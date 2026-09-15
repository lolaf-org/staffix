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
package org.lolaf.staffix.stores.core.async;

import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.StoreFileListener;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.queue.impl.single.ThreadLocalAppender;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.WireType;
import org.lolaf.staffix.api.session.FixSessionId;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The queue between the FIX thread and the consumer doing the real write, backed by Chronicle Queue so it
 * survives a restart.
 *
 * <p>Durable rather than in-memory because the point of an asynchronous store is to move the write off the
 * message path, not to make it optional - an event already queued must still be written after a crash.
 */
@Slf4j
public class PersistentQueue<T extends AsyncEvent> {

    private final AtomicLong offers;
    private final AtomicLong polls;
    private final FixSessionId fixSessionId;
    private final AtomicBoolean started;
    private final AtomicInteger lastPolledCycle;
    /**
     * Incremented on every {@link #start(AsyncStoreSettings, String)}, so that a {@link StoreFilesCleaner} can tell
     * whether a release notification belongs to the queue instance it was created for. Chronicle delivers those
     * notifications asynchronously on its {@code background~resource~releaser} thread, hence they can be received
     * after the queue has been stopped and restarted.
     */
    private final AtomicInteger generation;
    /**
     * Sweeps {@link #poll(AsyncEventSerde, AsyncEvent)} triggered on the cleaner. Only bumped when the tailer has
     * actually rolled past a pending cycle, ie when a sweep can delete something, so draining a backlog behind a
     * released-but-not-yet-deletable cycle leaves it untouched. Visible for testing.
     */
    private final AtomicLong pendingDeletionSweeps;
    private SingleChronicleQueue queue;
    private ExcerptTailer tailer;
    /**
     * Non-null when the current {@link StoreFilesCleaner} holds cycles waiting for the tailer to move past them, so
     * that the polling thread can complete the deletions Chronicle notified too early. Reading this single reference
     * is all {@link #poll(AsyncEventSerde, AsyncEvent)} does for the cleanup when there is nothing pending.
     */
    private volatile StoreFilesCleaner pendingDeletions;

    public PersistentQueue(FixSessionId fixSessionId) {
        this.offers = new AtomicLong();
        this.polls = new AtomicLong();
        this.fixSessionId = fixSessionId;
        this.started = new AtomicBoolean();
        this.lastPolledCycle = new AtomicInteger();
        this.generation = new AtomicInteger();
        this.pendingDeletionSweeps = new AtomicLong();
    }

    public void start(AsyncStoreSettings asyncStoreSettings, String type) {
        if (queue != null) {
            return;
        }

        String queueDir = asyncStoreSettings.getAsyncQueueDirectory();
        // some configured queues may start in parallel and use the same directory
        synchronized (PersistentQueue.class) {
            File chronicleDir = new File(queueDir);
            if (!chronicleDir.exists() && !chronicleDir.mkdirs()) {
                throw new IllegalStateException("Unable to create directory " + queueDir);
            }
        }

        String name = type + "-" + fixSessionId.forFileName("");
        // bump the generation first, so that a cleaner of the previous generation can no longer publish itself here
        StoreFilesCleaner storeFilesCleaner = new StoreFilesCleaner(generation.incrementAndGet());
        pendingDeletions = null;
        SingleChronicleQueueBuilder builder = ChronicleQueue.singleBuilder(new File(queueDir, name))
                .wireType(WireType.FIELDLESS_BINARY)
                .storeFileListener(storeFilesCleaner)
                .rollCycle(asyncStoreSettings.getRollCycleProvider().apply(fixSessionId));
        asyncStoreSettings.getBuilderConfigurer().accept(fixSessionId, builder);
        queue = builder.build();
        tailer = queue.createTailer(name);
        tailer.singleThreadedCheckDisabled(true);
        polls.set(0);
        offers.set(0);
        try (DocumentContext dc = tailer.readingDocument()) {
            if (dc.isPresent()) {
                long lastQueueWriteIndex = queue.lastIndex();
                log.debug("Chronicle lastQueueWriteIndex {}", lastQueueWriteIndex);
                long lastQueueReadIndex = tailer.lastReadIndex();
                log.debug("Chronicle lastQueueReadIndex  {}", lastQueueReadIndex);
                long size = 0;
                if (lastQueueWriteIndex != lastQueueReadIndex) {
                    size = queue.countExcerpts(lastQueueReadIndex, lastQueueWriteIndex) + 1;
                }
                log.info("Chronicle computed queue size is {}", size);
                offers.set(size);
                dc.rollbackOnClose();
            }
        }
        // the tailer sits on the oldest cycle that is not fully polled yet, so every cycle strictly before it can be
        // deleted by the StoreFilesCleaner. On an empty queue cycle() returns Integer.MIN_VALUE, which keeps every file.
        lastPolledCycle.set(tailer.cycle());
        started.set(true);
        log.info("Persistent queue {} for FIX session {} started with {} unprocessed elements and last polled cycle {}", type, fixSessionId, getSize(), lastPolledCycle);
    }

    public boolean isStarted() {
        return started.get();
    }

    public void stop() {
        if (queue == null) {
            return;
        }
        started.set(false);
        tailer.close();
        tailer = null;
        queue.close();
        queue = null;
        log.info("Persistent queue for FIX session {} is stopped with {} unprocessed elements", fixSessionId, getSize());
    }

    public long pollsCount() {
        return polls.get();
    }

    public long offersCount() {
        return offers.get();
    }

    public int getSize() {
        return (int) (offers.get() - polls.get());
    }

    public boolean isEmpty() {
        return getSize() == 0;
    }

    public boolean isNotEmpty() {
        return getSize() > 0;
    }

    public void offer(AsyncEventSerde<T> asyncEventSerde, T toSerialize) {
        ExcerptAppender appender = ThreadLocalAppender.acquireThreadLocalAppender(queue);
        try (DocumentContext dc = appender.writingDocument()) {
            asyncEventSerde.serialize(dc, toSerialize);
        }
        offers.getAndIncrement();
    }

    public boolean poll(AsyncEventSerde<T> asyncEventSerde, T target) {
        ExcerptTailer localTailer = tailer;
        if (localTailer == null) {
            // the queue has been stopped while a reader thread was still polling it
            return false;
        }
        try (DocumentContext dc = localTailer.readingDocument()) {
            if (dc.isPresent()) {
                int polledCycle = localTailer.cycle();
                lastPolledCycle.set(polledCycle);
                asyncEventSerde.deserialize(dc, target);
                polls.getAndIncrement();
                StoreFilesCleaner cleaner = pendingDeletions;
                // sweeping is worth its monitor only once the tailer has rolled past the oldest pending cycle,
                // otherwise every event of a backlog would take the cleaner lock for nothing
                if (cleaner != null && cleaner.hasDeletableCycle(polledCycle)) {
                    pendingDeletionSweeps.getAndIncrement();
                    cleaner.deletePolledCycles();
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Visible for testing, see {@link #pendingDeletionSweeps}.
     */
    long pendingDeletionSweepsCount() {
        return pendingDeletionSweeps.get();
    }

    private final class StoreFilesCleaner implements StoreFileListener {
        private final int cleanerGeneration;
        /**
         * Cycles Chronicle has released but whose file cannot be deleted yet because the tailer has not polled past
         * them. They are re-evaluated on every subsequent release and from {@link #poll(AsyncEventSerde, AsyncEvent)}
         * once the tailer moves on, so a deferred file is never leaked.
         */
        private final Map<Integer, File> pendingCycles = new HashMap<>();
        private final Set<Integer> deletedCycles = new HashSet<>();
        /**
         * Lowest cycle of {@link #pendingCycles}, {@link Integer#MAX_VALUE} when there is none. Read without the
         * monitor by {@link #hasDeletableCycle(int)} so the polling thread can tell whether a sweep would delete
         * anything before paying for the lock.
         */
        private volatile int minPendingCycle = Integer.MAX_VALUE;

        private StoreFilesCleaner(int cleanerGeneration) {
            this.cleanerGeneration = cleanerGeneration;
        }

        @Override
        public void onReleased(int cycle, File file) {
            if (cleanerGeneration != generation.get()) {
                // released by a queue instance that has already been stopped and replaced by a newer one, its files
                // are not ours to delete anymore
                return;
            }
            if (!started.get()) {
                // do not release files when stopping and closing the queue
                return;
            }
            synchronized (this) {
                // for some reason chronicle can call 2 times release for the same cycle..
                if (deletedCycles.contains(cycle)) {
                    return;
                }
                if (pendingCycles.put(cycle, file) == null && cycle >= lastPolledCycle.get()) {
                    // logged here rather than from deletePolledCycles: a cycle can stay pending for a whole roll
                    // period, especially the live one, and the sweep runs on the polling path
                    log.debug("Deferring deletion of cycle {} queue file {} for FIX session {} until the tailer polls past it, last polled cycle is {}",
                            cycle, file.getName(), fixSessionId, lastPolledCycle.get());
                }
                // publish the gate before the reference, so a poller seeing the cleaner also sees its oldest cycle
                minPendingCycle = Math.min(minPendingCycle, cycle);
                pendingDeletions = this;
            }
            deletePolledCycles();
        }

        /**
         * @return whether a sweep at the given tailer position would delete at least one of the pending cycles.
         */
        boolean hasDeletableCycle(int polledCycle) {
            return polledCycle > minPendingCycle;
        }

        /**
         * Deletes the files of every released cycle the tailer has already polled past. Called both from Chronicle's
         * background releaser thread and from the polling thread, as a cycle can be released a moment before the
         * tailer publishes its progress.
         */
        void deletePolledCycles() {
            if (cleanerGeneration != generation.get()) {
                // superseded by a restart between the read of pendingDeletions and this call, must not publish
                // ourselves back into it
                return;
            }
            int polledCycle = lastPolledCycle.get();
            synchronized (this) {
                int minPending = Integer.MAX_VALUE;
                Iterator<Map.Entry<Integer, File>> pending = pendingCycles.entrySet().iterator();
                while (pending.hasNext()) {
                    Map.Entry<Integer, File> released = pending.next();
                    int cycle = released.getKey();
                    if (cycle >= polledCycle) {
                        // not fully polled yet, and possibly the live cycle still being appended to: keep the file
                        // and let a later poll, once the tailer rolled past it, delete it
                        minPending = Math.min(minPending, cycle);
                        continue;
                    }
                    pending.remove();
                    delete(cycle, released.getValue());
                }
                minPendingCycle = minPending;
                pendingDeletions = pendingCycles.isEmpty() ? null : this;
            }
        }

        private void delete(int cycle, File file) {
            log.info("Deleting released cycle {} queue file {} for FIX session {}", cycle, file.getName(), fixSessionId);
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                log.error("Failed to delete file", e);
            }
            deletedCycles.add(cycle);
            if (deletedCycles.size() > 32) {
                // clean memory form times to times we don't want to end up with a OOME in a few thousands years :)
                deletedCycles.clear();
                deletedCycles.add(cycle);
            }
        }
    }
}