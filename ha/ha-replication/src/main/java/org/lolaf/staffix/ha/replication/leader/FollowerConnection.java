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
package org.lolaf.staffix.ha.replication.leader;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import org.lolaf.staffix.ha.replication.encryption.ConnectionEncryptionKeys;
import org.lolaf.staffix.ha.replication.encryption.KeyExchange;
import org.lolaf.staffix.ha.replication.encryption.Negotiation;
import org.lolaf.staffix.ha.replication.log.ReplicationLog;
import org.lolaf.staffix.ha.replication.log.ReplicationLogs;
import org.lolaf.staffix.ha.replication.spi.LeaderStream;
import org.lolaf.staffix.ha.replication.spi.LeaderStreams;
import org.lolaf.staffix.ha.replication.spi.LatencyRecorder;
import org.lolaf.staffix.ha.replication.spi.RecordFlag;
import org.lolaf.staffix.ha.replication.spi.ReplicationClock;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;
import org.lolaf.staffix.ha.replication.wire.*;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One follower's connection for one lane: the handshake, the frames it sends back, and the thread streaming the log
 * to it.
 *
 * <p>Frames from the follower arrive on the connection's IO thread; the log is streamed from a thread of the connection's
 * own, which reads as many records as fit straight into a batch behind room for their headers, sends it, then a
 * {@link SnapshotSender snapshot} chunk if one is in progress. While streaming, its reader holds appends back rather
 * than be lapped; a writer that waited the log full timeout for it drops it, and it reconnects to be caught up.
 */
@Slf4j
public final class FollowerConnection implements FrameHandler {

    private static final Duration CLOSE_FLUSH = Duration.ofSeconds(1);
    private static final AtomicReferenceFieldUpdater<FollowerConnection, State> STATE =
            AtomicReferenceFieldUpdater.newUpdater(FollowerConnection.class, State.class, "state");
    private static final AtomicReferenceFieldUpdater<FollowerConnection, PendingClose> PENDING_CLOSE =
            AtomicReferenceFieldUpdater.newUpdater(FollowerConnection.class, PendingClose.class, "pendingClose");
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
    private final LeaderStreams streams;
    /**
     * This node's current election epoch, stamped on every frame it sends; read once per batch, so a batch carries one.
     */
    private final LongSupplier nodeEpoch;
    private final String leaderId;
    private final long heartbeatIntervalNanos;
    private final long stallTimeoutNanos;
    private final int maxRecordFrameBytes;
    @Getter
    private volatile int lane;
    private final IdleStrategy senderIdle;
    private final ReplicationStats stats;
    private final StreamSyncs syncs;
    private final BatchBuffers batches;
    private final SnapshotSender snapshots;
    /**
     * Keys of streams already announced whose replication mode changed since.
     */
    private final Queue<Integer> reannouncements;
    private final Consumer<Runnable> senderLauncher;
    private final ReplicationClock clock;
    private final KeyExchange keyExchange;
    /**
     * This connection's keys once both peers encrypt, null on a clear one; IO thread until the stream starts.
     */
    @Getter(AccessLevel.PACKAGE)
    private volatile @Nullable ConnectionEncryptionKeys encryptionKeys;
    /**
     * The HELLO and WELCOME of an encrypted connection, which its KEY_CONFIRMATION covers; IO thread only.
     */
    private byte @Nullable [] receivedHelloBytes;
    private byte @Nullable [] sentWelcomeBytes;
    /**
     * Seals every frame the sender writes; set before the sender starts, sender thread after.
     */
    @Getter(AccessLevel.PACKAGE)
    private FrameCipher frameCipher;
    /**
     * Opens the follower's frames once its KEY_CONFIRMATION verified; IO thread only.
     */
    @Getter(AccessLevel.PACKAGE)
    private FrameCipher answerOpener;
    @Getter
    private volatile State state;
    private volatile boolean followerHungUp;
    @Getter
    private volatile @Nullable String followerId;
    /**
     * The offset of the last record the follower applied, -1 before it said.
     */
    @Getter
    private volatile long appliedOffset;
    /**
     * The offset of the last record the follower holds, written by its sink or not yet, -1 before it said.
     */
    @Getter
    private volatile long receivedOffset;
    /**
     * What the follower said of itself in its last ACK; {@link #isStreamInSync(int)} relies on the leader's own account.
     */
    @Getter
    private volatile boolean reportedInSync;
    private volatile long lastAckNanos;
    /**
     * The offset a {@code SYNC} send gave up waiting for, -1 when none: the follower is not waited for again until it
     * applies it, so a hung follower costs one timeout rather than one per send.
     */
    private volatile long overdueOffset;
    /**
     * What the follower's sinks for this lane have yet to write, as of its last ack.
     */
    @Getter
    private volatile long followerPendingWrites;
    /**
     * Where the next batch starts reading the log: once it is the log's tail, every record has been handed to betty.
     */
    private volatile long sentOffset;
    /**
     * Dropped as too slow last time, until its first in-sync ack; IO thread only.
     */
    private boolean recoveringFromTooSlow;
    private volatile boolean handingOver;
    private volatile boolean senderRunning;
    /**
     * The sender sealed a frame, and every frame it seals is sent: the follower then opens every later one, the CLOSE
     * included.
     */
    private volatile boolean streamStarted;
    /**
     * Recorded by the first close; the IO thread sends it once the sender stopped, sealed after the sender's last frame.
     */
    private volatile @Nullable PendingClose pendingClose;
    private final AtomicBoolean closeSent;
    /**
     * The next stream key to announce, every key below it announced; sender thread only.
     */
    private int nextToAnnounce;
    /**
     * The STREAM frames of the connection's first announcement pass, and whether its STREAMS_ANNOUNCED went out; sender
     * thread only.
     */
    private int firstPassAnnounced;
    private boolean streamsAnnounced;
    private long lastSendNanos;
    private long lastSentRecordOffset;
    /**
     * When the follower last went from owing nothing to owing an ack, sender thread only.
     */
    private long owedSinceNanos;
    /**
     * The offset of the last record the last {@link #fillBatch} took, -1 when it took none.
     */
    private long filledRecordOffset;
    private AckLatencySampler ackLatency;
    private LagSampler lag;

    /**
     * @param sendBuffers        the batch buffers the connection allocates at most, a power of two; the one written
     *                           last is reused only once the next is written
     * @param snapshotChunkBytes the most bytes of a snapshot sent between two live batches, at most a batch
     * @param senderLauncher     starts the loop streaming the log, on a thread of its own
     * @param clock              what the stall timeout and the heartbeat interval are measured on
     * @param stallTimeout       also how long a follower may take to send its KEY_CONFIRMATION; zero waits for ever
     */
    FollowerConnection(IOSession ioSession, ReplicationLogs logs, LeaderStreams streams, LongSupplier nodeEpoch,
                       String leaderId, Duration heartbeatInterval, Duration stallTimeout, int sendBuffers,
                       int snapshotChunkBytes, IdleStrategy senderIdle, ReplicationStats stats,
                       Consumer<Runnable> senderLauncher, ReplicationClock clock, KeyExchange keyExchange) {
        this.syncs = new StreamSyncs();
        this.reannouncements = new ConcurrentLinkedQueue<>();
        this.frameCipher = FrameCipher.CLEAR;
        this.answerOpener = FrameCipher.CLEAR;
        this.closeSent = new AtomicBoolean();
        this.state = State.AWAITING_HELLO;
        this.appliedOffset = -1;
        this.receivedOffset = -1;
        this.overdueOffset = -1;
        this.sentOffset = -1;
        this.lastSentRecordOffset = -1;
        this.filledRecordOffset = -1;
        this.ackLatency = new AckLatencySampler(LatencyRecorder.NONE);
        this.lag = new LagSampler(LatencyRecorder.NONE);
        this.ioSession = ioSession;
        this.address = String.valueOf(ioSession.getSocketAddress());
        this.logs = logs;
        this.streams = streams;
        this.nodeEpoch = nodeEpoch;
        this.leaderId = leaderId;
        this.heartbeatIntervalNanos = heartbeatInterval.toNanos();
        this.stallTimeoutNanos = stallTimeout.toNanos();
        this.maxRecordFrameBytes = logs.getMaxRecordFrameBytes();
        this.senderIdle = senderIdle;
        this.stats = stats;
        this.senderLauncher = senderLauncher;
        this.clock = clock;
        this.keyExchange = keyExchange;
        this.batches = new BatchBuffers(ioSession, sendBuffers,
                WireFormat.bufferBytes(maxRecordFrameBytes + WireFormat.TAG_BYTES), stats);
        this.snapshots = new SnapshotSender(this, streams, syncs, batches, stats, nodeEpoch, maxRecordFrameBytes,
                snapshotChunkBytes);
    }

    @Override
    public void onHello(long epoch, int version, int applicationId, String nodeId, long lastAppliedEpoch,
                        long lastAppliedOffset, int lane, EncryptionMode followerMode, byte[] followerNonce) {
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
        if (applicationId != streams.getApplicationId()) {
            close(CloseReason.APPLICATION_MISMATCH, "leader replicates application " + Integer.toHexString(streams.getApplicationId())
                    + ", follower " + Integer.toHexString(applicationId));
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
        Negotiation negotiation = keyExchange.negotiate(followerMode);
        if (negotiation == Negotiation.REFUSED) {
            close(CloseReason.ENCRYPTION_REQUIRED, "this leader's encryption is " + keyExchange.getMode()
                    + ", the follower's " + followerMode);
            return;
        }
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
        boolean encrypted = negotiation == Negotiation.ENCRYPTED;
        // a writer may have dropped the reader already
        if (!STATE.compareAndSet(this, State.AWAITING_HELLO, encrypted ? State.AWAITING_KEY_CONFIRMATION : State.STREAMING)) {
            if (state == State.DROPPED_AS_TOO_SLOW) {
                closeDropped();
            }
            return;
        }
        log.info("Follower {} connected lane {} of {} at epoch {}, {}", nodeId, lane, logs.count(), currentEpoch,
                resumed ? "resuming after offset " + lastAppliedOffset : "needing a snapshot");
        if (negotiation == Negotiation.CLEAR_AGAINST_PREFERENCE) {
            log.error("Replicating in clear to follower {} lane {}: this leader prefers encryption, the follower has it "
                    + "disabled", nodeId, lane);
        }
        byte[] leaderNonce = keyExchange.newNonce();
        long resumeOffset = resumed ? reader.getOffset() : WireFormat.NO_RESUME;
        ByteBuffer frame = ioSession.borrow(FrameEncoder.bytesWith(FrameType.WELCOME, leaderId));
        int length = FrameEncoder.welcome(frame.clear(), 0, currentEpoch, streams.getApplicationId(), leaderId,
                resumeOffset, logs.count(),
                keyExchange.getMode(), leaderNonce);
        if (encrypted) {
            receivedHelloBytes = FrameEncoder.helloBytes(epoch, applicationId, nodeId, lastAppliedEpoch, lastAppliedOffset, lane,
                    followerMode, followerNonce);
            sentWelcomeBytes = FrameDecoder.bytes(frame, 0, length);
            ConnectionEncryptionKeys keys = keyExchange.deriveKeys(followerNonce, leaderNonce);
            frameCipher = keyExchange.newSealer(keys.getLeaderToFollower());
            encryptionKeys = keys;
        }
        ioSession.send(BatchBuffers.written(frame, length), true);
        senderLauncher.accept(this::runSender);
    }

    @Override
    public void onKeyConfirmation(long epoch, byte[] confirmation) {
        ConnectionEncryptionKeys keys = encryptionKeys;
        if (state == State.DROPPED_AS_TOO_SLOW) {
            return;
        }
        if (state != State.AWAITING_KEY_CONFIRMATION || keys == null) {
            close(CloseReason.PROTOCOL_ERROR, "a KEY_CONFIRMATION on a connection not waiting for one");
            return;
        }
        if (!keyExchange.confirms(keys, receivedHelloBytes, sentWelcomeBytes, confirmation)) {
            close(CloseReason.KEY_MISMATCH, "the follower's secret or cipher differ from this leader's "
                    + keyExchange.describeCipher());
            return;
        }
        receivedHelloBytes = null;
        sentWelcomeBytes = null;
        answerOpener = keyExchange.newOpener(keys.getFollowerToLeader());
        if (STATE.compareAndSet(this, State.AWAITING_KEY_CONFIRMATION, State.STREAMING)) {
            log.info("Replicating encrypted to follower {} lane {}, {}", followerId, lane, keyExchange.describeCipher());
            senderIdle.wakeup();
        }
    }

    /**
     * Positions the reader after the follower's last applied record, if that record is still in the log.
     */
    private boolean resume(long lastAppliedOffset) {
        if (lastAppliedOffset < 0 || !reader.seek(lastAppliedOffset)) {
            return false;
        }
        ByteBuffer skipped = ByteBuffer.allocate(maxRecordFrameBytes);
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
        lastAckNanos = clock.nanoTime();
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
     * The follower holds everything of the stream up to its last ack, so a {@code SYNC} send of the stream waits
     * for its ack. Allocates nothing.
     */
    public boolean isStreamInSync(int streamKey) {
        return state == State.STREAMING && syncs.get(streamKey) == StreamSyncState.IN_SYNC;
    }

    boolean isAckOverdue() {
        return overdueOffset >= 0;
    }

    /**
     * A {@code SYNC} send stopped waiting for this follower to apply the record at {@code offset}.
     */
    void ackOverdue(long offset) {
        overdueOffset = offset;
        if (appliedOffset >= offset) {
            overdueOffset = -1;
        }
    }

    public @Nullable StreamSyncState getSyncState(int streamKey) {
        return syncs.get(streamKey);
    }

    @Override
    public void onStreamAnswer(long epoch, int streamKey, StreamAnswer answer) {
        if (syncs.get(streamKey) == null) {
            close(CloseReason.PROTOCOL_ERROR, "a STREAM_ANSWER for stream key " + streamKey + ", never announced");
            return;
        }
        syncs.answered(streamKey, answer);
        switch (answer) {
            case REPLICATING:
                syncs.set(streamKey, StreamSyncState.IN_SYNC);
                break;
            case SNAPSHOT_NEEDED:
                syncs.set(streamKey, StreamSyncState.SNAPSHOT_PENDING);
                snapshots.request();
                break;
            case NOT_REPLICATED:
                syncs.set(streamKey, StreamSyncState.NOT_REPLICATED);
                break;
            default:
                syncs.set(streamKey, StreamSyncState.NOT_REPLICATED);
                log.warn("Follower {} does not replicate stream {}: {}", followerId,
                        streams.get(streamKey).getStreamName(), answer);
        }
    }

    /**
     * Sends the stream again by snapshot, which resets the follower's copy first. Only a copy the follower holds is
     * replaced: a stream it refuses or is still catching up on is left as it is.
     */
    void resync(int streamKey) {
        if (carries(streamKey) && syncs.getAnswer(streamKey) == StreamAnswer.REPLICATING
                && syncs.compareAndSet(streamKey, StreamSyncState.IN_SYNC, StreamSyncState.SNAPSHOT_PENDING)) {
            snapshots.request();
        }
    }

    public @Nullable StreamAnswer getStreamAnswer(int streamKey) {
        return syncs.getAnswer(streamKey);
    }

    /**
     * @param streamCount the streams the leader replicates, every one of which the follower must hold to be in sync
     */
    public FollowerLaneStatus getStatus(int streamCount) {
        return FollowerLaneStatus.builder()
                .nodeId(followerId)
                .lane(lane)
                .address(address)
                .connected(isConnected())
                .inSync(isLaneInSync(streamCount))
                .snapshotInProgress(syncs.isSnapshotInProgress(streamCount))
                .unsentBytes(getUnsentBytes())
                .lagMillis(TimeUnit.NANOSECONDS.toMillis(getLagNanos(System.nanoTime())))
                .cipher(isEncrypted() ? keyExchange.describeCipher() : null)
                .build();
    }

    public boolean isConnected() {
        return state == State.STREAMING;
    }

    /**
     * Streaming encrypted, the follower having confirmed the keys.
     */
    public boolean isEncrypted() {
        return isConnected() && encryptionKeys != null;
    }

    /**
     * @param streamCount the streams the leader replicates, every one of which the follower must hold
     */
    public boolean isLaneInSync(int streamCount) {
        for (int key = 0; key < streamCount; key++) {
            if (carries(key) && !isStreamInSync(key) && streams.get(key).isReplicated()) {
                return false;
            }
        }
        return isConnected();
    }

    /**
     * Whether the stream is replicated over this lane, rather than another of the follower's.
     */
    public boolean carries(int streamKey) {
        return logs.laneOf(streamKey) == lane;
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
        markClosed();
    }

    /**
     * Fills {@code dst} from index 0, up to its limit, once streaming, with what the follower has not received: the
     * streams it does not know yet, then records until the next one does not fit. A record of a stream registered
     * after this fill began is left for the next one, behind that stream's announcement.
     *
     * @return the bytes filled
     */
    int fillBatch(ByteBuffer dst) {
        filledRecordOffset = -1;
        if (state != State.STREAMING) {
            return 0;
        }
        int limit = dst.limit();
        // every frame then fits with its tag after it
        dst.limit(limit - frameCipher.getTagBytes());
        try {
            int filled = fillBatchUpToLimit(dst);
            if (filled > 0) {
                streamStarted = true;
            }
            return filled;
        } finally {
            dst.limit(limit);
        }
    }

    private int fillBatchUpToLimit(ByteBuffer dst) {
        lag.onFill(replicationLog.getTailOffset(), reader.getOffset(), reader.getRecordOffset(), appliedOffset);
        int filled = announceStreams(dst);
        long currentEpoch = nodeEpoch.getAsLong();
        while (state == State.STREAMING && dst.limit() - filled >= WireFormat.RECORD_BODY_OFFSET) {
            int bodyIndex = filled + WireFormat.RECORD_BODY_OFFSET;
            int body = reader.readInto(dst, bodyIndex);
            if (body < 0) {
                break;
            }
            int streamKey = FrameDecoder.recordStreamKey(dst, bodyIndex);
            if (streamKey >= nextToAnnounce) {
                reader.unread();
                break;
            }
            if (snapshots.isOf(streamKey)) {
                if ((FrameDecoder.recordFlags(dst, bodyIndex) & RecordFlag.RESET.getBits()) == 0) {
                    continue;
                }
                // left for the stream, after the snapshot's end
                snapshots.resetAhead();
                reader.unread();
                break;
            }
            filledRecordOffset = reader.getRecordOffset();
            lag.onRecordRead(filledRecordOffset, reader.getOffset());
            int frameLength = FrameEncoder.recordHeader(dst, filled, currentEpoch, filledRecordOffset, body);
            filled += frameCipher.seal(dst, filled, frameLength);
        }
        sentOffset = reader.getOffset();
        return filled;
    }

    private int announceStreams(ByteBuffer dst) {
        int filled = 0;
        long currentEpoch = nodeEpoch.getAsLong();
        while (nextToAnnounce < streams.count()) {
            if (carries(nextToAnnounce)) {
                int bytes = streamFrame(dst, filled, currentEpoch, streams.get(nextToAnnounce));
                if (bytes == 0) {
                    return filled;
                }
                filled += bytes;
                if (!streamsAnnounced) {
                    firstPassAnnounced++;
                }
            }
            nextToAnnounce++;
        }
        if (!streamsAnnounced) {
            if (dst.limit() - filled < FrameEncoder.fixedBytes(FrameType.STREAMS_ANNOUNCED)) {
                return filled;
            }
            filled += frameCipher.seal(dst, filled, FrameEncoder.streamsAnnounced(dst, filled, currentEpoch, firstPassAnnounced));
            streamsAnnounced = true;
        }
        Integer key;
        while ((key = reannouncements.peek()) != null) {
            // a key not announced yet goes out with its current mode once it is
            if (key < nextToAnnounce && carries(key)) {
                int bytes = streamFrame(dst, filled, currentEpoch, streams.get(key));
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
     * @return the bytes written, 0 when the frame does not fit
     */
    private int streamFrame(ByteBuffer dst, int index, long currentEpoch, LeaderStream stream) {
        String name = stream.getStreamName();
        String storageIdentity = stream.getStorageIdentity();
        if (dst.limit() - index < FrameEncoder.bytesWith(FrameType.STREAM, name, storageIdentity)) {
            return 0;
        }
        syncs.set(stream.getKey(), StreamSyncState.ANNOUNCED);
        return frameCipher.seal(dst, index, FrameEncoder.stream(dst, index, currentEpoch, stream.getKey(),
                stream.getStreamReplicationMode(), name, storageIdentity));
    }

    /**
     * The stream's replication mode changed: the follower stops holding it, catches up, or checks its own settings
     * agree.
     */
    void reannounce(int streamKey) {
        reannouncements.add(streamKey);
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
        return frameCipher.seal(dst, 0, FrameEncoder.heartbeat(dst, 0, nodeEpoch.getAsLong(), replicationLog.getTailOffset()));
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
            if (pendingClose != null) {
                sendPendingCloseOnIoThread();
            }
        }
    }

    private void sendLoop() {
        senderIdle.reset();
        lastSendNanos = clock.nanoTime();
        lastAckNanos = lastSendNanos;
        long startedNanos = lastSendNanos;
        while (state != State.CLOSED && !handingOver) {
            if (state == State.DROPPED_AS_TOO_SLOW) {
                closeDropped();
                break;
            }
            if (state == State.AWAITING_KEY_CONFIRMATION) {
                if (stallTimeoutNanos > 0 && clock.nanoTime() - startedNanos > stallTimeoutNanos) {
                    close(CloseReason.PROTOCOL_ERROR, "no KEY_CONFIRMATION within "
                            + TimeUnit.NANOSECONDS.toMillis(stallTimeoutNanos) + " ms");
                    break;
                }
                senderIdle.idle();
                continue;
            }
            if (isStalled(clock.nanoTime())) {
                close(CloseReason.STALLED, "no ack for " + TimeUnit.NANOSECONDS.toMillis(stallTimeoutNanos)
                        + " ms with writes unapplied");
                break;
            }
            if (batches.hasNoRoom()) {
                if (isSnapshotInProgress() || reader.getOffset() != replicationLog.getTailOffset()) {
                    batches.countSendWait();
                }
                senderIdle.idle();
                continue;
            }
            startPendingSnapshot();
            boolean sent = sendLiveBatch();
            if (isSnapshotInProgress() && state == State.STREAMING) {
                sendSnapshotChunk();
                sent = true;
            }
            if (sent) {
                lastSendNanos = clock.nanoTime();
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
            filled = heartbeatIfDue(batch, clock.nanoTime());
        }
        // sent even once closed: a sealed frame left out would fail the CLOSE's tag
        if (filled == 0) {
            batches.keep(batch);
            return false;
        }
        if (filledRecordOffset >= 0) {
            if (lastSentRecordOffset <= appliedOffset) {
                owedSinceNanos = clock.nanoTime();
            }
            lastSentRecordOffset = filledRecordOffset;
            // before the send: an ack processed in between would otherwise leave the sample to the next one
            ackLatency.onSent(filledRecordOffset, System.nanoTime());
        }
        batches.send(batch, filled);
        return true;
    }

    /**
     * Owing an ack and silent for the stall timeout: a slow follower still acks each read, a hung one does not.
     */
    private boolean isStalled(long nowNanos) {
        return stallTimeoutNanos > 0 && lastSentRecordOffset > appliedOffset
                && nowNanos - Math.max(owedSinceNanos, lastAckNanos) > stallTimeoutNanos;
    }

    /**
     * For a leader stopping cleanly, its writers already stopped: waits for every record of the log to be sent, then
     * closes with {@link CloseReason#SHUTDOWN}. Arriving after them on the same connection, it tells the follower it
     * holds everything, so the follower takes over knowing it lost no write. Returns once the follower hung up
     * on it, so it has read it before leadership moves. When the deadline passes first, the connection is dropped.
     *
     * @return whether the follower read it
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
            log.warn("Follower {} was not sent every record before the deadline: it will not take over as handed over",
                    followerId);
            markClosed();
            ioSession.stop(Deadline.immediate());
            return false;
        }
        if (!requestClose(PendingClose.thenAwaitHangUp(CloseReason.SHUTDOWN,
                "the leader stopped cleanly, every record was sent"))) {
            return false;
        }
        boolean read = deadline.waitAsLongAs(() -> !followerHungUp);
        ioSession.stop(Deadline.of(CLOSE_FLUSH));
        if (!read) {
            log.warn("Follower {} did not hang up on the hand over before the deadline: it may not take over as handed over",
                    followerId);
        }
        return read;
    }

    /**
     * Sends a CLOSE and disconnects, the connection flushing it first. Any thread; only the first close of the
     * connection does it. The IO thread sends it once the sender stopped, the sender leaving its loop at its next turn,
     * so it is sealed in order after the sender's last frame.
     *
     * @return false when the connection was already closed, and nothing sent
     */
    boolean close(CloseReason reason, String text) {
        return requestClose(PendingClose.thenStop(reason, text));
    }

    private boolean requestClose(PendingClose closing) {
        // recorded before the mark: a sender leaving its loop on the mark finds it
        if (!PENDING_CLOSE.compareAndSet(this, null, closing)) {
            return false;
        }
        if (!markClosed()) {
            PENDING_CLOSE.compareAndSet(this, closing, null);
            return false;
        }
        logClose(closing.getReason(), closing.getText());
        if (senderRunning) {
            senderIdle.wakeup();
        } else {
            sendPendingCloseOnIoThread();
        }
        return true;
    }

    private void sendPendingCloseOnIoThread() {
        ioSession.processTask(this::sendPendingClose, (task, refusedOnceDisconnected) -> {
        });
    }

    private void sendPendingClose() {
        PendingClose closing = pendingClose;
        if (closing == null || !closeSent.compareAndSet(false, true)) {
            return;
        }
        FrameCipher sealer = streamStarted ? frameCipher : FrameCipher.CLEAR;
        ByteBuffer frame = ioSession.borrow(FrameEncoder.bytesWith(FrameType.CLOSE, closing.getText())
                + sealer.getTagBytes());
        int length = FrameEncoder.close(frame.clear(), 0, nodeEpoch.getAsLong(), closing.getReason(), closing.getText());
        ioSession.send(BatchBuffers.written(frame, sealer.seal(frame, 0, length)), true);
        if (closing.isStopAfter()) {
            ioSession.stop(Deadline.of(CLOSE_FLUSH));
        }
    }

    private void logClose(CloseReason reason, String shortText) {
        if (reason == CloseReason.SHUTDOWN || reason == CloseReason.RETRY_LATER) {
            log.info("Closing the replication stream to follower {} lane {}: {} {}", followerId, lane, reason, shortText);
        } else if (reason.isEncryptionRefusal()) {
            log.error("Closing the replication stream to follower {} lane {}: {} {}", followerId, lane, reason, shortText);
        } else {
            log.warn("Closing the replication stream to follower {} lane {}: {} {}", followerId, lane, reason, shortText);
        }
    }

    /**
     * The connection is gone: the send loop ends at its next turn.
     */
    void disconnected() {
        followerHungUp = true;
        markClosed();
    }

    /**
     * Run by the appending thread, which only marks the connection: closing it would hold that thread, and the log,
     * flushing the CLOSE to a follower that does not read. The sender, or the HELLO before it starts, closes it.
     */
    void droppedAsTooSlow() {
        State current;
        do {
            current = state;
            if (current != State.AWAITING_HELLO && current != State.AWAITING_KEY_CONFIRMATION && current != State.STREAMING) {
                return;
            }
        } while (!STATE.compareAndSet(this, current, State.DROPPED_AS_TOO_SLOW));
        stats.droppedTooSlow(followerId, lane);
        senderIdle.wakeup();
    }

    private void closeDropped() {
        close(CloseReason.TOO_SLOW, "a write waited the log full timeout for it");
    }

    /**
     * @return false when another thread closed the connection first
     */
    private boolean markClosed() {
        State current;
        do {
            current = state;
            if (current == State.CLOSED) {
                return false;
            }
        } while (!STATE.compareAndSet(this, current, State.CLOSED));
        ReplicationLog.Reader opened = reader;
        if (opened != null) {
            opened.close();
        }
        return true;
    }

    @Value
    private static class PendingClose {
        CloseReason reason;
        String text;
        /**
         * Stop the connection once the CLOSE is sent, rather than wait for the follower to hang up as a hand over does.
         */
        boolean stopAfter;

        static PendingClose thenStop(CloseReason reason, String text) {
            return new PendingClose(reason, FrameEncoder.closeText(text), true);
        }

        static PendingClose thenAwaitHangUp(CloseReason reason, String text) {
            return new PendingClose(reason, FrameEncoder.closeText(text), false);
        }
    }

    public enum State {
        AWAITING_HELLO,
        /**
         * Both peers encrypt: nothing streams until the follower's KEY_CONFIRMATION verifies.
         */
        AWAITING_KEY_CONFIRMATION,
        STREAMING,
        /**
         * Dropped as too slow by a write, its CLOSE not sent yet.
         */
        DROPPED_AS_TOO_SLOW,
        CLOSED
    }
}
