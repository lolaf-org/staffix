/*
 * Copyright © 2024-2026 Lolaf.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lolaf.staffix.ha.follower;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.wire.CloseReason;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.FrameHandler;
import org.lolaf.staffix.ha.wire.FrameType;
import org.lolaf.staffix.ha.wire.SessionStatus;
import org.lolaf.staffix.ha.wire.WireFormat;
import org.lolaf.staffix.ha.wire.WireFormatException;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/**
 * One connection to the leader, for one of its replication links: the handshake, then the leader's frames for the
 * link's sessions applied to the {@link Replica}, all on the connection's IO thread. Every frame read is acknowledged at the end of the read, so a {@code SYNC} send on the
 * leader waits one round trip, not a timer.
 *
 * <p>A frame from another epoch than the leader's WELCOME closes the connection: the leader it came from is not the
 * one this stream follows.
 */
@Slf4j
public final class LeaderLink implements FrameHandler {

    public enum State {
        AWAITING_WELCOME,
        STREAMING,
        CLOSED
    }

    private static final Duration CLOSE_FLUSH = Duration.ofSeconds(1);
    static final long REBIND_CHECK_NANOS = Duration.ofSeconds(1).toNanos();
    /**
     * Fits an {@link AnswerSender.ThroughNetworkThread} frame, at three bytes a character.
     */
    private static final int MAX_CLOSE_TEXT = 1024;

    private final IOSession connection;
    private final Replica replica;
    private final LongSupplier epoch;
    private final String nodeId;
    @Getter
    private final int link;
    private final IntConsumer onLinks;
    @Getter
    private volatile State state = State.AWAITING_WELCOME;
    @Getter
    private volatile @Nullable String leaderId;
    private volatile long leaderEpoch;
    private boolean ackDue;
    private boolean applyFailed;
    private final AnswerSender answers;
    private final boolean receivedAckedByNetworkThread;
    private final long rebindCheckNanos;
    private long rebindCheckedNanos;
    private long pendingWrites;
    private long pendingWritesReadNanos = System.nanoTime() - Long.MAX_VALUE / 2;
    private boolean ackedComplete;

    /**
     * @param onLinks told the leader's link count from its WELCOME, before the stream starts
     */
    LeaderLink(IOSession connection, Replica replica, LongSupplier epoch, String nodeId, int link, IntConsumer onLinks) {
        this(connection, replica, epoch, nodeId, link, onLinks, REBIND_CHECK_NANOS);
    }

    LeaderLink(IOSession connection, Replica replica, LongSupplier epoch, String nodeId, int link, IntConsumer onLinks,
               long rebindCheckNanos) {
        this(connection, replica, epoch, nodeId, link, onLinks, rebindCheckNanos, new AnswerSender.OnNetworkThread(connection),
                false);
    }

    /**
     * @param answers                      how this link's answers to the leader reach the connection
     * @param receivedAckedByNetworkThread the network thread acknowledges events as received as it reads them, a store
     *                                     writer thread writing them
     */
    LeaderLink(IOSession connection, Replica replica, LongSupplier epoch, String nodeId, int link, IntConsumer onLinks,
               long rebindCheckNanos, AnswerSender answers, boolean receivedAckedByNetworkThread) {
        this.answers = answers;
        this.receivedAckedByNetworkThread = receivedAckedByNetworkThread;
        this.rebindCheckNanos = rebindCheckNanos;
        this.connection = connection;
        this.replica = replica;
        this.epoch = epoch;
        this.nodeId = nodeId;
        this.link = link;
        this.onLinks = onLinks;
    }

    void hello() {
        ByteBuffer hello = answers.claim(FrameEncoder.bytesWith(FrameType.HELLO, nodeId));
        answers.send(hello, FrameEncoder.hello(hello, 0, epoch.getAsLong(), nodeId, replica.getLastAppliedEpoch(link),
                replica.getLastAppliedOffset(link), link));
    }

    /**
     * Whether an event of this epoch read now can be acknowledged as received: the stream it belongs to is the one
     * this link follows.
     */
    boolean followsEpoch(long epoch) {
        return state == State.STREAMING && epoch == leaderEpoch;
    }

    @Override
    public void onWelcome(long epoch, int version, String leaderId, long resumeOffset, int links) {
        if (state != State.AWAITING_WELCOME) {
            close(CloseReason.PROTOCOL_ERROR, "a second WELCOME");
            return;
        }
        if (version != WireFormat.VERSION) {
            close(CloseReason.VERSION_MISMATCH, "follower speaks version " + WireFormat.VERSION + ", leader " + version);
            return;
        }
        long followerEpoch = this.epoch.getAsLong();
        if (epoch < followerEpoch) {
            close(CloseReason.STALE_EPOCH, "leader is at epoch " + epoch + ", this follower at " + followerEpoch);
            return;
        }
        if (link >= links) {
            close(CloseReason.PROTOCOL_ERROR, "a WELCOME on link " + link + " of " + links);
            return;
        }
        this.leaderId = leaderId;
        leaderEpoch = epoch;
        onLinks.accept(links);
        boolean resumed = resumeOffset != WireFormat.NO_RESUME;
        replica.streamStarted(link, resumed);
        state = State.STREAMING;
        ackDue = true;
        log.info("Following leader {} at epoch {} on link {} of {}, {}", leaderId, epoch, link, links,
                resumed ? "resuming at offset " + resumeOffset : "from its log tail, needing a snapshot");
    }

    @Override
    public void onSession(long epoch, int sessionKey, FixSessionType fixSessionType, ReplicationMode replicationMode,
                          String fixSessionId, @Nullable String storageIdentity) {
        if (!fromLeader(epoch)) {
            return;
        }
        if (!replica.carries(link, sessionKey)) {
            close(CloseReason.PROTOCOL_ERROR, "session key " + sessionKey + " on link " + link + " of "
                    + replica.getLinks());
            return;
        }
        sessionStatus(sessionKey, replica.bind(sessionKey, fixSessionType, fixSessionId, replicationMode, storageIdentity));
    }

    private void sessionStatus(int sessionKey, SessionStatus status) {
        ByteBuffer frame = answers.claim(FrameEncoder.fixedBytes(FrameType.SESSION_STATUS));
        answers.send(frame, FrameEncoder.sessionStatus(frame, 0, leaderEpoch, sessionKey, status));
    }

    @Override
    public void onStoreEvent(long epoch, long logOffset, int sessionKey, StoreEventType eventType, long sequenceNumber,
                             ByteBuffer buffer, int messageIndex, int messageLength, byte fragmentFlags) {
        if (!fromLeader(epoch)) {
            return;
        }
        if (!replica.apply(link, epoch, logOffset, sessionKey, eventType, sequenceNumber, buffer, messageIndex, messageLength,
                fragmentFlags)) {
            close(CloseReason.PROTOCOL_ERROR, "a STORE_EVENT for session key " + sessionKey
                    + " never announced on this link, or a fragment out of its message");
            return;
        }
        ackDue = true;
    }

    @Override
    public void onHeartbeat(long epoch, long logTailOffset) {
        if (fromLeader(epoch)) {
            ackDue = true;
        }
    }

    @Override
    public void onClose(long epoch, CloseReason reason, String text) {
        if (reason == CloseReason.SHUTDOWN && state == State.STREAMING && epoch == leaderEpoch) {
            log.info("Leader {} stopped cleanly and handed over: {}", leaderId, text);
            replica.handedOver(link);
        } else if (reason == CloseReason.RETRY_LATER) {
            log.info("Leader {} holds this follower off: {}", leaderId, text);
        } else {
            log.warn("Leader {} closed the replication stream: {} {}", leaderId, reason, text);
        }
        state = State.CLOSED;
    }

    /**
     * Applies every whole frame of a read, then acknowledges it. A frame that cannot be decoded or applied closes the
     * stream: the frames after it in the read are dropped, and the follower reconnects to resume after the last event
     * it applied rather than carrying on past a gap.
     */
    void read(ByteBuffer message, FrameDecoder decoder) {
        int end = apply(message, message.position(), message.limit(), decoder);
        if (applyFailed) {
            message.position(message.limit());
            return;
        }
        endRead();
        message.position(end);
    }

    /**
     * Applies the whole frames from {@code index} to {@code limit}, a part of a read; {@link #endRead()} then writes
     * and acknowledges them.
     *
     * @return where the last whole frame ends, {@code limit} once a frame failed
     */
    int apply(ByteBuffer frames, int index, int limit, FrameDecoder decoder) {
        if (applyFailed) {
            return limit;
        }
        try {
            int consumed;
            while ((consumed = decoder.decode(frames, index, limit - index, this)) > 0) {
                index += consumed;
            }
            return index;
        } catch (WireFormatException e) {
            failed(CloseReason.PROTOCOL_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Failed to apply what leader {} sent", leaderId, e);
            failed(CloseReason.APPLY_FAILED, e.toString());
        }
        return limit;
    }

    /**
     * Writes and acknowledges what the frames applied since the last call left pending, unless one failed: those
     * after it are dropped, and the follower reconnects to resume after the last event it applied.
     */
    void endRead() {
        if (applyFailed) {
            return;
        }
        try {
            afterRead();
        } catch (RuntimeException e) {
            log.warn("Failed to apply what leader {} sent", leaderId, e);
            failed(CloseReason.APPLY_FAILED, e.toString());
        }
    }

    private void failed(CloseReason reason, String text) {
        applyFailed = true;
        close(reason, text);
    }

    private void afterRead() {
        long received = replica.takeReceivedToAck(link);
        if (state == State.STREAMING && received > replica.getLastAppliedOffset(link)) {
            if (!receivedAckedByNetworkThread) {
                ByteBuffer frame = answers.claim(FrameEncoder.fixedBytes(FrameType.RECEIVED));
                answers.send(frame, FrameEncoder.received(frame, 0, leaderEpoch, received));
            }
            long receivedSentNanos = System.nanoTime();
            replica.writeApplied(link);
            replica.getReceivedWriteLatency().record(System.nanoTime() - receivedSentNanos, TimeUnit.NANOSECONDS);
        } else {
            replica.writeApplied(link);
        }
        if (state == State.STREAMING) {
            bindSessionsAgain();
        }
        if (!ackDue || state != State.STREAMING) {
            return;
        }
        ackDue = false;
        boolean complete = replica.isComplete(link);
        ByteBuffer frame = answers.claim(FrameEncoder.fixedBytes(FrameType.ACK));
        answers.send(frame, FrameEncoder.ack(frame, 0, leaderEpoch, replica.getLastAppliedOffset(link), complete,
                pendingWrites(complete)));
    }

    /**
     * At most once a check interval, as it asks every session's store, and at once on turning in sync, when the leader
     * starts to trust it.
     */
    private long pendingWrites(boolean complete) {
        long now = System.nanoTime();
        if (complete != ackedComplete || now - pendingWritesReadNanos >= rebindCheckNanos) {
            ackedComplete = complete;
            pendingWritesReadNanos = now;
            pendingWrites = replica.getPendingWrites(link);
        }
        return pendingWrites;
    }

    /**
     * At most once a check interval, a second outside tests: the engine's sessions are listed to find them, and a
     * refusal is rare.
     */
    private void bindSessionsAgain() {
        long now = System.nanoTime();
        if (now - rebindCheckedNanos < rebindCheckNanos || !replica.hasSessionsToBindAgain(link)) {
            return;
        }
        rebindCheckedNanos = now;
        replica.bindAgain(link, (status, key) -> sessionStatus(key, status));
    }

    private boolean fromLeader(long epoch) {
        if (state != State.STREAMING) {
            close(CloseReason.PROTOCOL_ERROR, "a frame before WELCOME");
            return false;
        }
        if (epoch != leaderEpoch) {
            close(CloseReason.STALE_EPOCH, "a frame of epoch " + epoch + " in a stream of epoch " + leaderEpoch);
            return false;
        }
        return true;
    }

    @Override
    public void onSnapshotBegin(long epoch, int sessionKey, long cutOffset, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        if (fromLeader(epoch) && !replica.snapshotBegin(link, sessionKey, nextIncomingSeqNum, nextOutgoingSeqNum)) {
            close(CloseReason.PROTOCOL_ERROR, "a snapshot of session key " + sessionKey + ", not replicated here");
        }
    }

    @Override
    public void onSnapshotMessage(long epoch, int sessionKey, long sequenceNumber, ByteBuffer buffer, int messageIndex,
                                  int messageLength, byte fragmentFlags) {
        if (fromLeader(epoch) && !replica.snapshotMessage(link, sessionKey, sequenceNumber, buffer, messageIndex, messageLength,
                fragmentFlags)) {
            close(CloseReason.PROTOCOL_ERROR, "a snapshot message of session key " + sessionKey
                    + " not replicated here, or a fragment out of its message");
        }
    }

    /**
     * Tells the leader the session is now in sync, which is when its {@code SYNC} sends start waiting for this
     * follower.
     */
    @Override
    public void onSnapshotEnd(long epoch, int sessionKey, int messageCount, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        if (!fromLeader(epoch)) {
            return;
        }
        if (!replica.snapshotEnd(link, sessionKey, messageCount, nextIncomingSeqNum, nextOutgoingSeqNum)) {
            close(CloseReason.PROTOCOL_ERROR, "a snapshot of session key " + sessionKey + " that does not end with "
                    + messageCount + " messages received");
            return;
        }
        sessionStatus(sessionKey, SessionStatus.REPLICATING);
        ackDue = true;
    }

    @Override
    public void onHello(long epoch, int version, String nodeId, long lastAppliedEpoch, long lastAppliedOffset, int link) {
        unexpected(FrameType.HELLO);
    }

    @Override
    public void onSessionStatus(long epoch, int sessionKey, SessionStatus status) {
        unexpected(FrameType.SESSION_STATUS);
    }

    @Override
    public void onAck(long epoch, long appliedOffset, boolean inSync, long pendingWrites) {
        unexpected(FrameType.ACK);
    }

    @Override
    public void onReceived(long epoch, long receivedOffset) {
        unexpected(FrameType.RECEIVED);
    }

    private void unexpected(FrameType type) {
        close(CloseReason.PROTOCOL_ERROR, "a leader does not send " + type);
    }

    /**
     * Sends a CLOSE and disconnects, the connection flushing it first; the receiver connects again.
     */
    void close(CloseReason reason, String text) {
        if (state == State.CLOSED) {
            return;
        }
        log.warn("Closing the replication stream from leader {}: {} {}", leaderId, reason, text);
        state = State.CLOSED;
        String shortText = text.length() > MAX_CLOSE_TEXT ? text.substring(0, MAX_CLOSE_TEXT) : text;
        ByteBuffer frame = answers.claim(FrameEncoder.bytesWith(FrameType.CLOSE, shortText));
        answers.send(frame, FrameEncoder.close(frame, 0, epoch.getAsLong(), reason, shortText));
        connection.stop(Deadline.of(CLOSE_FLUSH));
    }

    void disconnected() {
        state = State.CLOSED;
    }

}
