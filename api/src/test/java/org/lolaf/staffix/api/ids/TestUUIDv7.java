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

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestUUIDv7 {

    // a fresh generator per test method (JUnit creates a new test instance per method), so the monotonic
    // state never carries over between tests
    private final UUIDv7 generator = new UUIDv7();

    @Test
    void testGenerateUuidStructure() {
        for (int i = 0; i < 1_000_000; i++) {
            UUID uuid = generator.generateUuid();
            assertThat(uuid.version()).as("version").isEqualTo(7);
            assertThat(uuid.variant()).as("variant").isEqualTo(2);
        }
    }

    @Test
    void testGenerateUuidMonotonic() {
        // for v7 UUIDs both msb and lsb are non-negative, so UUID.compareTo matches unsigned byte order
        UUID prev = generator.generateUuid();
        for (int i = 0; i < 2_000_000; i++) {
            UUID next = generator.generateUuid();
            assertThat(next.compareTo(prev))
                    .as("UUID #%d must be strictly greater than its predecessor", i)
                    .isEqualTo(1);
            prev = next;
        }
    }

    @Test
    void testGenerateMsbLsbFillsTheHolderInPlace() {
        MsbLsb msbLsb = new MsbLsb();
        UUID prev = null;
        for (int i = 0; i < 1_000_000; i++) {
            generator.generateMsbLsb(msbLsb);
            UUID uuid = new UUID(msbLsb.getMsb(), msbLsb.getLsb());
            assertThat(uuid.version()).as("version").isEqualTo(7);
            assertThat(uuid.variant()).as("variant").isEqualTo(2);
            if (prev != null) {
                assertThat(uuid.compareTo(prev)).as("UUID #%d must be strictly greater", i).isEqualTo(1);
            }
            prev = uuid;
        }
    }

    @Test
    void testTimestampIsCurrentForFreshGenerator() {
        long before = System.currentTimeMillis();
        long ts = generator.generateUuid().getMostSignificantBits() >>> 16;
        long after = System.currentTimeMillis();
        assertThat(ts).isBetween(before, after);
    }

    @Test
    void testConcurrentGenerationIsUniqueAndOrderedPerThread() throws InterruptedException {
        int threads = 8;
        int perThread = 200_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>(threads * perThread);
        AtomicReferenceArray<Throwable> failures = new AtomicReferenceArray<>(threads);

        for (int t = 0; t < threads; t++) {
            int threadIdx = t;
            pool.execute(() -> {
                try {
                    start.await();
                    UUID prev = null;
                    for (int i = 0; i < perThread; i++) {
                        UUID uuid = generator.generateUuid();
                        assertThat(uuid.version()).isEqualTo(7);
                        assertThat(uuid.variant()).isEqualTo(2);
                        if (prev != null) {
                            assertThat(uuid.compareTo(prev)).isPositive();
                        }
                        prev = uuid;
                        assertThat(seen.putIfAbsent(uuid.toString(), Boolean.TRUE)).isNull();
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
    void testNamedInstances() {
        assertThat(UUIDv7.instance("one")).isSameAs(UUIDv7.instance("one"));
        assertThat(UUIDv7.instance("one")).isNotSameAs(UUIDv7.instance("two"));
        assertThat(UUIDv7.instance("one")).isNotSameAs(UUIDv7.instance());

        // each id is its own strictly increasing sequence
        UUID first = UUIDv7.instance("one").generateUuid();
        assertThat(UUIDv7.instance("one").generateUuid().compareTo(first)).isEqualTo(1);

        assertThatThrownBy(() -> UUIDv7.instance(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testStaticConvenienceDelegatesToSharedInstance() {
        assertThat(UUIDv7.instance()).isSameAs(UUIDv7.instance());

        UUID uuid = UUIDv7.randomUuid();
        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void testCustomRandomSourceIsUsed() {
        // a constant-zero random source leaves all 62 rand_b bits cleared, only the variant bits remain set
        UUIDv7 zeroRand = new UUIDv7(() -> 0L);
        UUID uuid = zeroRand.generateUuid();
        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.getLeastSignificantBits()).isEqualTo(0x8000000000000000L);
    }

    @Test
    void testCustomClockIsUsed() {
        long fixedMillis = 0x0190B3C09C7AL;
        UUIDv7 fixedClock = new UUIDv7(() -> 0L, () -> fixedMillis);
        assertThat(fixedClock.generateUuid().getMostSignificantBits() >>> 16).isEqualTo(fixedMillis);
    }

    @Test
    void testConstructorRejectsNullRandom() {
        assertThatThrownBy(() -> new UUIDv7((LongSupplier) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testResolveRandom() {
        assertThat(UUIDv7.resolveRandom(null)).isNotNull();
        assertThat(UUIDv7.resolveRandom("")).isNotNull();
        assertThat(UUIDv7.resolveRandom("threadLocal")).isNotNull();
        assertThat(UUIDv7.resolveRandom("THREADLOCAL")).isNotNull();
        assertThat(UUIDv7.resolveRandom("secure")).isNotNull();
        LongSupplier fromClass = UUIDv7.resolveRandom("java.util.Random");
        assertThat(fromClass).isNotNull();
        fromClass.getAsLong(); // must not throw
        assertThatThrownBy(() -> UUIDv7.resolveRandom("not.a.real.Class"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> UUIDv7.resolveRandom("java.lang.String"))
                .isInstanceOf(IllegalStateException.class);
    }
}
