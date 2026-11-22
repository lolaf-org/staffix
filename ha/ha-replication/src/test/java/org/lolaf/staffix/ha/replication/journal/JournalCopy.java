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
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.ha.replication.spi.ReplicaSink;
import org.lolaf.staffix.ha.replication.wire.StreamReplicationMode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * A journal as a follower holds it. Records each call as one line in {@link #getWrites()}, so a test compares them
 * all at once: {@code "3 hello"} for an entry, {@code "snapshot 3 hello"} for one a snapshot copied, the content type
 * after the position when not {@link JournalStream#TEXT}.
 */
public final class JournalCopy implements ReplicaSink {

    @Getter
    private volatile StreamReplicationMode mode;
    @Getter
    private volatile @Nullable String storageIdentity;
    @Getter
    private final List<String> writes;
    /**
     * Entries held until {@link #writePending()} while true, as a sink writing in batches.
     */
    @Setter
    private volatile boolean batching;
    private final List<String> pending;
    /**
     * What {@link #getPendingWrites()} reports.
     */
    @Setter
    private volatile long pendingWrites;
    @Getter
    private volatile boolean stopped;
    /**
     * Each write waits for it, as a slow storage would.
     */
    @Setter
    private volatile CountDownLatch writesHeld;
    /**
     * Whether a held write was interrupted, which would fail the write of a storage that does not swallow it.
     */
    @Getter
    private volatile boolean heldWriteInterrupted;
    /**
     * Fails every write, as a storage gone would.
     */
    @Setter
    private volatile boolean failing;

    JournalCopy() {
        this.mode = StreamReplicationMode.ASYNC;
        this.writes = new CopyOnWriteArrayList<>();
        this.pending = new ArrayList<>();
        this.writesHeld = new CountDownLatch(0);
    }

    void configure(StreamReplicationMode mode, @Nullable String storageIdentity) {
        this.mode = mode;
        this.storageIdentity = storageIdentity;
    }

    private static String entry(byte contentType, long position, ByteBuffer buffer, int index, int length) {
        byte[] bytes = new byte[length];
        buffer.duplicate().position(index).get(bytes);
        String type = contentType == JournalStream.TEXT ? "" : " type=" + contentType;
        return position + type + " " + new String(bytes, StandardCharsets.US_ASCII);
    }

    @Override
    public void apply(byte contentType, long position, ByteBuffer buffer, int payloadIndex, int payloadLength) {
        String entry = entry(contentType, position, buffer, payloadIndex, payloadLength);
        if (batching) {
            synchronized (pending) {
                pending.add(entry);
            }
        } else {
            write(List.of(entry));
        }
    }

    private void write(List<String> entries) {
        if (entries.isEmpty()) {
            return;
        }
        if (failing) {
            throw new IllegalStateException("storage down");
        }
        try {
            writesHeld.await();
        } catch (InterruptedException e) {
            heldWriteInterrupted = true;
            Thread.currentThread().interrupt();
        }
        writes.addAll(entries);
    }

    @Override
    public void snapshotBegin(ByteBuffer buffer, int stateIndex, int stateLength) {
        writes.add("begin " + state(buffer, stateIndex, stateLength));
    }

    @Override
    public void snapshotRecord(byte contentType, long position, ByteBuffer buffer, int payloadIndex, int payloadLength) {
        writes.add("snapshot " + entry(contentType, position, buffer, payloadIndex, payloadLength));
    }

    @Override
    public void snapshotEnd(ByteBuffer buffer, int stateIndex, int stateLength) {
        writes.add("end " + state(buffer, stateIndex, stateLength));
    }

    private static String state(ByteBuffer buffer, int index, int length) {
        byte[] bytes = new byte[length];
        buffer.duplicate().position(index).get(bytes);
        return "state=" + new String(bytes, StandardCharsets.US_ASCII);
    }

    @Override
    public void writePending() {
        List<String> entries;
        synchronized (pending) {
            entries = List.copyOf(pending);
            pending.clear();
        }
        write(entries);
    }

    @Override
    public void discardPending() {
        synchronized (pending) {
            pending.clear();
        }
    }

    @Override
    public long getPendingWrites() {
        return pendingWrites;
    }

    @Override
    public void stop(Deadline deadline) {
        stopped = true;
    }
}
