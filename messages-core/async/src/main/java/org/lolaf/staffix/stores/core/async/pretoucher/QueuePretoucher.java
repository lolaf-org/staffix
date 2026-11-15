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
package org.lolaf.staffix.stores.core.async.pretoucher;

import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.MappedBytes;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueStore;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Touches the pages ahead of a queue's writer, from a thread other than the writer's, as far as the writer appends in
 * a given time at its recent rate.
 *
 * <p>It follows the last cycle file created, and holds that cycle until the writer creates the next one, as the
 * writer's own appender does: it never keeps a cycle the writer has left. Must be closed before the queue.
 */
@Slf4j
public final class QueuePretoucher implements AutoCloseable {

    private final SingleChronicleQueue queue;
    private final long aheadNanos;
    private final long minAheadBytes;
    private final int pageSize;
    private final AtomicLong missedPages;
    private final WriteRate writeRate;
    private SingleChronicleQueueStore store;
    private MappedBytes storeBytes;
    private PageToucher pageToucher;
    private boolean failing;
    private boolean closed;

    /**
     * An {@code aheadTime} of zero keeps the distance at {@code minAheadBytes}.
     */
    public QueuePretoucher(SingleChronicleQueue queue, Duration aheadTime, long minAheadBytes) {
        this(queue, aheadTime, minAheadBytes, OS.pageSize());
    }

    QueuePretoucher(SingleChronicleQueue queue, Duration aheadTime, long minAheadBytes, int pageSize) {
        this.queue = queue;
        this.aheadNanos = aheadTime.toNanos();
        this.minAheadBytes = minAheadBytes;
        this.pageSize = pageSize;
        this.missedPages = new AtomicLong();
        this.writeRate = new WriteRate();
    }

    /**
     * Pages the writer reached before the pretoucher, so it took their fault; the ones it fills right after a roll are
     * not counted. A steady count means the time ahead falls short of the pretouch thread's stalls.
     */
    public long missedPages() {
        return missedPages.get();
    }

    /**
     * Touches the pages ahead of the writer's last append. Best effort: a failure is logged once and retried at the
     * next call, as the writer works without it.
     */
    public synchronized void pretouch() {
        if (closed) {
            return;
        }
        try {
            followLastCycle();
            if (pageToucher != null) {
                long writePosition = store.writePosition();
                writeRate.update(writePosition, System.nanoTime());
                pageToucher.touchAhead(writePosition, aheadBytes());
            }
            failing = false;
        } catch (Exception e) {
            if (!failing) {
                log.warn("Unable to pretouch queue {}, retrying", queue.file(), e);
                failing = true;
            }
            releaseStore();
        }
    }

    synchronized long aheadBytes() {
        return Math.max(minAheadBytes, writeRate.bytesIn(aheadNanos));
    }

    @Override
    public synchronized void close() {
        closed = true;
        releaseStore();
    }

    private void followLastCycle() {
        int lastCycle = queue.lastCycle();
        if (store != null && store.cycle() == lastCycle) {
            return;
        }
        SingleChronicleQueueStore lastStore = queue.storeForCycle(lastCycle, queue.epoch(), false, null);
        if (lastStore == null) {
            return;
        }
        releaseStore();
        store = lastStore;
        storeBytes = lastStore.bytes();
        pageToucher = new PageToucher(storeBytes.mappedFile(), pageSize, missedPages);
        writeRate.restartAt(lastStore.writePosition(), System.nanoTime());
    }

    private void releaseStore() {
        if (pageToucher != null) {
            pageToucher.close();
            pageToucher = null;
        }
        if (storeBytes != null) {
            storeBytes.releaseLast();
            storeBytes = null;
        }
        if (store != null) {
            queue.closeStore(store);
            store = null;
        }
    }
}
