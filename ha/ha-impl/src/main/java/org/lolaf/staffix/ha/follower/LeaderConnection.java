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
import org.lolaf.staffix.ha.SessionSettingsDigests;
import org.lolaf.staffix.ha.wire.CloseReason;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.FrameHandler;
import org.lolaf.staffix.ha.wire.FrameType;
import org.lolaf.staffix.ha.wire.SessionAnswer;
import org.lolaf.staffix.ha.wire.WireFormat;
import org.lolaf.staffix.ha.wire.WireFormatException;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.LongSupplier;

/**
 * One connection to the leader, for one of its replication lanes: the handshake, then the leader's frames for the
 * lane's sessions applied to the {@link ReplicaSessions}, all on the connection's IO thread. Every frame read is
 * acknowledged at the end of the read, so a {@code SYNC} send on the leader waits one round trip, not a timer.
 *
 * <p>A frame from another epoch than the leader's WELCOME closes the connection: the leader it came from is not the
 * one this stream follows.
 */
@Slf4j
public final class LeaderConnection implements FrameHandler {

    public enum State {
        AWAITING_WELCOME,
        STREAMING,
        CLOSED
    }

    private static final Duration CLOSE_FLUSH = Duration.ofSeconds(1);
    static final long CHECK_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();
    /**
     * Fits an {@link AnswerSender.ThroughNetworkThread} frame, at three bytes a character.
     */
    private static final int MAX_CLOSE_TEXT = 1024;

    private final IOSession ioSession;
    private final ReplicaSessions replicaSessions;
    /**
     * This node's current election epoch, sent in its HELLO and CLOSE; a WELCOME of an older epoch is refused.
     */
    private final LongSupplier nodeEpoch;
    private final String nodeId;
    @Getter
    private final int lane;
    private final IntConsumer lanesAnnounced;
    @Getter
    private volatile State state = State.AWAITING_WELCOME;
    @Getter
    private volatile @Nullable String leaderId;
    /**
     * The epoch of the stream followed, from the leader's WELCOME: every later frame must carry it, and the answers
     * are stamped with it.
     */
    private volatile long streamEpoch;
    private boolean ackDue;
    private boolean applyFailed;
    private final AnswerSender answers;
    private final boolean receivedAckedByNetworkThread;
    private final long checkIntervalNanos;
    private final ObjIntConsumer<SessionAnswer> sendBoundAnswer = (answer, sessionKey) -> sendSessionAnswer(sessionKey, answer);
    private long rebindCheckedNanos;
    private long pendingWrites;
    private long pendingWritesReadNanos = System.nanoTime() - Long.MAX_VALUE / 2;
    private boolean ackedInSync;

    /**
     * @param lanesAnnounced told the leader's lane count from its WELCOME, before the stream starts
     */
    LeaderConnection(IOSession ioSession, ReplicaSessions replicaSessions, LongSupplier nodeEpoch, String nodeId, int lane, IntConsumer lanesAnnounced) {
        this(ioSession, replicaSessions, nodeEpoch, nodeId, lane, lanesAnnounced, CHECK_INTERVAL_NANOS);
    }

    LeaderConnection(IOSession ioSession, ReplicaSessions replicaSessions, LongSupplier nodeEpoch, String nodeId, int lane, IntConsumer lanesAnnounced,
               long checkIntervalNanos) {
        this(ioSession, replicaSessions, nodeEpoch, nodeId, lane, lanesAnnounced, checkIntervalNanos, new AnswerSender.OnNetworkThread(ioSession),
                false);
    }

    /**
     * @param answers                      how this lane's answers to the leader reach the connection
     * @param receivedAckedByNetworkThread the network thread acknowledges events as received as it reads them, a store
     *                                     writer thread writing them
     */
    LeaderConnection(IOSession ioSession, ReplicaSessions replicaSessions, LongSupplier nodeEpoch, String nodeId, int lane, IntConsumer lanesAnnounced,
               long checkIntervalNanos, AnswerSender answers, boolean receivedAckedByNetworkThread) {
        this.answers = answers;
        this.receivedAckedByNetworkThread = receivedAckedByNetworkThread;
        this.checkIntervalNanos = checkIntervalNanos;
        this.ioSession = ioSession;
        this.replicaSessions = replicaSessions;
        this.nodeEpoch = nodeEpoch;
        this.nodeId = nodeId;
        this.lane = lane;
        this.lanesAnnounced = lanesAnnounced;
    }

    void hello() {
        ByteBuffer hello = answers.claim(FrameEncoder.bytesWith(FrameType.HELLO, nodeId));
        answers.send(hello, FrameEncoder.hello(hello, 0, nodeEpoch.getAsLong(), nodeId, replicaSessions.getLastAppliedEpoch(lane),
                replicaSessions.getLastAppliedOffset(lane), lane));
    }

    /**
     * Whether an event of this epoch read now can be acknowledged as received: the stream it belongs to is the one
     * this lane follows.
     */
    boolean isStreamEpoch(long epoch) {
        return state == State.STREAMING && epoch == streamEpoch;
    }

    @Override
    public void onWelcome(long epoch, int version, String leaderId, long resumeOffset, int lanes) {
        if (state != State.AWAITING_WELCOME) {
            close(CloseReason.PROTOCOL_ERROR, "a second WELCOME");
            return;
        }
        if (version != WireFormat.VERSION) {
            close(CloseReason.VERSION_MISMATCH, "follower speaks version " + WireFormat.VERSION + ", leader " + version);
            return;
        }
        long currentEpoch = nodeEpoch.getAsLong();
        if (epoch < currentEpoch) {
            close(CloseReason.STALE_EPOCH, "leader is at epoch " + epoch + ", this follower at " + currentEpoch);
            return;
        }
        if (lane >= lanes) {
            close(CloseReason.PROTOCOL_ERROR, "a WELCOME on lane " + lane + " of " + lanes);
            return;
        }
        this.leaderId = leaderId;
        streamEpoch = epoch;
        lanesAnnounced.accept(lanes);
        boolean resumed = resumeOffset != WireFormat.NO_RESUME;
        replicaSessions.streamStarted(lane, resumed);
        state = State.STREAMING;
        ackDue = true;
        log.info("Following leader {} at epoch {} on lane {} of {}, {}", leaderId, epoch, lane, lanes,
                resumed ? "resuming at offset " + resumeOffset : "from its log tail, needing a snapshot");
    }

    @Override
    public void onSession(long epoch, int sessionKey, FixSessionType fixSessionType, ReplicationMode replicationMode,
                          String fixSessionId, @Nullable String storageIdentity) {
        if (!acceptsFrame(epoch)) {
            return;
        }
        if (!replicaSessions.carries(lane, sessionKey)) {
            close(CloseReason.PROTOCOL_ERROR, "session key " + sessionKey + " on lane " + lane + " of "
                    + replicaSessions.getLanes());
            return;
        }
        sendSessionAnswer(sessionKey, replicaSessions.bind(sessionKey, fixSessionType, fixSessionId, replicationMode, storageIdentity));
    }

    @Override
    public void onSessionSettingsDigest(long epoch, int generation, SessionSettingsDigests.Digest digest) {
        if (acceptsFrame(epoch)) {
            replicaSessions.getSettingsComparison().onDigest(generation, digest);
        }
    }

    @Override
    public void onSessionSettingsDigestsComplete(long epoch, int generation, int digestCount) {
        if (acceptsFrame(epoch)) {
            replicaSessions.getSettingsComparison().onDigestsComplete(generation, digestCount);
        }
    }

    private void sendSessionAnswer(int sessionKey, SessionAnswer answer) {
        ByteBuffer frame = answers.claim(FrameEncoder.fixedBytes(FrameType.SESSION_ANSWER));
        answers.send(frame, FrameEncoder.sessionAnswer(frame, 0, streamEpoch, sessionKey, answer));
    }

    @Override
    public void onStoreEvent(long epoch, long logOffset, int sessionKey, StoreEventType eventType, long sequenceNumber,
                             ByteBuffer buffer, int messageIndex, int messageLength, byte fragmentFlags) {
        if (!acceptsFrame(epoch)) {
            return;
        }
        if (!replicaSessions.apply(lane, epoch, logOffset, sessionKey, eventType, sequenceNumber, buffer, messageIndex, messageLength,
                fragmentFlags)) {
            close(CloseReason.PROTOCOL_ERROR, "a STORE_EVENT for session key " + sessionKey
                    + " never announced on this lane, or a fragment out of its message");
            return;
        }
        ackDue = true;
    }

    @Override
    public void onHeartbeat(long epoch, long logTailOffset) {
        if (acceptsFrame(epoch)) {
            ackDue = true;
        }
    }

    @Override
    public void onClose(long epoch, CloseReason reason, String text) {
        if (reason == CloseReason.SHUTDOWN && state == State.STREAMING && epoch == streamEpoch) {
            log.info("Leader {} stopped cleanly and handed over: {}", leaderId, text);
            replicaSessions.handedOver(lane);
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
            writeAndAck();
        } catch (RuntimeException e) {
            log.warn("Failed to apply what leader {} sent", leaderId, e);
            failed(CloseReason.APPLY_FAILED, e.toString());
        }
    }

    private void failed(CloseReason reason, String text) {
        applyFailed = true;
        close(reason, text);
    }

    private void writeAndAck() {
        long received = replicaSessions.takeReceivedToAck(lane);
        if (state == State.STREAMING && received > replicaSessions.getLastAppliedOffset(lane)) {
            if (!receivedAckedByNetworkThread) {
                ByteBuffer frame = answers.claim(FrameEncoder.fixedBytes(FrameType.RECEIVED));
                answers.send(frame, FrameEncoder.received(frame, 0, streamEpoch, received));
            }
            long receivedSentNanos = System.nanoTime();
            replicaSessions.writeApplied(lane);
            replicaSessions.getReceivedWriteLatency().record(System.nanoTime() - receivedSentNanos, TimeUnit.NANOSECONDS);
        } else {
            replicaSessions.writeApplied(lane);
        }
        if (state == State.STREAMING) {
            bindSessionsAgain();
        }
        if (!ackDue || state != State.STREAMING) {
            return;
        }
        ackDue = false;
        boolean inSync = replicaSessions.isLaneInSync(lane);
        ByteBuffer frame = answers.claim(FrameEncoder.fixedBytes(FrameType.ACK));
        answers.send(frame, FrameEncoder.ack(frame, 0, streamEpoch, replicaSessions.getLastAppliedOffset(lane), inSync,
                pendingWrites(inSync)));
    }

    /**
     * At most once a check interval, as it asks every session's store, and at once on turning in sync, when the leader
     * starts to trust it.
     */
    private long pendingWrites(boolean inSync) {
        long now = System.nanoTime();
        if (inSync != ackedInSync || now - pendingWritesReadNanos >= checkIntervalNanos) {
            ackedInSync = inSync;
            pendingWritesReadNanos = now;
            pendingWrites = replicaSessions.getPendingWrites(lane);
        }
        return pendingWrites;
    }

    /**
     * At most once a check interval, a second outside tests: the engine's sessions are listed to find them, and a
     * refusal is rare.
     */
    private void bindSessionsAgain() {
        long now = System.nanoTime();
        if (now - rebindCheckedNanos < checkIntervalNanos || !replicaSessions.hasSessionsToBindAgain(lane)) {
            return;
        }
        rebindCheckedNanos = now;
        replicaSessions.bindAgain(lane, sendBoundAnswer);
    }

    private boolean acceptsFrame(long epoch) {
        if (state != State.STREAMING) {
            close(CloseReason.PROTOCOL_ERROR, "a frame before WELCOME");
            return false;
        }
        if (epoch != streamEpoch) {
            close(CloseReason.STALE_EPOCH, "a frame of epoch " + epoch + " in a stream of epoch " + streamEpoch);
            return false;
        }
        return true;
    }

    @Override
    public void onSnapshotBegin(long epoch, int sessionKey, long snapshotOffset, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        if (acceptsFrame(epoch) && !replicaSessions.snapshotBegin(lane, sessionKey, nextIncomingSeqNum, nextOutgoingSeqNum)) {
            close(CloseReason.PROTOCOL_ERROR, "a snapshot of session key " + sessionKey + ", not replicated here");
        }
    }

    @Override
    public void onSnapshotMessage(long epoch, int sessionKey, long sequenceNumber, ByteBuffer buffer, int messageIndex,
                                  int messageLength, byte fragmentFlags) {
        if (acceptsFrame(epoch) && !replicaSessions.snapshotMessage(lane, sessionKey, sequenceNumber, buffer, messageIndex, messageLength,
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
        if (!acceptsFrame(epoch)) {
            return;
        }
        if (!replicaSessions.snapshotEnd(lane, sessionKey, messageCount, nextIncomingSeqNum, nextOutgoingSeqNum)) {
            close(CloseReason.PROTOCOL_ERROR, "a snapshot of session key " + sessionKey + " that does not end with "
                    + messageCount + " messages received");
            return;
        }
        sendSessionAnswer(sessionKey, SessionAnswer.REPLICATING);
        ackDue = true;
    }

    /**
     * Sends a CLOSE and disconnects, the connection flushing it first; the client connects again.
     */
    void close(CloseReason reason, String text) {
        if (state == State.CLOSED) {
            return;
        }
        log.warn("Closing the replication stream from leader {}: {} {}", leaderId, reason, text);
        state = State.CLOSED;
        String shortText = text.length() > MAX_CLOSE_TEXT ? text.substring(0, MAX_CLOSE_TEXT) : text;
        ByteBuffer frame = answers.claim(FrameEncoder.bytesWith(FrameType.CLOSE, shortText));
        answers.send(frame, FrameEncoder.close(frame, 0, nodeEpoch.getAsLong(), reason, shortText));
        ioSession.stop(Deadline.of(CLOSE_FLUSH));
    }

    void disconnected() {
        state = State.CLOSED;
    }

}
