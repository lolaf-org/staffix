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

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.ha.replication.log.ReplicationLog;
import org.lolaf.staffix.ha.replication.spi.LeaderStreams;
import org.lolaf.staffix.ha.replication.spi.RecordFlag;
import org.lolaf.staffix.ha.replication.spi.ReplicationClock;
import org.lolaf.staffix.ha.replication.wire.CloseReason;
import org.lolaf.staffix.ha.replication.wire.FrameCipher;
import org.lolaf.staffix.ha.replication.wire.FrameDecoder;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.wire.WireFormat;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The thread streaming the log to one follower's connection: it reads as many records as fit straight into a batch
 * behind room for their headers, sends it, then a {@link SnapshotSender snapshot} chunk if one is in progress. It
 * sends a heartbeat when it sent nothing for a heartbeat interval, and closes a follower that owes an ack and stays
 * silent for the stall timeout.
 */
final class FollowerSender {

    private final FollowerConnection connection;
    /**
     * This node's current election epoch, stamped on every frame; read once per batch, so a batch carries one.
     */
    private final LongSupplier nodeEpoch;
    private final long heartbeatIntervalNanos;
    private final long stallTimeoutNanos;
    private final IdleStrategy idle;
    private final ReplicationClock clock;
    private final FollowerAcks acks;
    private final BatchBuffers batches;
    private final SnapshotSender snapshots;
    private final StreamAnnouncer announcer;
    private final Consumer<Runnable> launcher;
    private @Nullable ReplicationLog log;
    private ReplicationLog.@Nullable Reader reader;
    /**
     * Seals every frame the sender writes; set before it starts.
     */
    @Getter
    private FrameCipher sealer;
    /**
     * Where the next batch starts reading the log, -1 before the first: once it is the log's tail, every record has
     * been handed to betty.
     */
    @Getter
    private volatile long sentOffset;
    /**
     * A frame was sealed, and every frame sealed is sent: the follower then opens every later one, the CLOSE included.
     */
    @Getter
    private volatile boolean streamStarted;
    @Getter
    private volatile boolean running;
    private volatile boolean stopping;
    private long lastSendNanos;
    private long lastSentRecordOffset;
    /**
     * When the follower last went from owing nothing to owing an ack.
     */
    private long owedSinceNanos;
    /**
     * The offset of the last record the last {@link #fillBatch} took, -1 when it took none.
     */
    private long filledRecordOffset;

    FollowerSender(FollowerConnection connection, IOSession ioSession, LeaderStreams streams, StreamSyncs syncs,
                   FollowerAcks acks, LongSupplier nodeEpoch, int maxRecordFrameBytes, Duration heartbeatInterval,
                   Duration stallTimeout, int sendBuffers, int snapshotChunkBytes, IdleStrategy idle,
                   ReplicationStats stats, Consumer<Runnable> launcher, ReplicationClock clock) {
        this.sealer = FrameCipher.CLEAR;
        this.sentOffset = -1;
        this.lastSentRecordOffset = -1;
        this.filledRecordOffset = -1;
        this.connection = connection;
        this.nodeEpoch = nodeEpoch;
        this.heartbeatIntervalNanos = heartbeatInterval.toNanos();
        this.stallTimeoutNanos = stallTimeout.toNanos();
        this.idle = idle;
        this.clock = clock;
        this.acks = acks;
        this.launcher = launcher;
        this.batches = new BatchBuffers(ioSession, sendBuffers,
                WireFormat.bufferBytes(maxRecordFrameBytes + WireFormat.TAG_BYTES), stats);
        this.snapshots = new SnapshotSender(connection, streams, syncs, batches, stats, nodeEpoch, maxRecordFrameBytes,
                snapshotChunkBytes);
        this.announcer = new StreamAnnouncer(connection, streams, syncs, snapshots);
    }

    /**
     * Starts streaming from {@code reader}, the follower's HELLO accepted.
     */
    void start(ReplicationLog log, ReplicationLog.Reader reader, FrameCipher sealer) {
        this.log = log;
        this.reader = reader;
        this.sealer = sealer;
        launcher.accept(this::run);
    }

    void wakeup() {
        idle.wakeup();
    }

    /**
     * Leaves the loop at its next turn, the connection kept open.
     */
    void stop() {
        stopping = true;
    }

    void onWriteWatermark(boolean reached) {
        batches.onWriteWatermark(reached);
    }

    /**
     * A stream's snapshot was asked for. Any thread.
     */
    void requestSnapshot() {
        snapshots.request();
    }

    /**
     * See {@link StreamAnnouncer#reannounce}.
     */
    void reannounce(int streamKey) {
        announcer.reannounce(streamKey);
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
        if (connection.getState() != FollowerConnection.State.STREAMING) {
            return 0;
        }
        int limit = dst.limit();
        // every frame then fits with its tag after it
        dst.limit(limit - sealer.getTagBytes());
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
        acks.onFill(log.getTailOffset(), reader.getOffset(), reader.getRecordOffset());
        long currentEpoch = nodeEpoch.getAsLong();
        int filled = announcer.announce(dst, currentEpoch, sealer);
        int announced = announcer.getNextToAnnounce();
        while (connection.getState() == FollowerConnection.State.STREAMING
                && dst.limit() - filled >= WireFormat.RECORD_BODY_OFFSET) {
            int bodyIndex = filled + WireFormat.RECORD_BODY_OFFSET;
            int body = reader.readInto(dst, bodyIndex);
            if (body < 0) {
                break;
            }
            int streamKey = FrameDecoder.recordStreamKey(dst, bodyIndex);
            if (streamKey >= announced) {
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
            acks.onRecordRead(filledRecordOffset, reader.getOffset());
            int frameLength = FrameEncoder.recordHeader(dst, filled, currentEpoch, filledRecordOffset, body);
            filled += sealer.seal(dst, filled, frameLength);
        }
        sentOffset = reader.getOffset();
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
        return sealer.seal(dst, 0, FrameEncoder.heartbeat(dst, 0, nodeEpoch.getAsLong(), log.getTailOffset()));
    }

    void startPendingSnapshot() {
        snapshots.startPending(reader.getOffset(), announcer.getNextToAnnounce());
    }

    boolean isSnapshotInProgress() {
        return snapshots.isInProgress();
    }

    void sendSnapshotChunk() {
        snapshots.sendChunk(sealer);
    }

    private void run() {
        running = true;
        try {
            sendLoop();
        } finally {
            running = false;
            connection.senderStopped();
        }
    }

    private void sendLoop() {
        idle.reset();
        lastSendNanos = clock.nanoTime();
        owedSinceNanos = lastSendNanos;
        long startedNanos = lastSendNanos;
        while (connection.getState() != FollowerConnection.State.CLOSED && !stopping) {
            if (connection.getState() == FollowerConnection.State.DROPPED_AS_TOO_SLOW) {
                connection.closeDropped();
                break;
            }
            if (connection.getState() == FollowerConnection.State.AWAITING_KEY_CONFIRMATION) {
                if (stallTimeoutNanos > 0 && clock.nanoTime() - startedNanos > stallTimeoutNanos) {
                    connection.close(CloseReason.PROTOCOL_ERROR, "no KEY_CONFIRMATION within "
                            + TimeUnit.NANOSECONDS.toMillis(stallTimeoutNanos) + " ms");
                    break;
                }
                idle.idle();
                continue;
            }
            if (isStalled(clock.nanoTime())) {
                connection.close(CloseReason.STALLED, "no ack for " + TimeUnit.NANOSECONDS.toMillis(stallTimeoutNanos)
                        + " ms with writes unapplied");
                break;
            }
            if (batches.hasNoRoom()) {
                if (isSnapshotInProgress() || reader.getOffset() != log.getTailOffset()) {
                    batches.countSendWait();
                }
                idle.idle();
                continue;
            }
            startPendingSnapshot();
            boolean sent = sendLiveBatch();
            if (isSnapshotInProgress() && connection.getState() == FollowerConnection.State.STREAMING) {
                sendSnapshotChunk();
                sent = true;
            }
            if (sent) {
                lastSendNanos = clock.nanoTime();
                idle.reset();
            } else {
                idle.idle();
            }
        }
    }

    private boolean sendLiveBatch() {
        ByteBuffer batch = batches.take();
        int filled = fillBatch(batch);
        if (filled == 0 && connection.getState() == FollowerConnection.State.STREAMING) {
            filled = heartbeatIfDue(batch, clock.nanoTime());
        }
        // sent even once closed: a sealed frame left out would fail the CLOSE's tag
        if (filled == 0) {
            batches.keep(batch);
            return false;
        }
        if (filledRecordOffset >= 0) {
            if (lastSentRecordOffset <= acks.getAppliedOffset()) {
                owedSinceNanos = clock.nanoTime();
            }
            lastSentRecordOffset = filledRecordOffset;
            acks.onSent(filledRecordOffset, System.nanoTime());
        }
        batches.send(batch, filled);
        return true;
    }

    /**
     * Owing an ack and silent for the stall timeout: a slow follower still acks each read, a hung one does not.
     */
    private boolean isStalled(long nowNanos) {
        return stallTimeoutNanos > 0 && lastSentRecordOffset > acks.getAppliedOffset()
                && nowNanos - Math.max(owedSinceNanos, acks.getLastAckNanos()) > stallTimeoutNanos;
    }
}
