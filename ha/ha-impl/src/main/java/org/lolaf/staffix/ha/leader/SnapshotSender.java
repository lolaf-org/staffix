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

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.ha.spi.LeaderStream;
import org.lolaf.staffix.ha.spi.LeaderStreams;
import org.lolaf.staffix.ha.spi.SnapshotSource;
import org.lolaf.staffix.ha.wire.CloseReason;
import org.lolaf.staffix.ha.wire.FrameCipher;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.FrameType;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Snapshots a lane's streams for its follower, one at a time and one chunk between two live batches, so the other
 * streams keep streaming. The stream's records are skipped from the log meanwhile, except a reset, which ends the
 * snapshot: a record is in its {@link SnapshotSource} before it is in the log, so the snapshot copies what was skipped,
 * and the follower applies the stream in position order as from the log. It copies in rounds, each up to the last
 * position the stream's state covers when it starts, and ends with a round that fits in one chunk; a round no shorter
 * than the one before, the stream written faster than it is copied, is sent whole, holding the other streams for its
 * time.
 *
 * <p>Sender thread only, but for {@link #request()}; allocates nothing once built.
 */
@Slf4j
final class SnapshotSender {

    private static final int NONE = -1;
    private static final int INITIAL_READ_SPAN = 64;
    private static final int MAX_READ_SPAN = 1 << 20;
    private static final long SOURCE_RETRY_NANOS = Duration.ofMillis(10).toNanos();

    private final FollowerConnection connection;
    private final LeaderStreams streams;
    private final StreamSyncs syncs;
    private final BatchBuffers batches;
    private final ReplicationStats stats;
    private final LongSupplier nodeEpoch;
    private final int maxRecordFrameBytes;
    private final int chunkBytes;
    private final ChunkWriter writer;
    private final IdleStrategy roomWait;
    /**
     * The stream's state as of the round in progress.
     */
    private final ByteBuffer state;
    private volatile boolean requested;
    private long retryAtNanos;
    /**
     * The stream being snapshot, {@link #NONE} between two.
     */
    private int streamKey;
    private @Nullable SnapshotSource source;
    private long snapshotOffset;
    private boolean begun;
    /**
     * The next position to copy.
     */
    private long next;
    private long roundEnd;
    private long previousRoundLength;
    /**
     * The round is sent in one chunk, whatever its size.
     */
    private boolean whole;
    private int recordCount;
    /**
     * The positions a source read covers: what the last chunk held, doubled while it leaves room, since a source may
     * copy its whole range before the writer can stop it.
     */
    private int readSpan;
    /**
     * The log holds a reset of the stream, sent after the snapshot's end.
     */
    private boolean resetAhead;

    /**
     * @param chunkBytes the most bytes sent between two live batches, at most a batch
     */
    SnapshotSender(FollowerConnection connection, LeaderStreams streams, StreamSyncs syncs, BatchBuffers batches,
                   ReplicationStats stats, LongSupplier nodeEpoch, int maxRecordFrameBytes, int chunkBytes) {
        this.writer = new ChunkWriter();
        this.roomWait = new BackoffIdleStrategy();
        this.state = ByteBuffer.allocate(WireFormat.MAX_STATE_BYTES);
        this.retryAtNanos = Long.MAX_VALUE;
        this.streamKey = NONE;
        this.connection = connection;
        this.streams = streams;
        this.syncs = syncs;
        this.batches = batches;
        this.stats = stats;
        this.nodeEpoch = nodeEpoch;
        this.maxRecordFrameBytes = maxRecordFrameBytes;
        this.chunkBytes = Math.min(chunkBytes, batches.getBatchBytes());
    }

    /**
     * A stream's snapshot was asked for: the next {@link #startPending} looks for it. Any thread.
     */
    void request() {
        requested = true;
    }

    boolean isInProgress() {
        return streamKey != NONE;
    }

    boolean isOf(int key) {
        return streamKey == key;
    }

    /**
     * The log reached a reset of the stream: the next chunk ends the snapshot, and the reset follows it.
     */
    void resetAhead() {
        resetAhead = true;
    }

    /**
     * Drops the snapshot in progress, the follower having been sent the stream again: it says whether it still needs
     * one.
     */
    void cancel() {
        streamKey = NONE;
        source = null;
    }

    /**
     * Starts the snapshot of the first stream the follower asked one for, when none is in progress.
     *
     * @param snapshotOffset where the log reads next: the stream's records after it are skipped until the snapshot
     *                       ends
     * @param announcedKeys  the keys below it were announced
     */
    void startPending(long snapshotOffset, int announcedKeys) {
        if (isInProgress() || !requested && (retryAtNanos == Long.MAX_VALUE || System.nanoTime() < retryAtNanos)) {
            return;
        }
        requested = false;
        retryAtNanos = Long.MAX_VALUE;
        for (int key = 0; key < announcedKeys && connection.isConnected(); key++) {
            // marked sent before it goes: the follower's REPLICATING may come back before the snapshot's last frame
            // is handed over
            if (!syncs.compareAndSet(key, StreamSyncState.SNAPSHOT_PENDING, StreamSyncState.SNAPSHOT_SENT)) {
                continue;
            }
            SnapshotSource streamSource = streams.get(key).newSnapshotSource();
            if (streamSource.isReadable()) {
                start(key, streamSource, snapshotOffset);
                // others may be pending, started once this one ends
                requested = true;
                return;
            }
            // a stream registered but not yet readable, as on taking leadership: retried without holding the log
            retryAtNanos = System.nanoTime() + SOURCE_RETRY_NANOS;
            syncs.compareAndSet(key, StreamSyncState.SNAPSHOT_SENT, StreamSyncState.SNAPSHOT_PENDING);
        }
    }

    /**
     * Sends the next chunk of the snapshot in progress: its next records while a batch holds them, the whole round
     * when the snapshot is not catching up, and its end once a round fitted in one chunk.
     */
    void sendChunk() {
        LeaderStream stream = streams.get(streamKey);
        writer.start(nodeEpoch.getAsLong());
        try {
            boolean roundStarted = next > roundEnd;
            long lastPosition = roundEnd;
            if (roundStarted || resetAhead) {
                lastPosition = source.captureState(state);
            }
            if (!begun) {
                writer.begin();
                begun = true;
            }
            if (resetAhead) {
                end(stream);
                return;
            }
            if (roundStarted) {
                startRound(lastPosition);
            }
            copyChunk();
            if (!connection.isConnected()) {
                // sent even once closed: a sealed frame left out would fail the CLOSE's tag
                writer.flush();
                return;
            }
            if (!writer.stopped) {
                next = roundEnd + 1;
                if (roundStarted) {
                    end(stream);
                    return;
                }
            }
            writer.flush();
        } catch (RuntimeException e) {
            log.warn("Snapshot of stream {} failed", stream.getStreamName(), e);
            int failedKey = streamKey;
            writer.flush();
            cancel();
            syncs.compareAndSet(failedKey, StreamSyncState.SNAPSHOT_SENT, StreamSyncState.SNAPSHOT_PENDING);
            connection.close(CloseReason.SNAPSHOT_FAILED, "stream " + stream.getStreamName() + ": " + e.getMessage());
        }
    }

    private void start(int key, SnapshotSource streamSource, long offset) {
        streamKey = key;
        source = streamSource;
        snapshotOffset = offset;
        begun = false;
        next = 0;
        roundEnd = -1;
        previousRoundLength = Long.MAX_VALUE;
        whole = false;
        state.clear();
        recordCount = 0;
        readSpan = INITIAL_READ_SPAN;
        resetAhead = false;
    }

    private void startRound(long lastPosition) {
        roundEnd = lastPosition;
        long length = roundEnd - next + 1;
        whole = length >= previousRoundLength;
        previousRoundLength = length;
    }

    private void copyChunk() {
        int recordsBefore = recordCount;
        while (next <= roundEnd && !writer.stopped && connection.isConnected()) {
            long stop = Math.min(roundEnd, next + readSpan - 1);
            source.read(next, stop, writer);
            if (!writer.stopped) {
                next = Math.max(next, stop + 1);
                readSpan = Math.min(readSpan * 2, MAX_READ_SPAN);
            }
        }
        if (writer.stopped) {
            readSpan = recordCount - recordsBefore + 1;
        }
    }

    private void end(LeaderStream stream) {
        writer.end();
        int records = recordCount;
        cancel();
        stats.snapshotSent(records);
        log.info("Snapshot of stream {} sent to follower {}: {} records", stream.getStreamName(),
                connection.getFollowerId(), records);
    }

    /**
     * Writes a chunk's frames into batches, stopping before a record the batch cannot hold unless the round goes
     * whole. A record longer than a batch goes anyway, over as many sends as it needs.
     */
    private final class ChunkWriter implements SnapshotSource.RecordConsumer {

        private long currentEpoch;
        private FrameCipher cipher;
        private @Nullable ByteBuffer batch;
        private int filled;
        private boolean stopped;

        private ChunkWriter() {
            this.cipher = FrameCipher.CLEAR;
        }

        void start(long epoch) {
            currentEpoch = epoch;
            cipher = connection.getFrameCipher();
            batch = null;
            filled = 0;
            stopped = false;
        }

        void begin() {
            room(FrameEncoder.fixedBytes(FrameType.SNAPSHOT_BEGIN) + state.position());
            filled += cipher.seal(batch, filled, FrameEncoder.snapshotBegin(batch, filled, currentEpoch, streamKey,
                    snapshotOffset, state, 0, state.position()));
        }

        /**
         * A payload too long for a frame goes in fragments, each no longer than the follower accepts: the longest
         * record frame, which a batch, rounded up for betty's pools, exceeds.
         */
        @Override
        public boolean onRecord(byte contentType, byte flags, long position, ByteBuffer payload) {
            int payloadLength = payload.remaining();
            int maxFragmentLength = maxRecordFrameBytes - FrameEncoder.snapshotRecordBytes(0);
            int fragments = Math.max(1, (payloadLength + maxFragmentLength - 1) / maxFragmentLength);
            int fragmentBytes = FrameEncoder.snapshotRecordBytes(0) + cipher.getTagBytes();
            if (!whole && filled > 0 && chunkBytes - filled < fragments * fragmentBytes + payloadLength) {
                next = position;
                stopped = true;
                return false;
            }
            int plaintext = flags & WireFormat.PLAINTEXT;
            int from = 0;
            do {
                int length = Math.min(maxFragmentLength, payloadLength - from);
                int fragmentFlags = (from + length < payloadLength ? WireFormat.MORE_FRAGMENTS : 0)
                        | (from > 0 ? WireFormat.CONTINUED_FRAGMENT : 0) | plaintext;
                room(FrameEncoder.snapshotRecordBytes(length));
                filled += cipher.seal(batch, filled, FrameEncoder.snapshotRecord(batch, filled, currentEpoch, streamKey,
                        contentType, (byte) fragmentFlags, position, payload, payload.position() + from, length));
                from += length;
            } while (from < payloadLength && connection.isConnected());
            recordCount++;
            next = position + 1;
            return connection.isConnected();
        }

        void end() {
            room(FrameEncoder.fixedBytes(FrameType.SNAPSHOT_END) + state.position());
            filled += cipher.seal(batch, filled, FrameEncoder.snapshotEnd(batch, filled, currentEpoch, streamKey,
                    recordCount, state, 0, state.position()));
            flush();
        }

        void flush() {
            if (batch != null) {
                batches.send(batch, filled);
                batch = null;
            }
        }

        private void room(int bytes) {
            if (batch != null && batches.getBatchBytes() - filled >= bytes + cipher.getTagBytes()) {
                return;
            }
            flush();
            awaitRoom();
            batch = batches.take();
            filled = 0;
        }

        private void awaitRoom() {
            while (batches.hasNoRoom() && connection.isConnected()) {
                batches.countSendWait();
                roomWait.idle();
            }
            roomWait.reset();
        }
    }
}
