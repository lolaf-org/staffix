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
package org.lolaf.staffix.api.ids;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSnowflakeId {

    // a fresh generator per test method (JUnit creates a new test instance per method), so the sequence state
    // never carries over between tests
    private final SnowflakeId generator = new SnowflakeId(42);

    @Test
    void testLayoutIsTimestampNodeSequence() {
        for (int i = 0; i < 1_000_000; i++) {
            long id = generator.nextId();

            assertThat(id).as("the sign bit is never set").isPositive();
            assertThat(SnowflakeId.nodeIdOf(id)).as("node id").isEqualTo(42);
            assertThat(SnowflakeId.sequenceOf(id)).as("sequence").isBetween(0, 4095);
        }
    }

    @Test
    void testTimestampIsCurrentForAFreshGenerator() {
        // only true below the 4096/ms ceiling: past it the timestamp deliberately runs ahead of the wall clock,
        // which is what testSequenceIncrementsWithinOneMillisecondThenBorrowsIntoTheTimestamp pins down
        long before = System.currentTimeMillis();
        long id = generator.nextId();
        long after = System.currentTimeMillis();

        assertThat(generator.timestampMillisOf(id)).isBetween(before, after);
    }

    @Test
    void testStrictlyIncreasing() {
        long prev = generator.nextId();
        for (int i = 0; i < 5_000_000; i++) {
            long next = generator.nextId();
            assertThat(next).as("id #%d must be strictly greater than its predecessor", i).isGreaterThan(prev);
            prev = next;
        }
    }

    @Test
    void testSequenceIncrementsWithinOneMillisecondThenBorrowsIntoTheTimestamp() {
        long fixedMillis = SnowflakeId.DEFAULT_EPOCH_MILLIS + 1234;
        SnowflakeId fixedClock = new SnowflakeId(7, SnowflakeId.DEFAULT_EPOCH_MILLIS, () -> fixedMillis);

        // the whole sequence space of that millisecond, in order
        for (int expectedSequence = 0; expectedSequence <= 4095; expectedSequence++) {
            long id = fixedClock.nextId();
            assertThat(SnowflakeId.sequenceOf(id)).isEqualTo(expectedSequence);
            assertThat(fixedClock.timestampMillisOf(id)).isEqualTo(fixedMillis);
            assertThat(SnowflakeId.nodeIdOf(id)).isEqualTo(7);
        }

        // the clock has not moved, so the next one borrows into the timestamp rather than repeating or blocking
        long overflowed = fixedClock.nextId();
        assertThat(SnowflakeId.sequenceOf(overflowed)).isZero();
        assertThat(fixedClock.timestampMillisOf(overflowed)).isEqualTo(fixedMillis + 1);
        assertThat(SnowflakeId.nodeIdOf(overflowed)).isEqualTo(7);
    }

    @Test
    void testClockGoingBackwardsNeverBreaksOrdering() {
        AtomicLong now = new AtomicLong(SnowflakeId.DEFAULT_EPOCH_MILLIS + 100_000);
        SnowflakeId movingClock = new SnowflakeId(1, SnowflakeId.DEFAULT_EPOCH_MILLIS, now::get);

        long prev = movingClock.nextId();
        now.addAndGet(-50_000); // a big NTP correction backwards
        for (int i = 0; i < 100_000; i++) {
            long next = movingClock.nextId();
            assertThat(next).as("id #%d after the clock stepped back", i).isGreaterThan(prev);
            prev = next;
        }
    }

    @Test
    void testDistinctNodesNeverCollide() {
        SnowflakeId nodeA = new SnowflakeId(1);
        SnowflakeId nodeB = new SnowflakeId(2);
        for (int i = 0; i < 500_000; i++) {
            long a = nodeA.nextId();
            long b = nodeB.nextId();
            assertThat(a).isNotEqualTo(b);
            assertThat(SnowflakeId.nodeIdOf(a)).isEqualTo(1);
            assertThat(SnowflakeId.nodeIdOf(b)).isEqualTo(2);
        }
    }

    @Test
    void testConcurrentGenerationIsUniqueAndOrderedPerThread() throws InterruptedException {
        int threads = 8;
        int perThread = 200_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentHashMap<Long, Boolean> seen = new ConcurrentHashMap<>(threads * perThread);
        AtomicReferenceArray<Throwable> failures = new AtomicReferenceArray<>(threads);

        for (int t = 0; t < threads; t++) {
            int threadIdx = t;
            pool.execute(() -> {
                try {
                    start.await();
                    long prev = 0;
                    for (int i = 0; i < perThread; i++) {
                        long id = generator.nextId();
                        assertThat(id).isGreaterThan(prev);
                        assertThat(SnowflakeId.nodeIdOf(id)).isEqualTo(42);
                        assertThat(seen.putIfAbsent(id, Boolean.TRUE)).isNull();
                        prev = id;
                    }
                } catch (Throwable e) {
                    failures.set(threadIdx, e);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        for (int t = 0; t < threads; t++) {
            assertThat(failures.get(t)).isNull();
        }
        assertThat(seen).hasSize(threads * perThread);
    }

    @Test
    void testAccessors() {
        assertThat(generator.nodeId()).isEqualTo(42);
        assertThat(generator.epochMillis()).isEqualTo(SnowflakeId.DEFAULT_EPOCH_MILLIS);

        SnowflakeId customEpoch = new SnowflakeId(0, 0L);
        assertThat(customEpoch.epochMillis()).isZero();
        assertThat(customEpoch.timestampMillisOf(customEpoch.nextId()))
                .isBetween(System.currentTimeMillis() - 1000, System.currentTimeMillis());
    }

    @Test
    void testConstructorRejectsInvalidArguments() {
        assertThatThrownBy(() -> new SnowflakeId(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeId(SnowflakeId.MAX_NODE_ID + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeId(0, System.currentTimeMillis() + 60_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeId(0, -1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeId(0, 0L, null)).isInstanceOf(NullPointerException.class);

        // the boundaries themselves are valid
        assertThat(new SnowflakeId(0).nodeId()).isZero();
        assertThat(new SnowflakeId(SnowflakeId.MAX_NODE_ID).nodeId()).isEqualTo(SnowflakeId.MAX_NODE_ID);
    }

    @Test
    void testSharedInstanceDefaultsToNodeZeroWithoutTheSystemProperty() {
        // the build sets no node id property, so the shared generator must fall back rather than fail
        assertThat(System.getProperty(SnowflakeId.NODE_ID_PROPERTY)).isNull();

        assertThat(SnowflakeId.instance()).isSameAs(SnowflakeId.instance());
        assertThat(SnowflakeId.instance().nodeId()).isEqualTo(SnowflakeId.DEFAULT_NODE_ID);
        assertThat(SnowflakeId.nodeIdOf(SnowflakeId.instance().nextId())).isEqualTo(SnowflakeId.DEFAULT_NODE_ID);
    }

    @Test
    void testResolveNodeId() {
        assertThat(SnowflakeId.resolveNodeId("0")).isZero();
        assertThat(SnowflakeId.resolveNodeId(" 17 ")).isEqualTo(17);
        assertThat(SnowflakeId.resolveNodeId("1023")).isEqualTo(SnowflakeId.MAX_NODE_ID);

        // a property that is set but unusable is a mistake, not an omission, and must fail loudly (an absent one is
        // handled before this method, see testSharedInstanceDefaultsToNodeZeroWithoutTheSystemProperty)
        assertThatThrownBy(() -> SnowflakeId.resolveNodeId(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SnowflakeId.resolveNodeId("  ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SnowflakeId.resolveNodeId("node-3")).isInstanceOf(IllegalStateException.class);

        // the range is not this method's business: a parseable but impossible node id is the constructor's to reject
        assertThat(SnowflakeId.resolveNodeId("1024")).isEqualTo(1024);
        assertThatThrownBy(() -> new SnowflakeId(SnowflakeId.resolveNodeId("1024")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeId(SnowflakeId.resolveNodeId("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
