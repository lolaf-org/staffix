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
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreMessagesConsumer;
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
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One connected follower: the handshake, the frames it sends back, and the thread streaming the log to it.
 *
 * <p>Frames from the follower arrive on the connection's IO thread; the log is streamed from a thread of the link's
 * own, which borrows a write buffer, reads as many events as fit straight into it behind room for their headers, and
 * sends it. A session the follower answers {@link SessionStatus#SNAPSHOT_NEEDED} for is snapshot by that thread
 * between two batches, so the snapshot is consistent with the stream: every event before the reader's position is in
 * the store it reads, and the events after it are sent next, applied twice at worst. A follower the log laps is
 * disconnected, so it knows it missed events and reconnects to be caught up.
 */
@Slf4j
public final class FollowerLink implements FrameHandler {

    public enum State {
        AWAITING_HELLO,
        STREAMING,
        CLOSED
    }

    /**
     * Where a session stands on this follower, as the leader knows it. A session whose storage the follower shares
     * is in sync: what the leader stored is already there.
     */
    public enum SessionSync {
        ANNOUNCED,
        SNAPSHOT_PENDING,
        SNAPSHOT_SENT,
        IN_SYNC,
        NOT_REPLICATED
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
    private final int snapshotMaxMessages;
    @Getter
    private volatile State state = State.AWAITING_HELLO;
    @Getter
    private volatile @Nullable String followerId;
    /**
     * The offset of the last event the follower applied, -1 before it said.
     */
    @Getter
    private volatile long appliedOffset = -1;
    /**
     * What the follower said of itself in its last ACK; {@link #isInSync(int)} relies on the leader's own account.
     */
    @Getter
    private volatile boolean reportedInSync;
    private volatile AtomicReferenceArray<SessionSync> sessionSyncs = new AtomicReferenceArray<>(16);
    private volatile boolean snapshotRequested;
    private volatile boolean paused;
    /**
     * Where the next batch starts reading the log: once it is the log's tail, every event has been handed to betty.
     */
    private volatile long sentOffset = -1;
    private volatile boolean handingOver;
    private volatile boolean senderRunning;
    private int announced;
    private long lastSendNanos;
    private final Consumer<Runnable> senderLauncher;

    /**
     * @param senderLauncher starts the loop streaming the log, on a thread of its own
     */
    FollowerLink(IOSession connection, ReplicationLog log, ReplicatedSessions sessions, LongSupplier epoch, String leaderId,
                 Duration heartbeatInterval, int snapshotMaxMessages, Consumer<Runnable> senderLauncher) {
        this.snapshotMaxMessages = snapshotMaxMessages;
        this.senderLauncher = senderLauncher;
        this.connection = connection;
        this.replicationLog = log;
        this.reader = log.newReader();
        this.sessions = sessions;
        this.epoch = epoch;
        this.leaderId = leaderId;
        this.heartbeatIntervalNanos = heartbeatInterval.toNanos();
        this.maxEventFrameBytes = log.getMaxEventFrameBytes();
        this.batchBytes = batchBytesFor(log);
    }

    /**
     * The size of the write buffers a sender fills: room for the largest event, and for many small ones at once.
     */
    static int batchBytesFor(ReplicationLog log) {
        return Math.max(64 * 1024, log.getMaxEventFrameBytes());
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
        state = State.STREAMING;
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
        this.reportedInSync = inSync;
    }

    /**
     * The follower holds everything of the session up to its last ack, so a {@code SYNC} send of the session waits
     * for its ack. Allocates nothing.
     */
    public boolean isInSync(int sessionKey) {
        return state == State.STREAMING && getSessionSync(sessionKey) == SessionSync.IN_SYNC;
    }

    public @Nullable SessionSync getSessionSync(int sessionKey) {
        AtomicReferenceArray<SessionSync> syncs = sessionSyncs;
        return sessionKey < syncs.length() ? syncs.get(sessionKey) : null;
    }

    private synchronized void sessionSync(int sessionKey, SessionSync sync) {
        AtomicReferenceArray<SessionSync> syncs = sessionSyncs;
        if (sessionKey >= syncs.length()) {
            AtomicReferenceArray<SessionSync> grown = new AtomicReferenceArray<>(Math.max(sessionKey + 1, syncs.length() * 2));
            for (int key = 0; key < syncs.length(); key++) {
                grown.set(key, syncs.get(key));
            }
            sessionSyncs = syncs = grown;
        }
        syncs.set(sessionKey, sync);
    }

    /**
     * The leader could not log one of the session's events, so the follower's copy has a gap: the session is no
     * longer in sync here until a snapshot refills it. A session not announced yet, or not replicated by this
     * follower, needs nothing.
     */
    public void resnapshot(int sessionKey) {
        SessionSync sync = getSessionSync(sessionKey);
        if (sync != null && sync != SessionSync.NOT_REPLICATED) {
            sessionSync(sessionKey, SessionSync.SNAPSHOT_PENDING);
            snapshotRequested = true;
        }
    }

    private synchronized boolean sessionSync(int sessionKey, SessionSync expected, SessionSync sync) {
        return sessionSyncs.compareAndSet(sessionKey, expected, sync);
    }

    @Override
    public void onSessionStatus(long epoch, int sessionKey, SessionStatus status) {
        if (getSessionSync(sessionKey) == null) {
            close(CloseReason.PROTOCOL_ERROR, "a SESSION_STATUS for session key " + sessionKey + ", never announced");
            return;
        }
        switch (status) {
            case REPLICATING:
            case SHARED_STORAGE:
                // a snapshot the leader asked for meanwhile still has to come
                if (getSessionSync(sessionKey) != SessionSync.SNAPSHOT_PENDING) {
                    sessionSync(sessionKey, SessionSync.IN_SYNC);
                }
                break;
            case SNAPSHOT_NEEDED:
                sessionSync(sessionKey, SessionSync.SNAPSHOT_PENDING);
                snapshotRequested = true;
                break;
            default:
                sessionSync(sessionKey, SessionSync.NOT_REPLICATED);
                ReplicatedSession session = sessions.get(sessionKey);
                log.warn("Follower {} does not replicate FIX session {} {}: {}", followerId, session.getFixSessionType(),
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
                close(CloseReason.LAPPED, "the log overwrote events not sent yet");
                break;
            }
            if (dst.getInt(filled + WireFormat.STORE_EVENT_BODY_OFFSET) >= announced) {
                if (!reader.seek(reader.getEventOffset())) {
                    close(CloseReason.LAPPED, "the log overwrote an event while its session was announced");
                }
                break;
            }
            filled += FrameEncoder.storeEventHeader(dst, filled, leaderEpoch, reader.getEventOffset(), body);
        }
        sentOffset = reader.getOffset();
        return filled;
    }

    private int announce(ByteBuffer dst, int index) {
        int filled = index;
        long leaderEpoch = epoch.getAsLong();
        while (announced < sessions.count()) {
            ReplicatedSession session = sessions.get(announced);
            String id = session.getFixSessionId().toString();
            ReplicatedSession.Config config = session.getConfig();
            if (dst.limit() - filled < FrameEncoder.bytesWith(FrameType.SESSION, id, config.getStorageIdentity())) {
                break;
            }
            filled += FrameEncoder.session(dst, filled, leaderEpoch, session.getKey(), session.getFixSessionType(),
                    config.getReplicationMode(), id, config.getStorageIdentity());
            sessionSync(announced, SessionSync.ANNOUNCED);
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

    void pause(boolean paused) {
        this.paused = paused;
    }

    private void send() {
        senderRunning = true;
        try {
            sendUntilClosedOrHandingOver();
        } finally {
            senderRunning = false;
        }
    }

    private void sendUntilClosedOrHandingOver() {
        IdleStrategy idle = new BackoffIdleStrategy();
        lastSendNanos = System.nanoTime();
        while (state != State.CLOSED && !handingOver) {
            if (paused) {
                idle.idle();
                continue;
            }
            if (snapshotRequested) {
                snapshotRequested = false;
                snapshotPending();
                lastSendNanos = System.nanoTime();
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
     * Snapshots every session the follower asked for, at the reader's position, which the stream then resumes from.
     */
    void snapshotPending() {
        for (int key = 0; key < announced && state == State.STREAMING; key++) {
            // marked sent before it goes: the follower's REPLICATING may come back before the snapshot's last frame
            // is handed over, and a session still pending then would wait for a second snapshot
            if (sessionSync(key, SessionSync.SNAPSHOT_PENDING, SessionSync.SNAPSHOT_SENT) && !snapshot(key)) {
                sessionSync(key, SessionSync.SNAPSHOT_SENT, SessionSync.SNAPSHOT_PENDING);
            }
        }
    }

    private boolean snapshot(int sessionKey) {
        ReplicatedSession session = sessions.get(sessionKey);
        FixSessionMessagesStore store = session.getConfig().getStore();
        SnapshotWriter writer = new SnapshotWriter(sessionKey, epoch.getAsLong());
        try {
            long nextOutgoingSeqNum = store.getOutgoingSeqNum();
            writer.begin(reader.getOffset(), store.getIncomingSeqNum(), nextOutgoingSeqNum);
            store.find(Math.max(1, nextOutgoingSeqNum - snapshotMaxMessages), nextOutgoingSeqNum - 1, writer);
            writer.end();
            log.info("Snapshot of FIX session {} {} sent to follower {}: {} messages", session.getFixSessionType(),
                    session.getFixSessionId(), followerId, writer.messageCount);
            return true;
        } catch (RuntimeException e) {
            log.warn("Snapshot of FIX session {} {} failed", session.getFixSessionType(), session.getFixSessionId(), e);
            close(CloseReason.SNAPSHOT_FAILED, "FIX session " + session.getFixSessionId() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Writes a snapshot's frames into batch buffers, sending each when the next frame does not fit.
     */
    private final class SnapshotWriter implements StoreMessagesConsumer {

        private final int sessionKey;
        private final long leaderEpoch;
        private ByteBuffer batch;
        private int filled;
        private int messageCount;

        SnapshotWriter(int sessionKey, long leaderEpoch) {
            this.sessionKey = sessionKey;
            this.leaderEpoch = leaderEpoch;
            this.batch = connection.borrow(batchBytes);
        }

        void begin(long cutOffset, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
            room(FrameEncoder.fixedBytes(FrameType.SNAPSHOT_BEGIN));
            filled += FrameEncoder.snapshotBegin(batch, filled, leaderEpoch, sessionKey, cutOffset, nextIncomingSeqNum,
                    nextOutgoingSeqNum);
        }

        /**
         * A message too long for a batch is left out: a resend of it is answered with a gap fill.
         */
        @Override
        public boolean onMessage(long seqNum, ByteBuffer message) {
            int bytes = FrameEncoder.snapshotMessageBytes(message.remaining());
            if (bytes > batchBytes) {
                log.warn("Message {} of {} bytes left out of a snapshot to follower {}", seqNum, message.remaining(), followerId);
                return true;
            }
            room(bytes);
            filled += FrameEncoder.snapshotMessage(batch, filled, leaderEpoch, sessionKey, seqNum, message);
            messageCount++;
            return state == State.STREAMING;
        }

        void end() {
            room(FrameEncoder.fixedBytes(FrameType.SNAPSHOT_END));
            filled += FrameEncoder.snapshotEnd(batch, filled, leaderEpoch, sessionKey, messageCount);
            connection.send(written(batch, filled), true);
        }

        private void room(int bytes) {
            if (batchBytes - filled >= bytes) {
                return;
            }
            connection.send(written(batch, filled), true);
            IdleStrategy idle = new BackoffIdleStrategy();
            while (paused && state == State.STREAMING) {
                idle.idle();
            }
            batch = connection.borrow(batchBytes);
            filled = 0;
        }
    }

    /**
     * For a leader stopping cleanly, its sessions already stopped: waits for every event of the log to be sent, then
     * closes with {@link CloseReason#SHUTDOWN}. Arriving after them on the same connection, it tells the follower it
     * holds everything, so the follower takes over without moving its seqnums ahead. When the deadline passes first,
     * the link is dropped without it.
     *
     * @return whether the follower was told
     */
    boolean handOver(Deadline deadline) {
        if (state != State.STREAMING) {
            return false;
        }
        boolean sentAll = deadline.waitAsLongAs(() -> state == State.STREAMING && sentOffset != replicationLog.getTailOffset())
                && state == State.STREAMING;
        handingOver = true;
        boolean senderStopped = deadline.waitAsLongAs(() -> senderRunning);
        if (!sentAll || !senderStopped) {
            log.warn("Follower {} was not sent every event before the deadline: it will take over with its seqnums moved ahead",
                    followerId);
            state = State.CLOSED;
            connection.stop(Deadline.immediate());
            return false;
        }
        close(CloseReason.SHUTDOWN, "the leader stopped cleanly, every event was sent");
        return true;
    }

    /**
     * Sends a CLOSE and disconnects, the connection flushing it first.
     */
    void close(CloseReason reason, String text) {
        if (reason == CloseReason.SHUTDOWN) {
            log.info("Closing the replication stream to follower {}: {} {}", followerId, reason, text);
        } else {
            log.warn("Closing the replication stream to follower {}: {} {}", followerId, reason, text);
        }
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
