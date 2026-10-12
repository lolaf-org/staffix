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
import net.openhft.chronicle.wire.DocumentContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TestAsyncStoreThreads {

    FixSessionId fixSessionId;
    AsyncStoreThreads<TestAsyncEvent> asyncStoreThreads;
    AsyncStoreSettings asyncStoreSettings;
    PersistentQueue<TestAsyncEvent> persistentQueue;
    AsyncEventSerde<TestAsyncEvent> asyncEventSerde;

    private static java.util.List<String> readerThreadsStillAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("Staffix-async-store-reader-"))
                .collect(java.util.stream.Collectors.toList());
    }

    @BeforeEach
    void setup() {
        fixSessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "sender", "target");
        asyncStoreSettings = AsyncStoreSettings.builder()
                .asyncQueueDirectory("./target/chronicle-" + System.currentTimeMillis())
                .readerThreadMaxEventsProcessingPerStore(10)
                .build();

        persistentQueue = new PersistentQueue<>(fixSessionId);

        asyncStoreThreads = new AsyncStoreThreads<>(asyncStoreSettings, "test", new AsyncEventInstanceProvider<>() {
            @Override
            public TestAsyncEvent instanciateAsyncEvent(Consumer<TestAsyncEvent> releaseCallback) {
                return new TestAsyncEvent(releaseCallback, null);
            }
        });
        asyncEventSerde = new AsyncEventSerde<>() {
            @Override
            public void deserialize(DocumentContext documentContext, TestAsyncEvent deserializeTarget) {
                deserializeTarget.testString = documentContext.wire().getValueIn().readString();
            }

            @Override
            public void serialize(DocumentContext documentContext, TestAsyncEvent toSerialize) {
                documentContext.wire().getValueOut().writeString(toSerialize.testString);
            }
        };
    }

    @AfterEach
    void shutdown() {
        // reader threads must be stopped before the queue they are polling, as AsyncMessageStore.stopMe does
        asyncStoreThreads.stop(Deadline.of(Duration.ofSeconds(5)));
        persistentQueue.stop();
    }

    @Test
    void aRegisteredQueueIsDrainedIntoItsConsumer() {
        persistentQueue.start(asyncStoreSettings, "test");
        asyncStoreThreads.start();
        AtomicInteger consumed = new AtomicInteger();
        asyncStoreThreads.register(persistentQueue, event -> consumed.incrementAndGet(), asyncEventSerde);

        for (int i = 0; i < 1000; i++) {
            persistentQueue.offer(asyncEventSerde, new TestAsyncEvent(null, "test"));
        }

        await().untilAsserted(() -> assertThat(consumed).hasValue(1000));
        assertThat(persistentQueue.isEmpty()).isTrue();
    }

    @Test
    void stoppingRightAfterStartLeavesNoThreadBehind() {
        // start() returning does not mean the thread has been scheduled yet, and a stop() landing in that window used
        // to be undone by run() raising the running flag itself. What that leaves behind depends on the deadline: with
        // the unlimited one a store test kit uses, stop() joins for ever a thread looping on a flag nobody clears
        // again, which is how it wedged a full build; here, with a bounded deadline, the join gives up, the thread
        // reference is dropped, and the still raised flag makes the next start() a no-op so the following stop()
        // throws on a null thread. Either way this loop fails without the fix rather than passing quietly.
        for (int i = 0; i < 200; i++) {
            asyncStoreThreads.start();
            asyncStoreThreads.stop(Deadline.of(Duration.ofSeconds(5)));
        }

        await().untilAsserted(() -> assertThat(readerThreadsStillAlive())
                .as("reader threads left running after stop")
                .isEmpty());
    }

    @AllArgsConstructor
    private static final class TestAsyncEvent implements AsyncEvent {

        private final Consumer<TestAsyncEvent> releaseCallback;

        String testString;

        @Override
        public void release() {
            if (releaseCallback != null) {
                releaseCallback.accept(this);
            }
        }
    }

}
