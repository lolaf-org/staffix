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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TestPretouchThread {

    private static final String THREAD_NAME = "Staffix-async-queue-pretoucher-test";

    final PretouchThread pretouchThread = new PretouchThread("test", Executors.defaultThreadFactory(),
            Duration.ofMillis(1));

    @AfterEach
    void shutdown() {
        pretouchThread.stop(Deadline.of(Duration.ofSeconds(5)));
    }

    private static boolean threadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().equals(THREAD_NAME));
    }

    @Test
    void pretouchesEachAddedQueueUntilRemoved() {
        AtomicLong calls = new AtomicLong();
        Object owner = new Object();
        pretouchThread.add(owner, calls::incrementAndGet);
        pretouchThread.start();

        await().atMost(Duration.ofSeconds(5)).until(() -> calls.get() >= 3);

        pretouchThread.remove(owner);
        long removedAt = calls.get();
        await().pollDelay(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(calls.get()).isLessThanOrEqualTo(removedAt + 1));
    }

    @Test
    void aQueueSharedByTwoOwnersStaysUntilBothAreRemoved() {
        AtomicLong calls = new AtomicLong();
        Pretouchable queue = calls::incrementAndGet;
        Object firstOwner = new Object();
        Object secondOwner = new Object();
        pretouchThread.add(firstOwner, queue);
        pretouchThread.add(secondOwner, queue);
        pretouchThread.remove(firstOwner);
        pretouchThread.start();

        await().atMost(Duration.ofSeconds(5)).until(() -> calls.get() >= 3);

        pretouchThread.remove(secondOwner);
        long removedAt = calls.get();
        await().pollDelay(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(calls.get()).isLessThanOrEqualTo(removedAt + 1));
    }

    /**
     * An interrupt landing while a queue maps a new chunk would close the file channel the writer shares.
     */
    @Test
    void stopDoesNotInterruptTheThread() {
        AtomicBoolean pretouching = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        pretouchThread.add(new Object(), () -> {
            pretouching.set(true);
            long end = System.nanoTime() + Duration.ofMillis(200).toNanos();
            while (System.nanoTime() < end) {
                if (Thread.currentThread().isInterrupted()) {
                    interrupted.set(true);
                }
            }
        });
        pretouchThread.start();
        await().atMost(Duration.ofSeconds(5)).untilTrue(pretouching);

        pretouchThread.stop(Deadline.of(Duration.ofSeconds(5)));

        assertThat(threadAlive()).isFalse();
        assertThat(interrupted).isFalse();
    }

    @Test
    void aZeroIntervalStartsNoThread() {
        PretouchThread off = new PretouchThread("test", Executors.defaultThreadFactory(), Duration.ZERO);
        AtomicLong calls = new AtomicLong();
        off.add(new Object(), calls::incrementAndGet);

        off.start();

        assertThat(threadAlive()).isFalse();
        off.stop(Deadline.of(Duration.ofSeconds(5)));
        assertThat(calls).hasValue(0);
    }

    @Test
    void stopEndsTheThread() {
        pretouchThread.start();
        await().atMost(Duration.ofSeconds(5)).until(TestPretouchThread::threadAlive);

        pretouchThread.stop(Deadline.of(Duration.ofSeconds(5)));

        assertThat(threadAlive()).isFalse();
    }
}
