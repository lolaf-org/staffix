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
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreMessagesConsumer;
import org.lolaf.staffix.ha.ReplicatedSession;
import org.lolaf.staffix.ha.ReplicatedSessions;
import org.lolaf.staffix.ha.wire.CloseReason;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.FrameType;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Snapshots a lane's sessions for its follower, one at a time and one chunk between two live batches, so the other
 * sessions keep streaming. The session's events are skipped from the stream meanwhile, except a reset, which ends the
 * snapshot: a session's message is in its store before its event is in the log, so the snapshot copies what was
 * skipped, and the follower's store is written in seqnum order as by the stream. It copies in rounds, each up to the
 * store's last message when it starts, and ends with a round that fits in one chunk; a round no shorter than the one
 * before, the session writing faster than it is copied, is sent whole, holding the other sessions for its time.
 *
 * <p>Sender thread only, but for {@link #request()}; allocates nothing once built.
 */
@Slf4j
final class SnapshotSender {

    private static final int NONE = -1;
    private static final int INITIAL_FIND_SPAN = 64;
    private static final int MAX_FIND_SPAN = 1 << 20;
    private static final long STORE_RETRY_NANOS = Duration.ofMillis(10).toNanos();

    private final FollowerConnection connection;
    private final ReplicatedSessions sessions;
    private final SessionSyncs syncs;
    private final BatchBuffers batches;
    private final ReplicationStats stats;
    private final LongSupplier nodeEpoch;
    private final int maxEventFrameBytes;
    private final int chunkBytes;
    private final ChunkWriter writer = new ChunkWriter();
    private final IdleStrategy roomWait = new BackoffIdleStrategy();
    private volatile boolean requested;
    private long retryAtNanos = Long.MAX_VALUE;
    /**
     * The session being snapshot, {@link #NONE} between two.
     */
    private int sessionKey = NONE;
    private @Nullable FixSessionMessagesStore store;
    private long snapshotOffset;
    /**
     * The next seqnum to copy, 0 before the first chunk.
     */
    private long next;
    private long roundEnd;
    private long previousRoundLength;
    /**
     * The round is sent in one chunk, whatever its size.
     */
    private boolean whole;
    private long nextIncomingSeqNum;
    private long nextOutgoingSeqNum;
    private int messageCount;
    /**
     * The seqnums a store read covers: what the last chunk held, doubled while it leaves room, since a store's
     * {@code find} copies its whole range before the writer can stop it.
     */
    private int findSpan;
    /**
     * The stream holds a reset of the session, sent after the snapshot's end.
     */
    private boolean resetAhead;

    /**
     * @param chunkBytes the most bytes sent between two live batches, at most a batch
     */
    SnapshotSender(FollowerConnection connection, ReplicatedSessions sessions, SessionSyncs syncs, BatchBuffers batches,
                   ReplicationStats stats, LongSupplier nodeEpoch, int maxEventFrameBytes, int chunkBytes) {
        this.connection = connection;
        this.sessions = sessions;
        this.syncs = syncs;
        this.batches = batches;
        this.stats = stats;
        this.nodeEpoch = nodeEpoch;
        this.maxEventFrameBytes = maxEventFrameBytes;
        this.chunkBytes = Math.min(chunkBytes, batches.getBatchBytes());
    }

    /**
     * A session's snapshot was asked for: the next {@link #startPending} looks for it. Any thread.
     */
    void request() {
        requested = true;
    }

    boolean isInProgress() {
        return sessionKey != NONE;
    }

    boolean isOf(int key) {
        return sessionKey == key;
    }

    /**
     * The stream reached a reset of the session: the next chunk ends the snapshot, and the reset follows it.
     */
    void resetAhead() {
        resetAhead = true;
    }

    /**
     * Drops the snapshot in progress, the follower having been sent the session again: it says whether it still needs
     * one.
     */
    void cancel() {
        sessionKey = NONE;
        store = null;
    }

    /**
     * Starts the snapshot of the first session the follower asked one for, when none is in progress.
     *
     * @param snapshotOffset where the stream reads next: the session's events after it are skipped until the snapshot
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
            if (!syncs.compareAndSet(key, SessionSyncState.SNAPSHOT_PENDING, SessionSyncState.SNAPSHOT_SENT)) {
                continue;
            }
            FixSessionMessagesStore sessionStore = sessions.get(key).getConfig().getStore();
            if (sessionStore.isStarted()) {
                start(key, sessionStore, snapshotOffset);
                // others may be pending, started once this one ends
                requested = true;
                return;
            }
            // a session registered but not yet running, as on taking leadership: retried without holding the stream
            retryAtNanos = System.nanoTime() + STORE_RETRY_NANOS;
            syncs.compareAndSet(key, SessionSyncState.SNAPSHOT_SENT, SessionSyncState.SNAPSHOT_PENDING);
        }
    }

    /**
     * Sends the next chunk of the snapshot in progress: its next messages while a batch holds them, the whole round
     * when the snapshot is not catching up, and its end once a round fitted in one chunk.
     */
    void sendChunk() {
        ReplicatedSession session = sessions.get(sessionKey);
        writer.start(nodeEpoch.getAsLong());
        try {
            boolean roundStarted = next > roundEnd;
            if (roundStarted || resetAhead) {
                nextIncomingSeqNum = store.getIncomingSeqNum();
                nextOutgoingSeqNum = store.getOutgoingSeqNum();
            }
            if (next == 0) {
                writer.begin();
                next = 1;
            }
            if (resetAhead) {
                end(session);
                return;
            }
            if (roundStarted) {
                startRound();
            }
            copyChunk();
            if (!connection.isConnected()) {
                return;
            }
            if (!writer.stopped) {
                next = roundEnd + 1;
                if (roundStarted) {
                    end(session);
                    return;
                }
            }
            writer.flush();
        } catch (RuntimeException e) {
            log.warn("Snapshot of FIX session {} {} failed", session.getFixSessionType(), session.getFixSessionId(), e);
            int failedKey = sessionKey;
            cancel();
            syncs.compareAndSet(failedKey, SessionSyncState.SNAPSHOT_SENT, SessionSyncState.SNAPSHOT_PENDING);
            connection.close(CloseReason.SNAPSHOT_FAILED, "FIX session " + session.getFixSessionId() + ": " + e.getMessage());
        }
    }

    private void start(int key, FixSessionMessagesStore sessionStore, long offset) {
        sessionKey = key;
        store = sessionStore;
        snapshotOffset = offset;
        next = 0;
        roundEnd = -1;
        previousRoundLength = Long.MAX_VALUE;
        whole = false;
        nextIncomingSeqNum = 0;
        nextOutgoingSeqNum = 0;
        messageCount = 0;
        findSpan = INITIAL_FIND_SPAN;
        resetAhead = false;
    }

    private void startRound() {
        roundEnd = nextOutgoingSeqNum - 1;
        long length = roundEnd - next + 1;
        whole = length >= previousRoundLength;
        previousRoundLength = length;
    }

    private void copyChunk() {
        int messagesBefore = messageCount;
        while (next <= roundEnd && !writer.stopped && connection.isConnected()) {
            long stop = Math.min(roundEnd, next + findSpan - 1);
            store.find(next, stop, writer);
            if (!writer.stopped) {
                next = Math.max(next, stop + 1);
                findSpan = Math.min(findSpan * 2, MAX_FIND_SPAN);
            }
        }
        if (writer.stopped) {
            findSpan = messageCount - messagesBefore + 1;
        }
    }

    private void end(ReplicatedSession session) {
        writer.end();
        int messages = messageCount;
        cancel();
        stats.snapshotSent(messages);
        log.info("Snapshot of FIX session {} {} sent to follower {}: {} messages", session.getFixSessionType(),
                session.getFixSessionId(), connection.getFollowerId(), messages);
    }

    /**
     * Writes a chunk's frames into batches, stopping before a message the batch cannot hold unless the round goes
     * whole. A message longer than a batch goes anyway, over as many sends as it needs.
     */
    private final class ChunkWriter implements StoreMessagesConsumer {

        private long currentEpoch;
        private @Nullable ByteBuffer batch;
        private int filled;
        private boolean stopped;

        void start(long epoch) {
            currentEpoch = epoch;
            batch = null;
            filled = 0;
            stopped = false;
        }

        void begin() {
            room(FrameEncoder.fixedBytes(FrameType.SNAPSHOT_BEGIN));
            filled += FrameEncoder.snapshotBegin(batch, filled, currentEpoch, sessionKey, snapshotOffset,
                    nextIncomingSeqNum, nextOutgoingSeqNum);
        }

        /**
         * A message too long for a frame goes in fragments, each no longer than the follower accepts: the longest
         * event frame, which a batch, rounded up for betty's pools, exceeds.
         */
        @Override
        public boolean onMessage(long seqNum, ByteBuffer message) {
            int messageLength = message.remaining();
            int maxFragmentLength = maxEventFrameBytes - FrameEncoder.snapshotMessageBytes(0);
            int fragments = Math.max(1, (messageLength + maxFragmentLength - 1) / maxFragmentLength);
            if (!whole && filled > 0 && chunkBytes - filled < fragments * FrameEncoder.snapshotMessageBytes(0) + messageLength) {
                next = seqNum;
                stopped = true;
                return false;
            }
            int from = 0;
            do {
                int length = Math.min(maxFragmentLength, messageLength - from);
                int flags = (from + length < messageLength ? WireFormat.MORE_FRAGMENTS : 0) | (from > 0 ? WireFormat.CONTINUED_FRAGMENT : 0);
                room(FrameEncoder.snapshotMessageBytes(length));
                filled += FrameEncoder.snapshotMessage(batch, filled, currentEpoch, sessionKey, seqNum, message,
                        message.position() + from, length, (byte) flags);
                from += length;
            } while (from < messageLength && connection.isConnected());
            messageCount++;
            next = seqNum + 1;
            return connection.isConnected();
        }

        void end() {
            room(FrameEncoder.fixedBytes(FrameType.SNAPSHOT_END));
            filled += FrameEncoder.snapshotEnd(batch, filled, currentEpoch, sessionKey, messageCount,
                    nextIncomingSeqNum, nextOutgoingSeqNum);
            flush();
        }

        void flush() {
            if (batch != null) {
                batches.send(batch, filled);
                batch = null;
            }
        }

        private void room(int bytes) {
            if (batch != null && batches.getBatchBytes() - filled >= bytes) {
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
