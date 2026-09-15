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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TestAsyncStoreThreads {

    FixSessionId fixSessionId;
    QueueSizeThresholdsListener queueSizeThresholdsListener;
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
        queueSizeThresholdsListener = mock(QueueSizeThresholdsListener.class);
        asyncStoreSettings = AsyncStoreSettings.builder()
                .asyncQueueDirectory("./target/chronicle-" + System.currentTimeMillis())
                .queueSizeThresholdsListener(queueSizeThresholdsListener)
                .readerThreadMaxEventsProcessingPerStore(10)
                .queueSizeThresholdsSettings(AsyncStoreSettings.QueueSizeThresholdsSettings.builder()
                        .critical(500)
                        .warn(300)
                        .normal(100)
                        // The reader evaluates the thresholds on the same loop that drains the queue, and only
                        // notifies once an interval has gone by, so a band is reported only if the queue happens to
                        // be inside it when a notification is due. Sampling every 50ms rather than every second
                        // takes tens of samples across the drain instead of one or two, so each band is seen.
                        .evaluationInterval(Duration.ofMillis(50))
                        .build())
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
    void testQueueSizeThresholdsAreTriggered() {
        persistentQueue.start(asyncStoreSettings, "test");
        asyncStoreThreads.start();

        // A consumer that costs something to call, which puts a ceiling on how fast the queue can drain that this
        // test sets rather than the machine it runs on. Left to run flat out the reader empties the 1000 events
        // below in well under one evaluation interval on a fast box, and the queue is already past CRITICAL - and
        // sometimes past WARNING too - by the time anything is looked at.
        Consumer<TestAsyncEvent> slowConsumer = event -> LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        asyncStoreThreads.register(fixSessionId, persistentQueue, slowConsumer, asyncEventSerde);

        for (int i = 0; i < 1000; i++) {
            persistentQueue.offer(asyncEventSerde, new TestAsyncEvent(null, "test"));
        }

        // CRITICAL and WARNING are re-notified every evaluationInterval for as long as the queue stays above the
        // threshold, so how many times they are seen only tells how long the reader took to drain below it, which is a
        // property of the machine and not of the code under test. Only the fact that each is reached is asserted here.
        await().untilAsserted(() -> verify(queueSizeThresholdsListener, atLeast(1))
                .onQueueSizeThresholdReached(eq(fixSessionId), eq(QueueSizeThresholdsListener.QueueSizeThreshold.CRITICAL), anyInt(), anyInt()));
        await().untilAsserted(() -> verify(queueSizeThresholdsListener, atLeast(1))
                .onQueueSizeThresholdReached(eq(fixSessionId), eq(QueueSizeThresholdsListener.QueueSizeThreshold.WARNING), anyInt(), anyInt()));
        // NORMAL, on the other hand, is notified on the transition only (no re-notification), and nothing is offered
        // past this point, so it is reached exactly once and staying with the default times(1) is timing independent.
        await().untilAsserted(() -> verify(queueSizeThresholdsListener)
                .onQueueSizeThresholdReached(eq(fixSessionId), eq(QueueSizeThresholdsListener.QueueSizeThreshold.NORMAL), anyInt(), anyInt()));

        reset(queueSizeThresholdsListener);
        for (int j = 0; j < 3; j++) {
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
            for (int i = 0; i < 90; i++) {
                persistentQueue.offer(asyncEventSerde, new TestAsyncEvent(null, "test"));
            }
        }

        verify(queueSizeThresholdsListener, never())
                .onQueueSizeThresholdReached(eq(fixSessionId), eq(QueueSizeThresholdsListener.QueueSizeThreshold.NORMAL), anyInt(), anyInt());
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
