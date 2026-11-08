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
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.monitoring.Timer;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.ReplicatedSession;
import org.lolaf.staffix.ha.ReplicatedSessions;
import org.lolaf.staffix.ha.SessionSettingsDigests;
import org.lolaf.staffix.ha.log.ReplicationLog;
import org.lolaf.staffix.ha.log.ReplicationLogs;
import org.lolaf.staffix.ha.wire.*;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One follower's connection for one lane: the handshake, the frames it sends back, and the thread streaming the log
 * to it.
 *
 * <p>Frames from the follower arrive on the connection's IO thread; the log is streamed from a thread of the connection's
 * own, which reads as many events as fit straight into a batch behind room for their headers, sends it, then a
 * {@link SnapshotSender snapshot} chunk if one is in progress. While streaming, its reader holds appends back rather
 * than be lapped; a writer that waited the log full timeout for it drops it, and it reconnects to be caught up.
 */
@Slf4j
public final class FollowerConnection implements FrameHandler {

    private static final Duration CLOSE_FLUSH = Duration.ofSeconds(1);
    private final IOSession ioSession;
    private final String address;
    private final ReplicationLogs logs;
    /**
     * The log of the lane the HELLO named, null before it.
     */
    private volatile @Nullable ReplicationLog replicationLog;
    /**
     * Opened by the HELLO, holding the lane's appends back until the connection closes; null before it.
     */
    private volatile ReplicationLog.@Nullable Reader reader;
    private final ReplicatedSessions sessions;
    /**
     * This node's current election epoch, stamped on every frame it sends; read once per batch, so a batch carries one.
     */
    private final LongSupplier nodeEpoch;
    private final String leaderId;
    private final long heartbeatIntervalNanos;
    private final long stallTimeoutNanos;
    private final int maxEventFrameBytes;
    @Getter
    private volatile int lane;
    private final IdleStrategy senderIdle;
    private final ReplicationStats stats;
    private final SessionSyncs syncs = new SessionSyncs();
    private final BatchBuffers batches;
    private final SnapshotSender snapshots;
    /**
     * Keys of sessions already announced whose replication mode changed since.
     */
    private final Queue<Integer> reannouncements = new ConcurrentLinkedQueue<>();
    private final Consumer<Runnable> senderLauncher;
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
     * The offset of the last event the follower holds, written to its store or not yet, -1 before it said.
     */
    @Getter
    private volatile long receivedOffset = -1;
    /**
     * What the follower said of itself in its last ACK; {@link #isSessionInSync(int)} relies on the leader's own account.
     */
    @Getter
    private volatile boolean reportedInSync;
    private volatile long lastAckNanos;
    /**
     * The offset a {@code SYNC} send gave up waiting for, -1 when none: the follower is not waited for again until it
     * applies it, so a hung follower costs one timeout rather than one per send.
     */
    private volatile long overdueOffset = -1;
    /**
     * What the follower's stores for this lane have yet to store, as of its last ack.
     */
    @Getter
    private volatile long followerPendingWrites;
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
    /**
     * The next session key to announce, every key below it announced; sender thread only.
     */
    private int nextToAnnounce;
    /**
     * The last generation of session settings digests sent whole, and how far the one being sent has gone; sender thread only.
     */
    private int sentDigestsGeneration;
    private int sendingDigestsGeneration;
    private int nextDigest;
    private long lastSendNanos;
    private long lastSentEventOffset = -1;
    /**
     * When the follower last went from owing nothing to owing an ack, sender thread only.
     */
    private long owedSinceNanos;
    /**
     * The offset of the last event the last {@link #fillBatch} took, -1 when it took none.
     */
    private long filledEventOffset = -1;
    private AckLatencySampler ackLatency = new AckLatencySampler(Timer.VoidTimer.getInstance());
    private LagSampler lag = new LagSampler(Timer.VoidTimer.getInstance());

    /**
     * @param sendBuffers        the batch buffers the connection allocates at most, a power of two; the one written
     *                           last is reused only once the next is written
     * @param snapshotChunkBytes the most bytes of a snapshot sent between two live batches, at most a batch
     * @param senderLauncher     starts the loop streaming the log, on a thread of its own
     */
    FollowerConnection(IOSession ioSession, ReplicationLogs logs, ReplicatedSessions sessions, LongSupplier nodeEpoch,
                       String leaderId, Duration heartbeatInterval, Duration stallTimeout, int sendBuffers,
                       int snapshotChunkBytes, IdleStrategy senderIdle, ReplicationStats stats,
                       Consumer<Runnable> senderLauncher) {
        this.ioSession = ioSession;
        this.address = String.valueOf(ioSession.getSocketAddress());
        this.logs = logs;
        this.sessions = sessions;
        this.nodeEpoch = nodeEpoch;
        this.leaderId = leaderId;
        this.heartbeatIntervalNanos = heartbeatInterval.toNanos();
        this.stallTimeoutNanos = stallTimeout.toNanos();
        this.maxEventFrameBytes = logs.getMaxEventFrameBytes();
        this.senderIdle = senderIdle;
        this.stats = stats;
        this.senderLauncher = senderLauncher;
        this.batches = new BatchBuffers(ioSession, sendBuffers, WireFormat.bufferBytes(maxEventFrameBytes), stats);
        this.snapshots = new SnapshotSender(this, sessions, syncs, batches, stats, nodeEpoch, maxEventFrameBytes,
                snapshotChunkBytes);
    }

    @Override
    public void onHello(long epoch, int version, String nodeId, long lastAppliedEpoch, long lastAppliedOffset, int lane) {
        followerId = nodeId;
        long currentEpoch = nodeEpoch.getAsLong();
        if (state != State.AWAITING_HELLO) {
            close(CloseReason.PROTOCOL_ERROR, "a second HELLO");
            return;
        }
        if (version != WireFormat.VERSION) {
            close(CloseReason.VERSION_MISMATCH, "leader speaks version " + WireFormat.VERSION + ", follower " + version);
            return;
        }
        if (epoch > currentEpoch) {
            close(CloseReason.STALE_EPOCH, "follower is at epoch " + epoch + ", this leader at " + currentEpoch);
            return;
        }
        if (lane >= logs.count()) {
            close(CloseReason.NO_SUCH_LANE, "lane " + lane + " of " + logs.count());
            return;
        }
        this.lane = lane;
        replicationLog = logs.get(lane);
        Optional<Instant> heldOffUntil = stats.getSlowFollowers().heldOffUntil(nodeId, lane);
        if (heldOffUntil.isPresent()) {
            close(CloseReason.RETRY_LATER, "dropped as too slow, held off until " + heldOffUntil.get());
            return;
        }
        reader = replicationLog.newReader(this::droppedAsTooSlow);
        recoveringFromTooSlow = stats.getSlowFollowers().isNotBackInSync(nodeId, lane);
        boolean resumed = lastAppliedEpoch == currentEpoch && resume(lastAppliedOffset);
        if (!resumed) {
            reader.seekToTail();
        }
        appliedOffset = lastAppliedOffset;
        receivedOffset = lastAppliedOffset;
        ackLatency = new AckLatencySampler(stats.ackLatency(nodeId, lane));
        lag = new LagSampler(stats.lag(nodeId, lane));
        state = State.STREAMING;
        log.info("Follower {} connected lane {} of {} at epoch {}, {}", nodeId, lane, logs.count(), currentEpoch,
                resumed ? "resuming after offset " + lastAppliedOffset : "needing a snapshot");
        ByteBuffer welcome = ioSession.borrow(FrameEncoder.bytesWith(FrameType.WELCOME, leaderId));
        int length = FrameEncoder.welcome(welcome.clear(), 0, currentEpoch, leaderId, resumed ? reader.getOffset() : WireFormat.NO_RESUME, logs.count());
        ioSession.send(BatchBuffers.written(welcome, length), true);
        senderLauncher.accept(this::runSender);
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
    public void onReceived(long epoch, long receivedOffset) {
        this.receivedOffset = receivedOffset;
    }

    void wakeSender() {
        senderIdle.wakeup();
    }

    @Override
    public void onAck(long epoch, long appliedOffset, boolean inSync, long pendingWrites) {
        this.appliedOffset = appliedOffset;
        this.followerPendingWrites = pendingWrites;
        this.reportedInSync = inSync;
        if (inSync && recoveringFromTooSlow) {
            recoveringFromTooSlow = false;
            stats.getSlowFollowers().backInSync(followerId, lane);
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
    public boolean isSessionInSync(int sessionKey) {
        return state == State.STREAMING && syncs.get(sessionKey) == SessionSyncState.IN_SYNC;
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

    public @Nullable SessionSyncState getSyncState(int sessionKey) {
        return syncs.get(sessionKey);
    }

    @Override
    public void onSessionAnswer(long epoch, int sessionKey, SessionAnswer answer) {
        if (syncs.get(sessionKey) == null) {
            close(CloseReason.PROTOCOL_ERROR, "a SESSION_ANSWER for session key " + sessionKey + ", never announced");
            return;
        }
        syncs.answered(sessionKey, answer);
        switch (answer) {
            case REPLICATING:
                syncs.set(sessionKey, SessionSyncState.IN_SYNC);
                break;
            case SNAPSHOT_NEEDED:
                syncs.set(sessionKey, SessionSyncState.SNAPSHOT_PENDING);
                snapshots.request();
                break;
            case NOT_REPLICATED:
                syncs.set(sessionKey, SessionSyncState.NOT_REPLICATED);
                break;
            default:
                syncs.set(sessionKey, SessionSyncState.NOT_REPLICATED);
                ReplicatedSession session = sessions.get(sessionKey);
                log.warn("Follower {} does not replicate FIX session {} {}: {}", followerId, session.getFixSessionType(),
                        session.getFixSessionId(), answer);
        }
    }

    /**
     * Sends the session again by snapshot, which resets the follower's copy first. Only a copy the follower holds is
     * replaced: a session it refuses or is still catching up on is left as it is.
     */
    void resync(int sessionKey) {
        if (carries(sessionKey) && syncs.getAnswer(sessionKey) == SessionAnswer.REPLICATING
                && syncs.compareAndSet(sessionKey, SessionSyncState.IN_SYNC, SessionSyncState.SNAPSHOT_PENDING)) {
            snapshots.request();
        }
    }

    public @Nullable SessionAnswer getSessionAnswer(int sessionKey) {
        return syncs.getAnswer(sessionKey);
    }

    /**
     * @param sessionCount the sessions the leader replicates, every one of which the follower must hold to be in sync
     */
    public HaStatus.FollowerConnection getStatus(int sessionCount) {
        return HaStatus.FollowerConnection.builder()
                .nodeId(followerId)
                .lane(lane)
                .address(address)
                .connected(isConnected())
                .inSync(isLaneInSync(sessionCount))
                .snapshotInProgress(syncs.isSnapshotInProgress(sessionCount))
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
    public boolean isLaneInSync(int sessionCount) {
        for (int key = 0; key < sessionCount; key++) {
            if (carries(key) && !isSessionInSync(key) && sessions.get(key).isReplicated()) {
                return false;
            }
        }
        return isConnected();
    }

    /**
     * Whether the session is replicated over this lane, rather than another of the follower's.
     */
    public boolean carries(int sessionKey) {
        return logs.laneOf(sessionKey) == lane;
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

    /**
     * Fills {@code dst} from index 0, up to its limit, with what the follower has not received: the sessions it does
     * not know yet, then events until the next one does not fit. An event of a session registered after this fill
     * began is left for the next one, behind that session's announcement.
     *
     * @return the bytes filled
     */
    int fillBatch(ByteBuffer dst) {
        lag.onFill(replicationLog.getTailOffset(), reader.getOffset(), reader.getEventOffset(), appliedOffset);
        int filled = announceSessions(dst);
        filled += fillSessionSettingsDigests(dst, filled);
        long currentEpoch = nodeEpoch.getAsLong();
        filledEventOffset = -1;
        while (state == State.STREAMING && dst.limit() - filled >= WireFormat.STORE_EVENT_BODY_OFFSET) {
            int body = reader.readInto(dst, filled + WireFormat.STORE_EVENT_BODY_OFFSET);
            if (body < 0) {
                break;
            }
            int sessionKey = dst.getInt(filled + WireFormat.STORE_EVENT_BODY_OFFSET);
            if (sessionKey >= nextToAnnounce) {
                reader.unread();
                break;
            }
            if (snapshots.isOf(sessionKey)) {
                if (dst.get(filled + WireFormat.STORE_EVENT_BODY_OFFSET + Integer.BYTES) != StoreEventType.RESET_SEQ_NUM.getCode()) {
                    continue;
                }
                // left for the stream, after the snapshot's end
                snapshots.resetAhead();
                reader.unread();
                break;
            }
            filledEventOffset = reader.getEventOffset();
            lag.onEventRead(filledEventOffset, reader.getOffset());
            filled += FrameEncoder.storeEventHeader(dst, filled, currentEpoch, filledEventOffset, body);
        }
        sentOffset = reader.getOffset();
        return filled;
    }

    private int announceSessions(ByteBuffer dst) {
        int filled = 0;
        long currentEpoch = nodeEpoch.getAsLong();
        while (nextToAnnounce < sessions.count()) {
            if (carries(nextToAnnounce)) {
                int bytes = sessionFrame(dst, filled, currentEpoch, sessions.get(nextToAnnounce));
                if (bytes == 0) {
                    return filled;
                }
                filled += bytes;
            }
            nextToAnnounce++;
        }
        Integer key;
        while ((key = reannouncements.peek()) != null) {
            // a key not announced yet goes out with its current mode once it is
            if (key < nextToAnnounce && carries(key)) {
                int bytes = sessionFrame(dst, filled, currentEpoch, sessions.get(key));
                if (bytes == 0) {
                    return filled;
                }
                filled += bytes;
                if (snapshots.isOf(key)) {
                    snapshots.cancel();
                }
            }
            reannouncements.poll();
        }
        return filled;
    }

    /**
     * On lane 0 only, as they concern the node and not a lane's sessions. Resumes on the next fill when they do not all fit.
     */
    private int fillSessionSettingsDigests(ByteBuffer dst, int index) {
        SessionSettingsDigests.Published published = sessions.getSettingsDigests().getPublished();
        if (lane != 0 || published.getGeneration() == sentDigestsGeneration) {
            return 0;
        }
        if (published.getGeneration() != sendingDigestsGeneration) {
            sendingDigestsGeneration = published.getGeneration();
            nextDigest = 0;
        }
        long currentEpoch = nodeEpoch.getAsLong();
        int filled = index;
        List<SessionSettingsDigests.Digest> digests = published.getDigests();
        for (; nextDigest < digests.size(); nextDigest++) {
            SessionSettingsDigests.Digest digest = digests.get(nextDigest);
            if (dst.limit() - filled < FrameEncoder.bytesWith(FrameType.SESSION_SETTINGS_DIGEST, digest.getStore(), digest.getFixSessionId(), digest.getValue())) {
                return filled - index;
            }
            filled += FrameEncoder.sessionSettingsDigest(dst, filled, currentEpoch, sendingDigestsGeneration, digest);
        }
        if (dst.limit() - filled < FrameEncoder.fixedBytes(FrameType.SESSION_SETTINGS_DIGESTS_COMPLETE)) {
            return filled - index;
        }
        filled += FrameEncoder.sessionSettingsDigestsComplete(dst, filled, currentEpoch, sendingDigestsGeneration, digests.size());
        sentDigestsGeneration = sendingDigestsGeneration;
        return filled - index;
    }

    /**
     * @return the bytes written, 0 when the frame does not fit
     */
    private int sessionFrame(ByteBuffer dst, int index, long currentEpoch, ReplicatedSession session) {
        String id = session.getFixSessionId().toString();
        ReplicatedSession.Config config = session.getConfig();
        if (dst.limit() - index < FrameEncoder.bytesWith(FrameType.SESSION, id, config.getStorageIdentity())) {
            return 0;
        }
        syncs.set(session.getKey(), SessionSyncState.ANNOUNCED);
        return FrameEncoder.session(dst, index, currentEpoch, session.getKey(), session.getFixSessionType(),
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
        return FrameEncoder.heartbeat(dst, 0, nodeEpoch.getAsLong(), replicationLog.getTailOffset());
    }

    void onWriteWatermark(boolean reached) {
        batches.onWriteWatermark(reached);
    }

    void startPendingSnapshot() {
        snapshots.startPending(reader.getOffset(), nextToAnnounce);
    }

    boolean isSnapshotInProgress() {
        return snapshots.isInProgress();
    }

    void sendSnapshotChunk() {
        snapshots.sendChunk();
    }

    private void runSender() {
        senderRunning = true;
        try {
            sendLoop();
        } finally {
            senderRunning = false;
        }
    }

    private void sendLoop() {
        senderIdle.reset();
        lastSendNanos = System.nanoTime();
        lastAckNanos = lastSendNanos;
        while (state != State.CLOSED && !handingOver) {
            if (isStalled(System.nanoTime())) {
                close(CloseReason.STALLED, "no ack for " + TimeUnit.NANOSECONDS.toMillis(stallTimeoutNanos)
                        + " ms with writes unapplied");
                break;
            }
            if (batches.hasNoRoom()) {
                if (snapshots.isInProgress() || reader.getOffset() != replicationLog.getTailOffset()) {
                    batches.countSendWait();
                }
                senderIdle.idle();
                continue;
            }
            startPendingSnapshot();
            boolean sent = sendLiveBatch();
            if (snapshots.isInProgress() && state == State.STREAMING) {
                snapshots.sendChunk();
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

    private boolean sendLiveBatch() {
        ByteBuffer batch = batches.take();
        int filled = fillBatch(batch);
        if (filled == 0 && state == State.STREAMING) {
            filled = heartbeatIfDue(batch, System.nanoTime());
        }
        if (filled == 0 || state != State.STREAMING) {
            batches.keep(batch);
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
        batches.send(batch, filled);
        return true;
    }

    /**
     * Owing an ack and silent for the stall timeout: a slow follower still acks each read, a hung one does not.
     */
    private boolean isStalled(long nowNanos) {
        return stallTimeoutNanos > 0 && lastSentEventOffset > appliedOffset
                && nowNanos - Math.max(owedSinceNanos, lastAckNanos) > stallTimeoutNanos;
    }

    /**
     * For a leader stopping cleanly, its sessions already stopped: waits for every event of the log to be sent, then
     * closes with {@link CloseReason#SHUTDOWN}. Arriving after them on the same connection, it tells the follower it
     * holds everything, so the follower takes over without moving its seqnums ahead. When the deadline passes first,
     * the connection is dropped without it.
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
            markClosed();
            ioSession.stop(Deadline.immediate());
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
            log.info("Closing the replication stream to follower {} lane {}: {} {}", followerId, lane, reason, text);
        } else {
            log.warn("Closing the replication stream to follower {} lane {}: {} {}", followerId, lane, reason, text);
        }
        markClosed();
        if (reason == CloseReason.TOO_SLOW) {
            stats.droppedTooSlow(followerId, lane);
        }
        // not borrowed: a close comes from the sender as well as the IO thread, and the pool takes one borrower
        ByteBuffer frame = ByteBuffer.allocate(FrameEncoder.bytesWith(FrameType.CLOSE, text));
        int length = FrameEncoder.close(frame, 0, nodeEpoch.getAsLong(), reason, text);
        ioSession.send(BatchBuffers.written(frame, length), false);
        ioSession.stop(Deadline.of(CLOSE_FLUSH));
    }

    /**
     * The connection is gone: the send loop ends at its next turn.
     */
    void disconnected() {
        markClosed();
    }

    private void droppedAsTooSlow() {
        if (state == State.STREAMING) {
            close(CloseReason.TOO_SLOW, "a write waited the log full timeout for it");
        }
    }

    private void markClosed() {
        state = State.CLOSED;
        ReplicationLog.Reader opened = reader;
        if (opened != null) {
            opened.close();
        }
    }

    public enum State {
        AWAITING_HELLO,
        STREAMING,
        CLOSED
    }
}
