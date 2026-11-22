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
package org.lolaf.staffix.ha.replication.journal;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.staffix.ha.replication.leader.Followers;
import org.lolaf.staffix.ha.replication.log.LogFullWait;
import org.lolaf.staffix.ha.replication.log.LogRecord;
import org.lolaf.staffix.ha.replication.log.ReplicationLog;
import org.lolaf.staffix.ha.replication.spi.LeaderStream;
import org.lolaf.staffix.ha.replication.spi.SnapshotSource;
import org.lolaf.staffix.ha.replication.wire.StreamReplicationMode;
import org.lolaf.staffix.ha.replication.wire.WireFormat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;

/**
 * One journal on the leader: its entries kept in memory by position, from 1, each one put there before it is appended
 * to the log, as the snapshot contract asks. One appending thread at a time.
 */
public final class JournalStream implements LeaderStream {

    /**
     * The content type of an entry; any other is carried as it is.
     */
    public static final byte TEXT = 1;

    @Getter
    private final int key;
    @Getter
    private final String streamName;
    @Getter
    @Setter
    private volatile StreamReplicationMode streamReplicationMode;
    @Getter
    @Setter
    private volatile @Nullable String storageIdentity;
    private final ReplicationLog log;
    private final Followers followers;
    private final NavigableMap<Long, Entry> entries;
    private final LogRecord logRecord;
    private final LogFullWait logFullWait;
    private volatile long nextPosition;
    private long lastAppendedOffset;
    /**
     * False to stand for storage not started yet: a snapshot waits.
     */
    @Setter
    private volatile boolean readable;
    /**
     * Called with each position a snapshot reads before its entry is handed out: it may throw, as storage gone would.
     */
    @Setter
    private volatile LongConsumer snapshotReads;
    /**
     * Each range a snapshot asked for, from and to.
     */
    @Getter
    private final List<long[]> snapshotRanges;

    JournalStream(int key, String streamName, StreamReplicationMode streamReplicationMode,
                  @Nullable String storageIdentity, ReplicationLog log, Followers followers, Duration logFullTimeout) {
        this.key = key;
        this.streamName = streamName;
        this.streamReplicationMode = streamReplicationMode;
        this.storageIdentity = storageIdentity;
        this.log = log;
        this.followers = followers;
        this.entries = new ConcurrentSkipListMap<>();
        this.logRecord = new LogRecord();
        this.logFullWait = new LogFullWait(logFullTimeout);
        this.nextPosition = 1;
        this.lastAppendedOffset = -1;
        this.readable = true;
        this.snapshotReads = position -> {
        };
        this.snapshotRanges = new CopyOnWriteArrayList<>();
    }

    public long append(String text) {
        return append(TEXT, (byte) 0, text);
    }

    public long appendPlaintext(String text) {
        return append(TEXT, WireFormat.PLAINTEXT, text);
    }

    /**
     * @param flags {@link WireFormat#PLAINTEXT} or none
     * @return the record's log offset
     */
    public synchronized long append(byte contentType, byte flags, String text) {
        return append(contentType, flags, ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * @param payload from its position to its limit, copied
     */
    public synchronized long append(byte contentType, byte flags, ByteBuffer payload) {
        long position = nextPosition;
        byte[] bytes = new byte[payload.remaining()];
        payload.duplicate().get(bytes);
        entries.put(position, new Entry(contentType, flags, bytes));
        nextPosition = position + 1;
        return appendToLog(contentType, flags, position, payload);
    }

    /**
     * Holds {@code count} entries of {@code bytes} bytes each, without appending them to the log: the history a
     * follower only gets from a snapshot.
     */
    public synchronized void hold(int count, int bytes) {
        for (int i = 0; i < count; i++) {
            entries.put(nextPosition, new Entry(TEXT, (byte) 0, new byte[bytes]));
            nextPosition++;
        }
    }

    /**
     * @param flags {@link WireFormat#PLAINTEXT} or none
     */
    public synchronized void hold(byte flags, String text) {
        entries.put(nextPosition, new Entry(TEXT, flags, text.getBytes(StandardCharsets.US_ASCII)));
        nextPosition++;
    }

    /**
     * Moves past {@code count} positions holding no entry, which a snapshot skips.
     */
    public synchronized void skip(int count) {
        nextPosition += count;
    }

    /**
     * Starts the journal over, empty, its next entry at position 1.
     */
    public synchronized long reset() {
        entries.clear();
        nextPosition = 1;
        return appendToLog(TEXT, WireFormat.RESET, 0, null);
    }

    /**
     * @throws IllegalArgumentException for a record longer than the log holds, left out of the log
     */
    private long appendToLog(byte contentType, byte flags, long position, @Nullable ByteBuffer payload) {
        lastAppendedOffset = log.append(logRecord.set(key, contentType, flags, position, payload), logFullWait);
        followers.wakeSenders();
        return lastAppendedOffset;
    }

    /**
     * As a sync mode asks, after the last append.
     */
    public Followers.Ack awaitAck(Duration timeout) {
        return followers.awaitAck(key, lastAppendedOffset, streamReplicationMode, timeout.toNanos(),
                new BackoffIdleStrategy());
    }

    public long getNextPosition() {
        return nextPosition;
    }

    @Override
    public SnapshotSource newSnapshotSource() {
        return new Snapshot();
    }

    private static final class Entry {

        private final byte contentType;
        private final byte flags;
        private final byte[] payload;

        private Entry(byte contentType, byte flags, byte[] payload) {
            this.contentType = contentType;
            this.flags = flags;
            this.payload = payload;
        }
    }

    /**
     * Its state is the next position, as ASCII digits.
     */
    private final class Snapshot implements SnapshotSource {

        @Override
        public boolean isReadable() {
            return readable;
        }

        @Override
        public long captureState(ByteBuffer state) {
            long next = nextPosition;
            state.clear().put(Long.toString(next).getBytes(StandardCharsets.US_ASCII));
            return next - 1;
        }

        @Override
        public void read(long fromPosition, long toPosition, RecordConsumer consumer) {
            snapshotRanges.add(new long[]{fromPosition, toPosition});
            if (fromPosition > toPosition) {
                return;
            }
            for (Map.Entry<Long, Entry> entry : entries.subMap(fromPosition, true, toPosition, true).entrySet()) {
                snapshotReads.accept(entry.getKey());
                Entry value = entry.getValue();
                if (!consumer.onRecord(value.contentType, value.flags, entry.getKey(), ByteBuffer.wrap(value.payload))) {
                    return;
                }
            }
        }
    }
}
