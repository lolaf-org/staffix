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
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.FrameHandler;
import org.lolaf.staffix.ha.wire.FrameType;
import org.lolaf.staffix.ha.wire.SessionStatus;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * One connection to the leader: the handshake, then the leader's frames applied to the {@link Replica}, all on the
 * connection's IO thread. Every frame read is acknowledged at the end of the read, so a {@code SYNC} send on the
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

    private final IOSession connection;
    private final Replica replica;
    private final LongSupplier epoch;
    private final String nodeId;
    @Getter
    private volatile State state = State.AWAITING_WELCOME;
    @Getter
    private volatile @Nullable String leaderId;
    private long leaderEpoch;
    private boolean ackDue;

    LeaderLink(IOSession connection, Replica replica, LongSupplier epoch, String nodeId) {
        this.connection = connection;
        this.replica = replica;
        this.epoch = epoch;
        this.nodeId = nodeId;
    }

    void hello() {
        ByteBuffer hello = connection.borrow(FrameEncoder.bytesWith(FrameType.HELLO, nodeId));
        int length = FrameEncoder.hello(hello.clear(), 0, epoch.getAsLong(), nodeId, replica.getLastAppliedEpoch(),
                replica.getLastAppliedOffset());
        connection.send(written(hello, length), true);
    }

    @Override
    public void onWelcome(long epoch, int version, String leaderId, long resumeOffset) {
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
        this.leaderId = leaderId;
        leaderEpoch = epoch;
        boolean resumed = resumeOffset != WireFormat.NO_RESUME;
        replica.streamStarted(resumed);
        state = State.STREAMING;
        ackDue = true;
        log.info("Following leader {} at epoch {}, {}", leaderId, epoch,
                resumed ? "resuming at offset " + resumeOffset : "from its log tail, needing a snapshot");
    }

    @Override
    public void onSession(long epoch, int sessionKey, FixSessionType fixSessionType, ReplicationMode replicationMode,
                          String fixSessionId, @Nullable String storageIdentity) {
        if (!fromLeader(epoch)) {
            return;
        }
        sessionStatus(sessionKey, replica.bind(sessionKey, fixSessionType, fixSessionId, replicationMode, storageIdentity));
    }

    private void sessionStatus(int sessionKey, SessionStatus status) {
        ByteBuffer frame = connection.borrow(FrameEncoder.fixedBytes(FrameType.SESSION_STATUS));
        int length = FrameEncoder.sessionStatus(frame.clear(), 0, leaderEpoch, sessionKey, status);
        connection.send(written(frame, length), true);
    }

    @Override
    public void onStoreEvent(long epoch, long logOffset, int sessionKey, StoreEventType eventType, long sequenceNumber,
                             ByteBuffer buffer, int messageIndex, int messageLength) {
        if (!fromLeader(epoch)) {
            return;
        }
        if (!replica.apply(epoch, logOffset, sessionKey, eventType, sequenceNumber, buffer, messageIndex, messageLength)) {
            close(CloseReason.PROTOCOL_ERROR, "a STORE_EVENT for session key " + sessionKey + ", never announced");
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
            replica.handedOver();
        } else {
            log.warn("Leader {} closed the replication stream: {} {}", leaderId, reason, text);
        }
        state = State.CLOSED;
    }

    /**
     * Acknowledges what the read applied, once for all of its frames.
     */
    void afterRead() {
        if (!ackDue || state != State.STREAMING) {
            return;
        }
        ackDue = false;
        ByteBuffer frame = connection.borrow(FrameEncoder.fixedBytes(FrameType.ACK));
        int length = FrameEncoder.ack(frame.clear(), 0, leaderEpoch, replica.getLastAppliedOffset(), replica.isComplete());
        connection.send(written(frame, length), true);
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
        if (fromLeader(epoch) && !replica.snapshotBegin(sessionKey, nextIncomingSeqNum, nextOutgoingSeqNum)) {
            close(CloseReason.PROTOCOL_ERROR, "a snapshot of session key " + sessionKey + ", not replicated here");
        }
    }

    @Override
    public void onSnapshotMessage(long epoch, int sessionKey, long sequenceNumber, ByteBuffer buffer, int messageIndex,
                                  int messageLength) {
        if (fromLeader(epoch) && !replica.snapshotMessage(sessionKey, sequenceNumber, buffer, messageIndex, messageLength)) {
            close(CloseReason.PROTOCOL_ERROR, "a snapshot message of session key " + sessionKey + ", not replicated here");
        }
    }

    /**
     * Tells the leader the session is now in sync, which is when its {@code SYNC} sends start waiting for this
     * follower.
     */
    @Override
    public void onSnapshotEnd(long epoch, int sessionKey, int messageCount) {
        if (!fromLeader(epoch)) {
            return;
        }
        if (!replica.snapshotEnd(sessionKey, messageCount)) {
            close(CloseReason.PROTOCOL_ERROR, "a snapshot of session key " + sessionKey + " that does not end with "
                    + messageCount + " messages received");
            return;
        }
        sessionStatus(sessionKey, SessionStatus.REPLICATING);
        ackDue = true;
    }

    @Override
    public void onHello(long epoch, int version, String nodeId, long lastAppliedEpoch, long lastAppliedOffset) {
        unexpected(FrameType.HELLO);
    }

    @Override
    public void onSessionStatus(long epoch, int sessionKey, SessionStatus status) {
        unexpected(FrameType.SESSION_STATUS);
    }

    @Override
    public void onAck(long epoch, long appliedOffset, boolean inSync) {
        unexpected(FrameType.ACK);
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
        ByteBuffer frame = connection.borrow(FrameEncoder.bytesWith(FrameType.CLOSE, text));
        int length = FrameEncoder.close(frame.clear(), 0, epoch.getAsLong(), reason, text);
        connection.send(written(frame, length), true);
        connection.stop(Deadline.of(CLOSE_FLUSH));
    }

    void disconnected() {
        state = State.CLOSED;
    }

    private static ByteBuffer written(ByteBuffer buffer, int length) {
        return buffer.clear().position(length);
    }
}
