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

import net.openhft.chronicle.core.io.BackgroundResourceReleaser;
import net.openhft.chronicle.core.io.IOTools;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.StoreFileListener;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueStore;
import net.openhft.chronicle.queue.rollcycles.TestRollCycles;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.WireType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lolaf.ringos.Deadline;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;

class TestQueuePretoucher {

    private static final long AHEAD_BYTES = 1 << 20;

    private static final String PADDING = "x".repeat(1000);

    /**
     * Acquisitions minus releases of each cycle file, by whoever holds it.
     */
    final Map<Integer, AtomicInteger> cycleHolders = new ConcurrentHashMap<>();
    SingleChronicleQueue queue;
    QueuePretoucher pretoucher;
    File tmpfsDirectory;

    @BeforeEach
    void setup() {
        open(UnaryOperator.identity());
    }

    @AfterEach
    void shutdown() {
        close();
        if (tmpfsDirectory != null) {
            IOTools.deleteDirWithFiles(tmpfsDirectory);
        }
    }

    private void open(UnaryOperator<SingleChronicleQueueBuilder> configurer) {
        open(new File("./target/pretouch-" + System.nanoTime()), configurer);
    }

    private void open(File directory, UnaryOperator<SingleChronicleQueueBuilder> configurer) {
        StoreFileListener holdersCounter = new StoreFileListener() {
            @Override
            public void onAcquired(int cycle, File file) {
                cycleHolders.computeIfAbsent(cycle, c -> new AtomicInteger()).incrementAndGet();
            }

            @Override
            public void onReleased(int cycle, File file) {
                cycleHolders.computeIfAbsent(cycle, c -> new AtomicInteger()).decrementAndGet();
            }
        };
        queue = configurer.apply(ChronicleQueue.singleBuilder(directory)
                        .wireType(WireType.FIELDLESS_BINARY)
                        .storeFileListener(holdersCounter))
                .build();
        pretoucher = new QueuePretoucher(queue, Duration.ZERO, AHEAD_BYTES, new AtomicLong());
    }

    private void close() {
        pretoucher.close();
        queue.close();
    }

    private int holders(int cycle) {
        BackgroundResourceReleaser.releasePendingResources();
        return cycleHolders.getOrDefault(cycle, new AtomicInteger()).get();
    }

    private List<String> readAll() {
        List<String> read = new ArrayList<>();
        try (ExcerptTailer tailer = queue.createTailer()) {
            for (String text; (text = tailer.readText()) != null; ) {
                read.add(text);
            }
        }
        return read;
    }

    @Test
    void appendsStayIntactWhilePretouching() throws InterruptedException {
        AtomicBoolean writing = new AtomicBoolean(true);
        Thread pretouching = new Thread(() -> {
            while (writing.get()) {
                pretoucher.pretouch();
            }
        });
        pretouching.start();

        try (ExcerptAppender appender = queue.createAppender()) {
            for (int i = 0; i < 20_000; i++) {
                appender.writeText(i + PADDING);
            }
        } finally {
            writing.set(false);
            pretouching.join();
        }

        List<String> read = readAll();
        assertThat(read).hasSize(20_000);
        for (int i = 0; i < read.size(); i++) {
            assertThat(read.get(i)).isEqualTo(i + PADDING);
        }
    }

    /**
     * The appender is closed after each cycle, so the only holder left on the last cycle is the pretoucher: it must
     * move to every new cycle and let go of the previous one, which a cleaner can then delete.
     */
    @Test
    void followsEachRollAndReleasesThePreviousCycle() throws InterruptedException {
        close();
        open(builder -> builder.rollCycle(TestRollCycles.TEST_SECONDLY));
        AtomicBoolean writing = new AtomicBoolean(true);
        Thread pretouching = new Thread(() -> {
            while (writing.get()) {
                pretoucher.pretouch();
                LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
            }
        });
        pretouching.start();

        List<Integer> writtenCycles = new ArrayList<>();
        int written = 0;
        try {
            for (int roll = 0; roll < 3; roll++) {
                try (ExcerptAppender appender = queue.createAppender()) {
                    for (int i = 0; i < 1000; i++) {
                        appender.writeText(written++ + PADDING);
                    }
                }
                int lastCycle = queue.lastCycle();
                writtenCycles.add(lastCycle);
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(holders(lastCycle)).isOne();
                    cycleHolders.keySet().stream()
                            .filter(cycle -> cycle < lastCycle)
                            .forEach(cycle -> assertThat(holders(cycle)).as("holders of cycle %d", cycle).isZero());
                });
                await().atMost(Duration.ofSeconds(5)).until(() -> queue.cycle() > lastCycle);
            }
        } finally {
            writing.set(false);
            pretouching.join();
        }

        assertThat(writtenCycles).doesNotHaveDuplicates();
        List<String> read = readAll();
        assertThat(read).hasSize(written);
        for (int i = 0; i < read.size(); i++) {
            assertThat(read.get(i)).isEqualTo(i + PADDING);
        }
        pretoucher.close();
        assertThat(holders(writtenCycles.get(2))).isZero();
    }

    @Test
    void theDistanceGrowsFromTheMinimumWithTheWriteRate() {
        pretoucher.close();
        pretoucher = new QueuePretoucher(queue, Duration.ofSeconds(1), 4096, new AtomicLong());
        try (ExcerptAppender appender = queue.createAppender()) {
            appender.writeText(PADDING);
            pretoucher.pretouch();
            assertThat(pretoucher.aheadBytes()).isEqualTo(4096);

            for (int round = 0; round < 10; round++) {
                for (int i = 0; i < 1000; i++) {
                    appender.writeText(PADDING);
                }
                LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
                pretoucher.pretouch();
            }
        }

        assertThat(pretoucher.aheadBytes()).isGreaterThan(1 << 20);
    }

    @Test
    void pretouchingAnEmptyQueueDoesNothing() {
        pretoucher.pretouch();

        try (ExcerptAppender appender = queue.createAppender()) {
            appender.writeText("first");
        }
        pretoucher.pretouch();

        try (ExcerptTailer tailer = queue.createTailer()) {
            assertThat(tailer.readText()).isEqualTo("first");
        }
    }

    @Test
    void pretouchingAfterCloseDoesNothing() {
        pretoucher.close();

        pretoucher.pretouch();

        try (ExcerptAppender appender = queue.createAppender()) {
            appender.writeText("first");
        }
    }

    @Test
    void thePagesAheadOfTheWriterReadAsZero() throws IOException {
        try (ExcerptAppender appender = queue.createAppender()) {
            for (int i = 0; i < 1000; i++) {
                appender.writeText(i + PADDING);
            }
        }
        for (int i = 0; i < 2 * AHEAD_BYTES / (PageToucher.MAX_PAGES_PER_CALL * 4096L); i++) {
            pretoucher.pretouch();
        }

        long endOfLastAppend = endOfLastAppend();
        try (RandomAccessFile file = new RandomAccessFile(lastCycleFile(), "r")) {
            file.seek(endOfLastAppend - 1);
            assertThat((char) file.readByte()).as("the last byte appended").isEqualTo('x');
            byte[] ahead = new byte[(int) AHEAD_BYTES];
            file.readFully(ahead);
            assertThat(ahead).containsOnly(0);
        }
    }

    /**
     * Counted on the writer's own thread, through the real pretouch thread. On tmpfs: on disk, writeback under
     * concurrent writes write-protects the touched pages again, and the writer faults on them anyway.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void theWriterTakesAlmostNoPageFault() throws Exception {
        assumeThat(Path.of("/dev/shm")).isDirectory();
        tmpfsDirectory = new File("/dev/shm/staffix-pretouch-" + System.nanoTime());
        long withoutPretouching = writerMinorFaults(false);

        long withPretouching = writerMinorFaults(true);

        assertThat(withPretouching).isLessThanOrEqualTo(withoutPretouching / 10);
    }

    private long writerMinorFaults(boolean pretouching) throws Exception {
        close();
        open(new File(tmpfsDirectory, String.valueOf(System.nanoTime())), UnaryOperator.identity());
        PretouchThread pretouchThread = new PretouchThread("test", Executors.defaultThreadFactory(),
                Duration.ofMillis(1));
        pretouchThread.add(this, pretoucher::pretouch);
        try (ExcerptAppender appender = queue.createAppender()) {
            appender.writeText(PADDING);
            if (pretouching) {
                pretouchThread.start();
                LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
            }
            long before = threadMinorFaults();
            for (int i = 0; i < 20_000; i++) {
                appender.writeText(PADDING);
                if (i % 20 == 0) {
                    LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
                }
            }
            return threadMinorFaults() - before;
        } finally {
            pretouchThread.stop(Deadline.of(Duration.ofSeconds(5)));
        }
    }

    private static long threadMinorFaults() throws IOException {
        String stat = Files.readString(Path.of("/proc/thread-self/stat"));
        // the fields after the command name, which is in parentheses and may hold spaces; minflt is the 8th
        String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
        return Long.parseLong(fields[7]);
    }

    private long endOfLastAppend() {
        SingleChronicleQueueStore store = queue.storeForCycle(queue.lastCycle(), queue.epoch(), false, null);
        try {
            long lastHeader = store.writePosition();
            try (ExcerptTailer tailer = queue.createTailer()) {
                tailer.toEnd();
                tailer.moveToIndex(tailer.index() - 1);
                try (DocumentContext dc = tailer.readingDocument()) {
                    return Math.max(lastHeader, dc.wire().bytes().readLimit());
                }
            }
        } finally {
            queue.closeStore(store);
        }
    }

    private File lastCycleFile() {
        SingleChronicleQueueStore store = queue.storeForCycle(queue.lastCycle(), queue.epoch(), false, null);
        try {
            return store.file();
        } finally {
            queue.closeStore(store);
        }
    }
}
