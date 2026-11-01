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
import org.lolaf.betty.api.io.IOWriter.MessageSentCallback;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.ringos.rb.RingBuffer;
import org.lolaf.ringos.rb.RingBufferFactory;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.monitoring.Timer;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreMessagesConsumer;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.ReplicatedSession;
import org.lolaf.staffix.ha.ReplicatedSessions;
import org.lolaf.staffix.ha.log.ReplicationLog;
import org.lolaf.staffix.ha.log.ReplicationLogs;
import org.lolaf.staffix.ha.wire.*;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One connected follower: the handshake, the frames it sends back, and the thread streaming the log to it.
 *
 * <p>Frames from the follower arrive on the connection's IO thread; the log is streamed from a thread of the link's
 * own, which takes one of its own write buffers, reads as many events as fit straight into it behind room for their
 * headers, and sends it. While streaming, its reader holds appends back rather than be lapped; a writer that waited
 * the log full timeout for it drops it, and it reconnects to be caught up.
 *
 * <p>A session the follower answers {@link SessionStatus#SNAPSHOT_NEEDED} for is snapshot by that thread one chunk per
 * batch, so the other sessions keep streaming. Its own events are skipped meanwhile, except a reset, which ends the
 * snapshot: a session's message is in its store before its event is in the log, so the snapshot copies what was
 * skipped, and the follower's store is written in seqnum order as by the stream. The snapshot copies in rounds, each up
 * to the store's last message when it starts, and ends with a round that fits in one chunk; a round no shorter than the
 * one before, the session writing faster than it is copied, is sent whole, holding the other sessions for its time.
 */
@Slf4j
public final class FollowerLink implements FrameHandler {

    private static final Duration CLOSE_FLUSH = Duration.ofSeconds(1);
    private static final long STORE_RETRY_NANOS = Duration.ofMillis(10).toNanos();
    private final IOSession connection;
    private final ReplicationLogs logs;
    /**
     * Link 0's until the HELLO says which link this connection carries.
     */
    private volatile ReplicationLog replicationLog;
    /**
     * Gating from the follower's HELLO until the link closes, from any thread.
     */
    private volatile ReplicationLog.Reader reader;
    private final ReplicatedSessions sessions;
    private final LongSupplier epoch;
    private final String leaderId;
    private final long heartbeatIntervalNanos;
    private final int maxEventFrameBytes;
    private final int batchBytes;
    @Getter
    private volatile int link;
    private final int sendBuffers;
    private final int snapshotChunkBytes;
    private final IdleStrategy senderIdle;
    /**
     * Batch buffers betty wrote, handed back by its IO thread. The link owns its batch buffers rather than borrowing
     * the connection's: betty frees a pool's memory when the connection stops, whichever thread still writes into one.
     */
    private final RingBuffer<ByteBuffer> writtenBatches;
    private final MessageSentCallback<ByteBuffer> batchWritten = this::batchWritten;
    /**
     * The batch last written, handed back with the next: betty restores its position and limit once the callback
     * returns, which would land on a sender already filling it. IO thread only.
     */
    private @Nullable ByteBuffer lastWrittenBatch;
    private final IdleStrategy sendBufferWait = new BackoffIdleStrategy();
    private int batchesAllocated;
    /**
     * A batch buffer taken for a pass with nothing to send, kept for the next one.
     */
    private @Nullable ByteBuffer spareBatch;
    private final ReplicationStats stats;
    /**
     * What the follower last said of each session, by key, which tells a shared storage and a refusal's reason apart.
     */
    private final Map<Integer, SessionStatus> reportedStatuses = new ConcurrentHashMap<>();
    /**
     * Keys of sessions already announced whose replication mode changed since.
     */
    private final Queue<Integer> reannouncements = new ConcurrentLinkedQueue<>();
    private final Consumer<Runnable> senderLauncher;
    private boolean sendBuffersExhausted;
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
    private volatile long lastAckNanos;
    /**
     * The offset a {@code SYNC} send gave up waiting for, -1 when none: the follower is not waited for again until it
     * applies it, so a hung follower costs one timeout rather than one per send.
     */
    private volatile long overdueOffset = -1;
    private volatile AtomicReferenceArray<SessionSync> sessionSyncs = new AtomicReferenceArray<>(16);
    private volatile boolean snapshotRequested;
    /**
     * The session being snapshot, only one at a time; read and written by the sender thread only.
     */
    private @Nullable SnapshotCursor snapshot;
    private long storeRetryNanos = Long.MAX_VALUE;
    private volatile boolean paused;
    /**
     * Where the next batch starts reading the log: once it is the log's tail, every event has been handed to betty.
     */
    private volatile long sentOffset = -1;
    /**
     * Dropped as too slow last time, until its first in-sync ack; IO thread only.
     */
    private boolean recoveringFromTooSlow;
    private volatile boolean handingOver;
    private volatile boolean senderRunning;
    private int announced;
    private long lastSendNanos;
    private final long stallTimeoutNanos;
    private long lastSentEventOffset = -1;
    /**
     * When the follower last went from owing nothing to owing an ack, sender thread only.
     */
    private long owedSinceNanos;
    /**
     * The offset of the last event the last {@link #fill} took, -1 when it took none.
     */
    private long filledEventOffset = -1;
    private AckLatencySampler ackLatency = new AckLatencySampler(Timer.VoidTimer.getInstance());
    private LagSampler lag = new LagSampler(Timer.VoidTimer.getInstance());

    /**
     * @param sendBuffers    the batch buffers the link allocates at most, a power of two; the one written last is
     *                       reused only once the next is written
     * @param snapshotChunkBytes the most bytes of a snapshot sent between two live batches, at most a batch
     * @param senderLauncher starts the loop streaming the log, on a thread of its own
     */
    FollowerLink(IOSession connection, ReplicationLogs logs, ReplicatedSessions sessions, LongSupplier epoch, String leaderId,
                 Duration heartbeatInterval, Duration stallTimeout, int sendBuffers, int snapshotChunkBytes, IdleStrategy senderIdle,
                 ReplicationStats stats, Consumer<Runnable> senderLauncher) {
        this.sendBuffers = sendBuffers;
        this.writtenBatches = RingBufferFactory.build(RingBufferFactory.AccessType.SINGLE_CONSUMER_SINGLE_PRODUCER, sendBuffers);
        this.senderIdle = senderIdle;
        this.stats = stats;
        this.senderLauncher = senderLauncher;
        this.connection = connection;
        this.logs = logs;
        this.replicationLog = logs.get(0);
        this.reader = replicationLog.newReader();
        this.sessions = sessions;
        this.epoch = epoch;
        this.leaderId = leaderId;
        this.heartbeatIntervalNanos = heartbeatInterval.toNanos();
        this.stallTimeoutNanos = stallTimeout.toNanos();
        this.maxEventFrameBytes = logs.getMaxEventFrameBytes();
        this.batchBytes = WireFormat.bufferBytes(logs.getMaxEventFrameBytes());
        this.snapshotChunkBytes = Math.min(snapshotChunkBytes, batchBytes);
    }

    /**
     * A buffer written from index 0 with absolute puts, as a send takes it: up to its position, which betty flips.
     */
    private static ByteBuffer written(ByteBuffer buffer, int length) {
        return buffer.clear().position(length);
    }

    @Override
    public void onHello(long followerEpoch, int version, String nodeId, long lastAppliedEpoch, long lastAppliedOffset, int link) {
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
        if (link >= logs.count()) {
            close(CloseReason.NO_SUCH_LINK, "link " + link + " of " + logs.count());
            return;
        }
        this.link = link;
        replicationLog = logs.get(link);
        Optional<Instant> heldOffUntil = stats.getSlowFollowers().heldOffUntil(nodeId, link);
        if (heldOffUntil.isPresent()) {
            close(CloseReason.RETRY_LATER, "dropped as too slow, held off until " + heldOffUntil.get());
            return;
        }
        reader = replicationLog.newGatingReader(this::droppedAsTooSlow);
        recoveringFromTooSlow = stats.getSlowFollowers().isNotBackInSync(nodeId, link);
        boolean resumed = lastAppliedEpoch == leaderEpoch && resume(lastAppliedOffset);
        if (!resumed) {
            reader.seekToTail();
        }
        appliedOffset = lastAppliedOffset;
        ackLatency = new AckLatencySampler(stats.ackLatency(nodeId, link));
        lag = new LagSampler(stats.lag(nodeId, link));
        state = State.STREAMING;
        log.info("Follower {} connected link {} of {} at epoch {}, {}", nodeId, link, logs.count(), leaderEpoch,
                resumed ? "resuming after offset " + lastAppliedOffset : "needing a snapshot");
        ByteBuffer welcome = connection.borrow(FrameEncoder.bytesWith(FrameType.WELCOME, leaderId));
        int length = FrameEncoder.welcome(welcome.clear(), 0, leaderEpoch, leaderId, resumed ? reader.getOffset() : WireFormat.NO_RESUME, logs.count());
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
        if (inSync && recoveringFromTooSlow) {
            recoveringFromTooSlow = false;
            stats.getSlowFollowers().backInSync(followerId, link);
        }
        lastAckNanos = System.nanoTime();
        long overdue = overdueOffset;
        if (overdue >= 0 && appliedOffset >= overdue) {
            overdueOffset = -1;
        }
        ackLatency.onAck(appliedOffset);
        lag.onAck(appliedOffset);
    }

    /**
     * How long the follower has been behind a write it has not applied yet, 0 when it is not, or not streaming.
     */
    public long getLagNanos(long nowNanos) {
        return state == State.STREAMING ? lag.getPendingNanos(nowNanos) : 0;
    }

    /**
     * The follower holds everything of the session up to its last ack, so a {@code SYNC} send of the session waits
     * for its ack. Allocates nothing.
     */
    public boolean isInSync(int sessionKey) {
        return state == State.STREAMING && getSessionSync(sessionKey) == SessionSync.IN_SYNC;
    }

    boolean isAckOverdue() {
        return overdueOffset >= 0;
    }

    /**
     * A {@code SYNC} send stopped waiting for this follower to apply the event at {@code offset}.
     */
    void ackOverdue(long offset) {
        overdueOffset = offset;
        if (appliedOffset >= offset) {
            overdueOffset = -1;
        }
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

    private synchronized boolean sessionSync(int sessionKey, SessionSync expected, SessionSync sync) {
        return sessionSyncs.compareAndSet(sessionKey, expected, sync);
    }

    @Override
    public void onSessionStatus(long epoch, int sessionKey, SessionStatus status) {
        if (getSessionSync(sessionKey) == null) {
            close(CloseReason.PROTOCOL_ERROR, "a SESSION_STATUS for session key " + sessionKey + ", never announced");
            return;
        }
        reportedStatuses.put(sessionKey, status);
        switch (status) {
            case REPLICATING:
            case SHARED_STORAGE:
                sessionSync(sessionKey, SessionSync.IN_SYNC);
                break;
            case SNAPSHOT_NEEDED:
                sessionSync(sessionKey, SessionSync.SNAPSHOT_PENDING);
                snapshotRequested = true;
                break;
            case NOT_REPLICATED:
                sessionSync(sessionKey, SessionSync.NOT_REPLICATED);
                break;
            default:
                sessionSync(sessionKey, SessionSync.NOT_REPLICATED);
                ReplicatedSession session = sessions.get(sessionKey);
                log.warn("Follower {} does not replicate FIX session {} {}: {}", followerId, session.getFixSessionType(),
                        session.getFixSessionId(), status);
        }
    }

    /**
     * Sends the session again by snapshot, which resets the follower's copy first. Only a copy the follower holds is
     * replaced: a session it shares the storage of, refuses or is still catching up on is left as it is.
     */
    void resync(int sessionKey) {
        if (carries(sessionKey) && reportedStatuses.get(sessionKey) == SessionStatus.REPLICATING
                && sessionSync(sessionKey, SessionSync.IN_SYNC, SessionSync.SNAPSHOT_PENDING)) {
            snapshotRequested = true;
        }
    }

    public @Nullable SessionStatus getReportedStatus(int sessionKey) {
        return reportedStatuses.get(sessionKey);
    }

    /**
     * @param sessionCount the sessions the leader replicates, every one of which the follower must hold to be in sync
     */
    public HaStatus.Follower status(int sessionCount) {
        boolean snapshotInProgress = false;
        for (int key = 0; key < sessionCount; key++) {
            SessionSync sync = getSessionSync(key);
            snapshotInProgress |= sync == SessionSync.SNAPSHOT_PENDING || sync == SessionSync.SNAPSHOT_SENT;
        }
        return HaStatus.Follower.builder()
                .nodeId(followerId)
                .link(link)
                .address(String.valueOf(connection.getSocketAddress()))
                .connected(isConnected())
                .inSync(isInSyncForAll(sessionCount))
                .snapshotInProgress(snapshotInProgress)
                .unsentBytes(getUnsentBytes())
                .lagMillis(TimeUnit.NANOSECONDS.toMillis(getLagNanos(System.nanoTime())))
                .build();
    }

    public boolean isConnected() {
        return state == State.STREAMING;
    }

    /**
     * @param sessionCount the sessions the leader replicates, every one of which the follower must hold
     */
    public boolean isInSyncForAll(int sessionCount) {
        for (int key = 0; key < sessionCount; key++) {
            if (carries(key) && !isInSync(key) && sessions.get(key).isReplicated()) {
                return false;
            }
        }
        return isConnected();
    }

    /**
     * Whether the session is replicated over this link, rather than another of the follower's.
     */
    public boolean carries(int sessionKey) {
        return logs.linkOf(sessionKey) == link;
    }

    /**
     * The bytes the leader wrote and has not sent this follower, -1 before it started sending.
     */
    public long getUnsentBytes() {
        long sent = sentOffset;
        return sent < 0 ? -1 : replicationLog.getTailOffset() - sent;
    }

    @Override
    public void onClose(long epoch, CloseReason reason, String text) {
        log.info("Follower {} closed the replication stream: {} {}", followerId, reason, text);
        state = State.CLOSED;
    }

    @Override
    public void onWelcome(long epoch, int version, String leaderId, long resumeOffset, int links) {
        unexpected(FrameType.WELCOME);
    }

    @Override
    public void onSession(long epoch, int sessionKey, FixSessionType fixSessionType, ReplicationMode replicationMode,
                          String fixSessionId, @Nullable String storageIdentity) {
        unexpected(FrameType.SESSION);
    }

    @Override
    public void onStoreEvent(long epoch, long logOffset, int sessionKey, StoreEventType eventType, long sequenceNumber,
                             ByteBuffer buffer, int messageIndex, int messageLength, byte fragmentFlags) {
        unexpected(FrameType.STORE_EVENT);
    }

    @Override
    public void onSnapshotBegin(long epoch, int sessionKey, long cutOffset, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        unexpected(FrameType.SNAPSHOT_BEGIN);
    }

    @Override
    public void onSnapshotMessage(long epoch, int sessionKey, long sequenceNumber, ByteBuffer buffer, int messageIndex,
                                  int messageLength, byte fragmentFlags) {
        unexpected(FrameType.SNAPSHOT_MESSAGE);
    }

    @Override
    public void onSnapshotEnd(long epoch, int sessionKey, int messageCount, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
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
     * not know yet, then events until the next one does not fit. An event of a session registered after this fill
     * began is left for the next one, behind that session's announcement.
     *
     * @return the bytes filled
     */
    int fill(ByteBuffer dst) {
        lag.onFill(replicationLog.getTailOffset(), reader.getOffset(), reader.getEventOffset(), appliedOffset);
        int filled = announce(dst);
        long leaderEpoch = epoch.getAsLong();
        filledEventOffset = -1;
        while (state == State.STREAMING && dst.limit() - filled >= WireFormat.STORE_EVENT_BODY_OFFSET) {
            int body = reader.readInto(dst, filled + WireFormat.STORE_EVENT_BODY_OFFSET);
            if (body < 0) {
                break;
            }
            int sessionKey = dst.getInt(filled + WireFormat.STORE_EVENT_BODY_OFFSET);
            if (sessionKey >= announced) {
                reader.unread();
                break;
            }
            if (snapshot != null && snapshot.sessionKey == sessionKey) {
                if (dst.get(filled + WireFormat.STORE_EVENT_BODY_OFFSET + Integer.BYTES) != StoreEventType.RESET_SEQ_NUM.getCode()) {
                    continue;
                }
                // left for the stream, after the snapshot's end
                snapshot.resetAhead = true;
                reader.unread();
                break;
            }
            filledEventOffset = reader.getEventOffset();
            lag.onEventRead(filledEventOffset, reader.getOffset());
            filled += FrameEncoder.storeEventHeader(dst, filled, leaderEpoch, filledEventOffset, body);
        }
        sentOffset = reader.getOffset();
        return filled;
    }

    private int announce(ByteBuffer dst) {
        int filled = 0;
        long leaderEpoch = epoch.getAsLong();
        while (announced < sessions.count()) {
            if (carries(announced)) {
                int bytes = announce(dst, filled, leaderEpoch, sessions.get(announced));
                if (bytes == 0) {
                    return filled;
                }
                filled += bytes;
            }
            announced++;
        }
        Integer key;
        while ((key = reannouncements.peek()) != null) {
            // a key not announced yet goes out with its current mode once it is
            if (key < announced && carries(key)) {
                int bytes = announce(dst, filled, leaderEpoch, sessions.get(key));
                if (bytes == 0) {
                    return filled;
                }
                filled += bytes;
                if (snapshot != null && snapshot.sessionKey == key) {
                    // the follower binds the session again and says whether it still needs one
                    snapshot = null;
                }
            }
            reannouncements.poll();
        }
        return filled;
    }

    /**
     * @return the bytes written, 0 when the frame does not fit
     */
    private int announce(ByteBuffer dst, int index, long leaderEpoch, ReplicatedSession session) {
        String id = session.getFixSessionId().toString();
        ReplicatedSession.Config config = session.getConfig();
        if (dst.limit() - index < FrameEncoder.bytesWith(FrameType.SESSION, id, config.getStorageIdentity())) {
            return 0;
        }
        sessionSync(session.getKey(), SessionSync.ANNOUNCED);
        return FrameEncoder.session(dst, index, leaderEpoch, session.getKey(), session.getFixSessionType(),
                config.getReplicationMode(), id, config.getStorageIdentity());
    }

    /**
     * The session's replication mode changed: the follower stops holding it, catches up, or checks its own settings
     * agree.
     */
    void reannounce(int sessionKey) {
        reannouncements.add(sessionKey);
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
        senderIdle.reset();
        lastSendNanos = System.nanoTime();
        lastAckNanos = lastSendNanos;
        while (state != State.CLOSED && !handingOver) {
            if (isStalled(System.nanoTime())) {
                close(CloseReason.STALLED, "no ack for " + TimeUnit.NANOSECONDS.toMillis(stallTimeoutNanos)
                        + " ms with writes unapplied");
                break;
            }
            if (hasNoRoomToSend()) {
                senderIdle.idle();
                continue;
            }
            startPendingSnapshot();
            boolean sent = sendBatch();
            if (snapshot != null && state == State.STREAMING) {
                sendSnapshotChunk();
                sent = true;
            }
            if (sent) {
                lastSendNanos = System.nanoTime();
                senderIdle.reset();
            } else {
                senderIdle.idle();
            }
        }
    }

    private boolean sendBatch() {
        ByteBuffer batch = takeBatch();
        int filled = fill(batch);
        if (filled == 0 && state == State.STREAMING) {
            filled = heartbeatIfDue(batch, System.nanoTime());
        }
        if (filled == 0 || state != State.STREAMING) {
            spareBatch = batch;
            return false;
        }
        if (filledEventOffset >= 0) {
            long nowNanos = System.nanoTime();
            if (lastSentEventOffset <= appliedOffset) {
                owedSinceNanos = nowNanos;
            }
            lastSentEventOffset = filledEventOffset;
            // before the send: an ack processed in between would otherwise leave the sample to the next one
            ackLatency.onSent(filledEventOffset, nowNanos);
        }
        sendBatch(batch, filled);
        return true;
    }

    private void batchWritten(ByteBuffer message, Exception error, ByteBuffer batch) {
        ByteBuffer previous = lastWrittenBatch;
        lastWrittenBatch = batch;
        if (previous != null) {
            writtenBatches.offer(previous);
        }
    }

    /**
     * Owing an ack and silent for the stall timeout: a slow follower still acks each read, a hung one does not.
     */
    private boolean isStalled(long nowNanos) {
        return stallTimeoutNanos > 0 && lastSentEventOffset > appliedOffset
                && nowNanos - Math.max(owedSinceNanos, lastAckNanos) > stallTimeoutNanos;
    }

    private ByteBuffer takeBatch() {
        ByteBuffer batch = spareBatch;
        if (batch != null) {
            spareBatch = null;
            return batch;
        }
        batch = writtenBatches.poll();
        if (batch == null) {
            batchesAllocated++;
            batch = ByteBuffer.allocateDirect(batchBytes);
        }
        // betty flips a buffer it writes
        return batch.clear();
    }

    private void sendBatch(ByteBuffer batch, int length) {
        connection.send(written(batch, length), batch, batchWritten, false);
    }

    /**
     * Neither betty's write watermark is reached nor every send buffer in flight.
     */
    private boolean hasNoRoomToSend() {
        boolean exhausted = spareBatch == null && batchesAllocated >= sendBuffers && writtenBatches.isEmpty();
        if (exhausted && !sendBuffersExhausted) {
            stats.sendBuffersExhausted();
        }
        sendBuffersExhausted = exhausted;
        return paused || exhausted;
    }

    /**
     * Starts the snapshot of the first session the follower asked one for, when none is in progress. Its cut is the
     * reader's position: the session's events after it are skipped until the snapshot ends.
     */
    void startPendingSnapshot() {
        if (snapshot != null || !snapshotRequested && (storeRetryNanos == Long.MAX_VALUE || System.nanoTime() < storeRetryNanos)) {
            return;
        }
        snapshotRequested = false;
        storeRetryNanos = Long.MAX_VALUE;
        for (int key = 0; key < announced && state == State.STREAMING; key++) {
            // marked sent before it goes: the follower's REPLICATING may come back before the snapshot's last frame
            // is handed over
            if (!sessionSync(key, SessionSync.SNAPSHOT_PENDING, SessionSync.SNAPSHOT_SENT)) {
                continue;
            }
            FixSessionMessagesStore store = sessions.get(key).getConfig().getStore();
            if (store.isStarted()) {
                snapshot = new SnapshotCursor(key, store, reader.getOffset());
                // others may be pending, started once this one ends
                snapshotRequested = true;
                return;
            }
            // a session registered but not yet running, as on taking leadership: retried without holding the stream
            storeRetryNanos = System.nanoTime() + STORE_RETRY_NANOS;
            sessionSync(key, SessionSync.SNAPSHOT_SENT, SessionSync.SNAPSHOT_PENDING);
        }
    }

    boolean isSnapshotInProgress() {
        return snapshot != null;
    }

    /**
     * Sends the next chunk of the snapshot in progress: its next messages while a batch holds them, the whole round when
     * the snapshot is not catching up, and its end once a round fitted in one chunk.
     */
    void sendSnapshotChunk() {
        SnapshotCursor cursor = snapshot;
        ReplicatedSession session = sessions.get(cursor.sessionKey);
        SnapshotWriter writer = new SnapshotWriter(cursor, epoch.getAsLong());
        try {
            boolean roundStarted = cursor.next > cursor.roundEnd;
            if (roundStarted || cursor.resetAhead) {
                cursor.nextIncomingSeqNum = cursor.store.getIncomingSeqNum();
                cursor.nextOutgoingSeqNum = cursor.store.getOutgoingSeqNum();
            }
            if (cursor.next == 0) {
                writer.begin();
                cursor.next = 1;
            }
            if (cursor.resetAhead) {
                end(writer, session);
                return;
            }
            if (roundStarted) {
                cursor.startRound();
            }
            if (cursor.next <= cursor.roundEnd) {
                cursor.store.find(cursor.next, cursor.roundEnd, writer);
            }
            if (state != State.STREAMING) {
                return;
            }
            if (!writer.stopped) {
                cursor.next = cursor.roundEnd + 1;
                if (roundStarted) {
                    end(writer, session);
                    return;
                }
            }
            writer.flush();
        } catch (RuntimeException e) {
            log.warn("Snapshot of FIX session {} {} failed", session.getFixSessionType(), session.getFixSessionId(), e);
            snapshot = null;
            sessionSync(cursor.sessionKey, SessionSync.SNAPSHOT_SENT, SessionSync.SNAPSHOT_PENDING);
            close(CloseReason.SNAPSHOT_FAILED, "FIX session " + session.getFixSessionId() + ": " + e.getMessage());
        }
    }

    private void end(SnapshotWriter writer, ReplicatedSession session) {
        SnapshotCursor cursor = writer.cursor;
        writer.end();
        snapshot = null;
        stats.snapshotSent(cursor.messageCount);
        log.info("Snapshot of FIX session {} {} sent to follower {}: {} messages", session.getFixSessionType(),
                session.getFixSessionId(), followerId, cursor.messageCount);
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
            closed();
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
        if (reason == CloseReason.SHUTDOWN || reason == CloseReason.RETRY_LATER) {
            log.info("Closing the replication stream to follower {} link {}: {} {}", followerId, link, reason, text);
        } else {
            log.warn("Closing the replication stream to follower {} link {}: {} {}", followerId, link, reason, text);
        }
        closed();
        if (reason == CloseReason.TOO_SLOW) {
            stats.droppedTooSlow(followerId, link);
        }
        // not borrowed: a close comes from the sender as well as the IO thread, and the pool takes one borrower
        ByteBuffer frame = ByteBuffer.allocate(FrameEncoder.bytesWith(FrameType.CLOSE, text));
        int length = FrameEncoder.close(frame, 0, epoch.getAsLong(), reason, text);
        connection.send(written(frame, length), false);
        connection.stop(Deadline.of(CLOSE_FLUSH));
    }

    /**
     * The connection is gone: the send loop ends at its next turn.
     */
    void disconnected() {
        closed();
    }

    private void droppedAsTooSlow() {
        if (state == State.STREAMING) {
            close(CloseReason.TOO_SLOW, "a write waited the log full timeout for it");
        }
    }

    private void closed() {
        state = State.CLOSED;
        reader.close();
    }

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

    /**
     * Where the snapshot of a session stands, across the chunks it is sent in.
     */
    private static final class SnapshotCursor {

        private final int sessionKey;
        private final FixSessionMessagesStore store;
        private final long cutOffset;
        /**
         * The next seqnum to copy, 0 before the first chunk.
         */
        private long next;
        private long roundEnd = -1;
        private long previousRoundLength = Long.MAX_VALUE;
        /**
         * The round is sent in one chunk, whatever its size.
         */
        private boolean whole;
        private long nextIncomingSeqNum;
        private long nextOutgoingSeqNum;
        private int messageCount;
        /**
         * The stream holds a reset of the session, sent after the snapshot's end.
         */
        private boolean resetAhead;

        SnapshotCursor(int sessionKey, FixSessionMessagesStore store, long cutOffset) {
            this.sessionKey = sessionKey;
            this.store = store;
            this.cutOffset = cutOffset;
        }

        void startRound() {
            roundEnd = nextOutgoingSeqNum - 1;
            long length = roundEnd - next + 1;
            whole = length >= previousRoundLength;
            previousRoundLength = length;
        }
    }

    /**
     * Writes a chunk's frames into batch buffers, stopping before a message the batch cannot hold unless the round
     * goes whole. A message longer than a batch goes anyway, over as many sends as it needs.
     */
    private final class SnapshotWriter implements StoreMessagesConsumer {

        private final SnapshotCursor cursor;
        private final long leaderEpoch;
        private @Nullable ByteBuffer batch;
        private int filled;
        private boolean stopped;

        SnapshotWriter(SnapshotCursor cursor, long leaderEpoch) {
            this.cursor = cursor;
            this.leaderEpoch = leaderEpoch;
        }

        void begin() {
            room(FrameEncoder.fixedBytes(FrameType.SNAPSHOT_BEGIN));
            filled += FrameEncoder.snapshotBegin(batch, filled, leaderEpoch, cursor.sessionKey, cursor.cutOffset,
                    cursor.nextIncomingSeqNum, cursor.nextOutgoingSeqNum);
        }

        /**
         * A message too long for a frame goes in fragments, each no longer than the follower accepts: the longest event
         * frame, which a batch, rounded up for betty's pools, exceeds.
         */
        @Override
        public boolean onMessage(long seqNum, ByteBuffer message) {
            int messageLength = message.remaining();
            int maxFragmentLength = maxEventFrameBytes - FrameEncoder.snapshotMessageBytes(0);
            int fragments = Math.max(1, (messageLength + maxFragmentLength - 1) / maxFragmentLength);
            if (!cursor.whole && filled > 0 && snapshotChunkBytes - filled < fragments * FrameEncoder.snapshotMessageBytes(0) + messageLength) {
                cursor.next = seqNum;
                stopped = true;
                return false;
            }
            int from = 0;
            do {
                int length = Math.min(maxFragmentLength, messageLength - from);
                int flags = (from + length < messageLength ? WireFormat.MORE_FRAGMENTS : 0) | (from > 0 ? WireFormat.CONTINUED_FRAGMENT : 0);
                room(FrameEncoder.snapshotMessageBytes(length));
                filled += FrameEncoder.snapshotMessage(batch, filled, leaderEpoch, cursor.sessionKey, seqNum, message,
                        message.position() + from, length, (byte) flags);
                from += length;
            } while (from < messageLength && state == State.STREAMING);
            cursor.messageCount++;
            cursor.next = seqNum + 1;
            return state == State.STREAMING;
        }

        void end() {
            room(FrameEncoder.fixedBytes(FrameType.SNAPSHOT_END));
            filled += FrameEncoder.snapshotEnd(batch, filled, leaderEpoch, cursor.sessionKey, cursor.messageCount,
                    cursor.nextIncomingSeqNum, cursor.nextOutgoingSeqNum);
            flush();
        }

        void flush() {
            if (batch != null) {
                sendBatch(batch, filled);
                batch = null;
            }
        }

        private void room(int bytes) {
            if (batch != null && batchBytes - filled >= bytes) {
                return;
            }
            flush();
            awaitRoomToSend();
            batch = takeBatch();
            filled = 0;
        }

        private void awaitRoomToSend() {
            while (hasNoRoomToSend() && state == State.STREAMING) {
                sendBufferWait.idle();
            }
            sendBufferWait.reset();
        }
    }
}
