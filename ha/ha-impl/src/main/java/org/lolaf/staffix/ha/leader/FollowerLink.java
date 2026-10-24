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
package org.lolaf.staffix.ha.leader;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.ReplicatedSession;
import org.lolaf.staffix.ha.ReplicatedSessions;
import org.lolaf.staffix.ha.log.ReplicationLog;
import org.lolaf.staffix.ha.wire.CloseReason;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.FrameHandler;
import org.lolaf.staffix.ha.wire.FrameType;
import org.lolaf.staffix.ha.wire.SessionStatus;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One connected follower: the handshake, the frames it sends back, and the thread streaming the log to it.
 *
 * <p>Frames from the follower arrive on the connection's IO thread; the log is streamed from a thread of the link's
 * own, which borrows a write buffer, reads as many events as fit straight into it behind room for their headers, and
 * sends it. A follower whose position is no longer in the log, or that the log laps, is out of sync: it needs a
 * snapshot, and only heartbeats reach it meanwhile.
 */
@Slf4j
public final class FollowerLink implements FrameHandler {

    public enum State {
        AWAITING_HELLO,
        STREAMING,
        OUT_OF_SYNC,
        CLOSED
    }

    private static final Duration CLOSE_FLUSH = Duration.ofSeconds(1);

    private final IOSession connection;
    private final ReplicationLog replicationLog;
    private final ReplicationLog.Reader reader;
    private final ReplicatedSessions sessions;
    private final LongSupplier epoch;
    private final String leaderId;
    private final long heartbeatIntervalNanos;
    private final int maxEventFrameBytes;
    private final int batchBytes;
    @Getter
    private volatile State state = State.AWAITING_HELLO;
    @Getter
    private volatile @Nullable String followerId;
    /**
     * The offset of the last event the follower applied, -1 before it said.
     */
    @Getter
    private volatile long appliedOffset = -1;
    private volatile boolean inSync;
    private volatile boolean paused;
    private int announced;
    private long lastSendNanos;
    private final Consumer<Runnable> senderLauncher;

    /**
     * @param senderLauncher starts the loop streaming the log, on a thread of its own
     */
    FollowerLink(IOSession connection, ReplicationLog log, ReplicatedSessions sessions, LongSupplier epoch, String leaderId,
                 Duration heartbeatInterval, Consumer<Runnable> senderLauncher) {
        this.senderLauncher = senderLauncher;
        this.connection = connection;
        this.replicationLog = log;
        this.reader = log.newReader();
        this.sessions = sessions;
        this.epoch = epoch;
        this.leaderId = leaderId;
        this.heartbeatIntervalNanos = heartbeatInterval.toNanos();
        this.maxEventFrameBytes = maxEventFrameBytes(log);
        this.batchBytes = batchBytesFor(log);
    }

    private static int maxEventFrameBytes(ReplicationLog log) {
        return WireFormat.STORE_EVENT_BODY_OFFSET + FrameEncoder.storeEventBodyBytes(log.getMaxMessageLength());
    }

    /**
     * The size of the write buffers a sender fills: room for the largest event, and for many small ones at once.
     */
    static int batchBytesFor(ReplicationLog log) {
        return Math.max(64 * 1024, maxEventFrameBytes(log));
    }

    @Override
    public void onHello(long followerEpoch, int version, String nodeId, long lastAppliedEpoch, long lastAppliedOffset) {
        followerId = nodeId;
        long leaderEpoch = epoch.getAsLong();
        if (state != State.AWAITING_HELLO) {
            close(CloseReason.PROTOCOL_ERROR, "a second HELLO");
            return;
        }
        if (version != WireFormat.VERSION) {
            close(CloseReason.VERSION_MISMATCH, "leader speaks version " + WireFormat.VERSION + ", follower " + version);
            return;
        }
        if (followerEpoch > leaderEpoch) {
            close(CloseReason.STALE_EPOCH, "follower is at epoch " + followerEpoch + ", this leader at " + leaderEpoch);
            return;
        }
        boolean resumed = lastAppliedEpoch == leaderEpoch && resume(lastAppliedOffset);
        if (!resumed) {
            reader.seekToTail();
        }
        appliedOffset = lastAppliedOffset;
        state = resumed ? State.STREAMING : State.OUT_OF_SYNC;
        log.info("Follower {} connected at epoch {}, {}", nodeId, leaderEpoch,
                resumed ? "resuming after offset " + lastAppliedOffset : "needing a snapshot");
        ByteBuffer welcome = connection.borrow(FrameEncoder.bytesWith(FrameType.WELCOME, leaderId));
        int length = FrameEncoder.welcome(welcome.clear(), 0, leaderEpoch, leaderId, resumed ? reader.getOffset() : WireFormat.NO_RESUME);
        connection.send(written(welcome, length), true);
        senderLauncher.accept(this::send);
    }

    /**
     * Positions the reader after the follower's last applied event, if that event is still in the log.
     */
    private boolean resume(long lastAppliedOffset) {
        if (lastAppliedOffset < 0 || !reader.seek(lastAppliedOffset)) {
            return false;
        }
        ByteBuffer skipped = ByteBuffer.allocate(maxEventFrameBytes);
        return reader.readInto(skipped, 0) >= 0;
    }

    @Override
    public void onAck(long epoch, long appliedOffset, boolean inSync) {
        this.appliedOffset = appliedOffset;
        this.inSync = inSync;
    }

    /**
     * Streaming, and caught up by its own account: a {@code SYNC} send waits for its ack.
     */
    public boolean isInSync() {
        return inSync && state == State.STREAMING;
    }

    @Override
    public void onSessionStatus(long epoch, int sessionKey, SessionStatus status) {
        if (status != SessionStatus.REPLICATING) {
            ReplicatedSession session = sessions.get(sessionKey);
            log.info("Follower {} does not replicate FIX session {} {}: {}", followerId, session.getFixSessionType(),
                    session.getFixSessionId(), status);
        }
    }

    @Override
    public void onClose(long epoch, CloseReason reason, String text) {
        log.info("Follower {} closed the replication stream: {} {}", followerId, reason, text);
        state = State.CLOSED;
    }

    @Override
    public void onWelcome(long epoch, int version, String leaderId, long resumeOffset) {
        unexpected(FrameType.WELCOME);
    }

    @Override
    public void onSession(long epoch, int sessionKey, FixSessionType fixSessionType, ReplicationMode replicationMode,
                          String fixSessionId, @Nullable String storageIdentity) {
        unexpected(FrameType.SESSION);
    }

    @Override
    public void onStoreEvent(long epoch, long logOffset, int sessionKey, StoreEventType eventType, long sequenceNumber,
                             ByteBuffer buffer, int messageIndex, int messageLength) {
        unexpected(FrameType.STORE_EVENT);
    }

    @Override
    public void onSnapshotBegin(long epoch, int sessionKey, long cutOffset, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        unexpected(FrameType.SNAPSHOT_BEGIN);
    }

    @Override
    public void onSnapshotMessage(long epoch, int sessionKey, long sequenceNumber, ByteBuffer buffer, int messageIndex,
                                  int messageLength) {
        unexpected(FrameType.SNAPSHOT_MESSAGE);
    }

    @Override
    public void onSnapshotEnd(long epoch, int sessionKey, int messageCount) {
        unexpected(FrameType.SNAPSHOT_END);
    }

    @Override
    public void onHeartbeat(long epoch, long logTailOffset) {
        unexpected(FrameType.HEARTBEAT);
    }

    private void unexpected(FrameType type) {
        close(CloseReason.PROTOCOL_ERROR, "a follower does not send " + type);
    }

    /**
     * Fills {@code dst} from index 0, up to its limit, with what the follower has not received: the sessions it does
     * not know yet, then events while a whole event still fits. An event of a session registered after this fill
     * began is left for the next one, behind that session's announcement.
     *
     * @return the bytes filled
     */
    int fill(ByteBuffer dst) {
        int filled = announce(dst, 0);
        long leaderEpoch = epoch.getAsLong();
        while (state == State.STREAMING && dst.limit() - filled >= maxEventFrameBytes) {
            int body = reader.readInto(dst, filled + WireFormat.STORE_EVENT_BODY_OFFSET);
            if (body == ReplicationLog.Reader.NO_EVENT) {
                break;
            }
            if (body == ReplicationLog.Reader.LAPPED) {
                outOfSync("the log overwrote events it had not been sent");
                break;
            }
            if (dst.getInt(filled + WireFormat.STORE_EVENT_BODY_OFFSET) >= announced) {
                if (!reader.seek(reader.getEventOffset())) {
                    outOfSync("the log overwrote an event while its session was announced");
                }
                break;
            }
            filled += FrameEncoder.storeEventHeader(dst, filled, leaderEpoch, reader.getEventOffset(), body);
        }
        return filled;
    }

    private int announce(ByteBuffer dst, int index) {
        int filled = index;
        long leaderEpoch = epoch.getAsLong();
        while (announced < sessions.count()) {
            ReplicatedSession session = sessions.get(announced);
            String id = session.getFixSessionId().toString();
            if (dst.limit() - filled < FrameEncoder.bytesWith(FrameType.SESSION, id, session.getStorageIdentity())) {
                break;
            }
            filled += FrameEncoder.session(dst, filled, leaderEpoch, session.getKey(), session.getFixSessionType(),
                    session.getReplicationMode(), id, session.getStorageIdentity());
            announced++;
        }
        return filled;
    }

    /**
     * Fills {@code dst} from index 0 with a heartbeat, when nothing was sent for a heartbeat interval.
     *
     * @return the bytes filled, 0 when no heartbeat is due
     */
    int heartbeatIfDue(ByteBuffer dst, long nowNanos) {
        if (nowNanos - lastSendNanos < heartbeatIntervalNanos) {
            return 0;
        }
        return FrameEncoder.heartbeat(dst, 0, epoch.getAsLong(), replicationLog.getTailOffset());
    }

    private void outOfSync(String reason) {
        state = State.OUT_OF_SYNC;
        inSync = false;
        log.warn("Follower {} is out of sync and needs a snapshot: {}", followerId, reason);
    }

    void pause(boolean paused) {
        this.paused = paused;
    }

    private void send() {
        IdleStrategy idle = new BackoffIdleStrategy();
        lastSendNanos = System.nanoTime();
        while (state != State.CLOSED) {
            if (paused) {
                idle.idle();
                continue;
            }
            ByteBuffer batch = connection.borrow(batchBytes);
            batch.clear().limit(batchBytes);
            int filled = fill(batch);
            if (filled == 0) {
                filled = heartbeatIfDue(batch, System.nanoTime());
            }
            if (filled == 0) {
                connection.unborrow(batch);
                idle.idle();
                continue;
            }
            connection.send(written(batch, filled), true);
            lastSendNanos = System.nanoTime();
            idle.reset();
        }
    }

    /**
     * Sends a CLOSE and disconnects, the connection flushing it first.
     */
    void close(CloseReason reason, String text) {
        log.warn("Closing the replication stream to follower {}: {} {}", followerId, reason, text);
        state = State.CLOSED;
        ByteBuffer frame = connection.borrow(FrameEncoder.bytesWith(FrameType.CLOSE, text));
        int length = FrameEncoder.close(frame.clear(), 0, epoch.getAsLong(), reason, text);
        connection.send(written(frame, length), true);
        connection.stop(Deadline.of(CLOSE_FLUSH));
    }

    /**
     * A buffer written from index 0 with absolute puts, as a send takes it: up to its position, which betty flips.
     */
    private static ByteBuffer written(ByteBuffer buffer, int length) {
        return buffer.clear().position(length);
    }

    /**
     * The connection is gone: the send loop ends at its next turn.
     */
    void disconnected() {
        state = State.CLOSED;
    }
}
