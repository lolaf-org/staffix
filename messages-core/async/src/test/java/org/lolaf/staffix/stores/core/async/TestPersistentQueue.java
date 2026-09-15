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

import lombok.AllArgsConstructor;
import net.openhft.chronicle.core.io.BackgroundResourceReleaser;
import net.openhft.chronicle.queue.rollcycles.TestRollCycles;
import net.openhft.chronicle.wire.DocumentContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

class TestPersistentQueue {

    FixSessionId fixSessionId;
    AsyncStoreSettings asyncStoreSettings;
    PersistentQueue<TestingEvent> persistentQueue;
    TestingEvent testingEvent;
    AsyncEventSerde<TestingEvent> asyncEventSerde;

    private static File[] queueFiles(File fixSessionDir) {
        return fixSessionDir.listFiles(f -> !f.getName().contains("metadata"));
    }

    @BeforeEach
    void setup() {
        fixSessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "sender", "target");
        asyncStoreSettings = AsyncStoreSettings.builder()
                .asyncQueueDirectory("./target/chronicle-" + System.currentTimeMillis())
                .build();

        persistentQueue = new PersistentQueue<>(fixSessionId);

        testingEvent = new TestingEvent("test");

        asyncEventSerde = new AsyncEventSerde<>() {
            TestingEvent event;

            @Override
            public void deserialize(DocumentContext documentContext, TestingEvent testingEvent) {
                testingEvent.value = documentContext.wire().getValueIn().readString();
            }

            @Override
            public void serialize(DocumentContext documentContext, TestingEvent testingEvent) {
                documentContext.wire().write().writeString(testingEvent.value);
            }
        };
    }

    @AfterEach
    void shutdown() {
        persistentQueue.stop();
    }

    @Test
    void testStoreFilesCleanupWorks() {
        File chronicleDir = new File("./target/chronicle-" + System.currentTimeMillis());
        File fixSessionDir = new File(chronicleDir, "test-test");

        asyncStoreSettings = AsyncStoreSettings.builder()
                .asyncQueueDirectory(chronicleDir.getPath())
                .rollCycleProvider(sid -> TestRollCycles.TEST_SECONDLY)
                .build();

        persistentQueue.start(asyncStoreSettings, "test");

        // one event per roll cycle, so each one ends up in its own queue file
        for (int i = 0; i < 5; i++) {
            persistentQueue.offer(asyncEventSerde, new TestingEvent("test-" + i));
            LockSupport.parkNanos(Duration.ofMillis(1100).toNanos());
        }

        persistentQueue.stop();

        assertThat(queueFiles(fixSessionDir)).hasSize(5);

        persistentQueue.start(asyncStoreSettings, "test");

        assertThat(persistentQueue.getSize()).isEqualTo(5);

        // no cycle file may be deleted before the tailer polled it, every offered event must still be readable
        TestingEvent testingRead = new TestingEvent(null);
        List<String> polled = new ArrayList<>();
        while (persistentQueue.poll(asyncEventSerde, testingRead)) {
            polled.add(testingRead.value);
        }
        assertThat(polled).containsExactly("test-0", "test-1", "test-2", "test-3", "test-4");
        assertThat(persistentQueue.getSize()).isZero();

        // everything has been polled, files should be deleted and only last one kept
        BackgroundResourceReleaser.releasePendingResources();
        assertThat(queueFiles(fixSessionDir)).hasSize(1);
        persistentQueue.stop();

        persistentQueue.start(asyncStoreSettings, "test");

        assertThat(persistentQueue.getSize()).isZero();
        assertThat(persistentQueue.poll(asyncEventSerde, testingRead)).isFalse();
        assertThat(queueFiles(fixSessionDir)).hasSize(1);
    }

    /**
     * Restarting on a backlog makes {@link PersistentQueue#start} probe the queue size, and Chronicle answers that with
     * a release notification for the cycle the backlog sits in, ie the live one, delivered on its background releaser
     * thread once the queue is started again. That cycle cannot be deleted, and with a roll cycle of an hour the tailer
     * will not move past it for the rest of the hour, so it stays pending: draining the backlog must not sweep the
     * cleaner once per event.
     */
    @Test
    void testReleasedLiveCycleIsNotSweptOnEveryPoll() {
        File chronicleDir = new File("./target/chronicle-" + System.currentTimeMillis());
        File fixSessionDir = new File(chronicleDir, "test-test");

        // the default roll cycle is hourly, so every event below lands in the live cycle and no roll can occur
        asyncStoreSettings = AsyncStoreSettings.builder()
                .asyncQueueDirectory(chronicleDir.getPath())
                .build();

        persistentQueue.start(asyncStoreSettings, "test");
        for (int i = 0; i < 500; i++) {
            persistentQueue.offer(asyncEventSerde, new TestingEvent("test-" + i));
        }
        persistentQueue.stop();

        persistentQueue = new PersistentQueue<>(fixSessionId);
        persistentQueue.start(asyncStoreSettings, "test");
        assertThat(persistentQueue.getSize()).isEqualTo(500);

        // deliver the release of the live cycle the start-up size probe queued, as Chronicle does asynchronously
        BackgroundResourceReleaser.releasePendingResources();

        TestingEvent testingRead = new TestingEvent(null);
        int polled = 0;
        while (persistentQueue.poll(asyncEventSerde, testingRead)) {
            assertThat(testingRead.value).isEqualTo("test-" + polled++);
        }
        assertThat(polled).isEqualTo(500);

        // the released cycle is the live one, no poll may have swept for it and its file must still be there
        assertThat(persistentQueue.pendingDeletionSweepsCount()).isZero();
        assertThat(queueFiles(fixSessionDir)).hasSize(1);
    }

    @Test
    void testSerializationDeserializationWorks() {

        persistentQueue.start(asyncStoreSettings, "test");

        TestingEvent testingWrite = new TestingEvent("test1");
        TestingEvent testingRead = new TestingEvent(null);

        persistentQueue.offer(asyncEventSerde, testingWrite);

        assertThat(persistentQueue.poll(asyncEventSerde, testingRead)).isTrue();

        assertThat(testingRead.value).isEqualTo("test1");
    }

    @Test
    void testSizeCorrectlyComputed() {

        persistentQueue.start(asyncStoreSettings, "test");

        assertThat(persistentQueue.getSize()).isZero();

        persistentQueue.offer(asyncEventSerde, testingEvent);

        assertThat(persistentQueue.getSize()).isEqualTo(1);
        assertThat(persistentQueue.isEmpty()).isFalse();

        persistentQueue.offer(asyncEventSerde, testingEvent);
        persistentQueue.offer(asyncEventSerde, testingEvent);

        assertThat(persistentQueue.getSize()).isEqualTo(3);


        assertThat(persistentQueue.poll(asyncEventSerde, testingEvent)).isTrue();
        assertThat(persistentQueue.getSize()).isEqualTo(2);

        assertThat(persistentQueue.poll(asyncEventSerde, testingEvent)).isTrue();
        assertThat(persistentQueue.getSize()).isEqualTo(1);

        assertThat(persistentQueue.poll(asyncEventSerde, testingEvent)).isTrue();
        assertThat(persistentQueue.getSize()).isZero();
        assertThat(persistentQueue.isEmpty()).isTrue();

        assertThat(persistentQueue.poll(asyncEventSerde, testingEvent)).isFalse();
    }

    @Test
    void testSizeCorrectlyComputedOnRestartWithEmptyQueue() {

        persistentQueue.start(asyncStoreSettings, "test");
        persistentQueue.stop();

        assertThat(persistentQueue.getSize()).isZero();

        persistentQueue.start(asyncStoreSettings, "test");

        assertThat(persistentQueue.getSize()).isZero();
    }

    @Test
    void testSizeCorrectlyComputedOnRestartWithNotEmptyQueue() {

        persistentQueue.start(asyncStoreSettings, "test");

        persistentQueue.offer(asyncEventSerde, testingEvent);
        persistentQueue.offer(asyncEventSerde, testingEvent);

        persistentQueue.stop();

        persistentQueue.start(asyncStoreSettings, "test");

        assertThat(persistentQueue.getSize()).isEqualTo(2);
    }

    @Test
    void testSizeCorrectlyComputedOnRestartWithFullyProcessedQueueQueue() {

        persistentQueue.start(asyncStoreSettings, "test");

        persistentQueue.offer(asyncEventSerde, testingEvent);
        persistentQueue.offer(asyncEventSerde, testingEvent);

        assertThat(persistentQueue.poll(asyncEventSerde, testingEvent)).isTrue();
        assertThat(persistentQueue.poll(asyncEventSerde, testingEvent)).isTrue();
        assertThat(persistentQueue.getSize()).isZero();

        persistentQueue.stop();

        persistentQueue.start(asyncStoreSettings, "test");

        assertThat(persistentQueue.getSize()).isZero();
    }

    @AllArgsConstructor
    public static final class TestingEvent implements AsyncEvent {

        public String value;

        @Override
        public void release() {
            // nothing to do
        }
    }
}