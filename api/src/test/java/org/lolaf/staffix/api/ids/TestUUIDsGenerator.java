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

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestUUIDsGenerator {

    // a fresh generator per test method (JUnit creates a new test instance per method), so neither the v7
    // sequence nor the thread local instances carry over between tests
    private final UUIDsGenerator generator = new UUIDsGenerator();

    // java.util.UUID.randomUUID(), minus the random source: version 4 in the high nibble of byte 6, variant 10 in
    // the top bits of byte 8, everything else straight from the input
    private static UUID randomUuidTheJdkWay(byte[] randomBytes) {
        byte[] data = randomBytes.clone();
        data[6] &= 0x0f;
        data[6] |= 0x40;
        data[8] &= 0x3f;
        data[8] |= (byte) 0x80;
        return new UUID(longAt(data, 0), longAt(data, 8));
    }

    private static long longAt(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFFL);
        }
        return value;
    }

    // the long that 8 copies of one byte assemble into, i.e. what a fill(dst, b) source yields for either half
    private static long repeatedByte(int b) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (b & 0xFFL);
        }
        return value;
    }

    private static long v4Msb(long random) {
        return (random & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
    }

    private static long v4Lsb(long random) {
        return (random & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    }

    @Test
    void testNewV7StructureAndFreshInstances() {
        UUID previous = generator.newV7();
        for (int i = 0; i < 1_000_000; i++) {
            UUID uuid = generator.newV7();
            assertThat(uuid.version()).as("version").isEqualTo(7);
            assertThat(uuid.variant()).as("variant").isEqualTo(2);
            assertThat(uuid).as("a fresh instance per call").isNotSameAs(previous);
            assertThat(uuid.compareTo(previous)).as("UUID #%d must be strictly greater", i).isEqualTo(1);
            previous = uuid;
        }
    }

    @Test
    void testNewV4StructureAndUniqueness() {
        Set<UUID> seen = new HashSet<>();
        UUID previous = generator.newV4();
        for (int i = 0; i < 1_000_000; i++) {
            UUID uuid = generator.newV4();
            assertThat(uuid.version()).as("version").isEqualTo(4);
            assertThat(uuid.variant()).as("variant").isEqualTo(2);
            assertThat(uuid).as("a fresh instance per call").isNotSameAs(previous);
            assertThat(seen.add(uuid)).as("UUID #%d must be unique", i).isTrue();
            previous = uuid;
        }
    }

    @Test
    void testThreadLocalV7ReusesOneInstanceAndStaysOrdered() {
        UUID first = generator.threadLocalV7();
        UUID previous = new UUID(first.getMostSignificantBits(), first.getLeastSignificantBits());
        for (int i = 0; i < 1_000_000; i++) {
            UUID uuid = generator.threadLocalV7();
            assertThat(uuid).as("the same reused instance on this thread").isSameAs(first);
            assertThat(uuid.version()).as("version").isEqualTo(7);
            assertThat(uuid.variant()).as("variant").isEqualTo(2);
            assertThat(uuid.compareTo(previous)).as("UUID #%d must be strictly greater", i).isEqualTo(1);
            previous = new UUID(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
        }
    }

    @Test
    void testThreadLocalV4ReusesOneInstanceAndStaysUnique() {
        Set<UUID> seen = new HashSet<>();
        UUID first = generator.threadLocalV4();
        for (int i = 0; i < 1_000_000; i++) {
            UUID uuid = generator.threadLocalV4();
            assertThat(uuid).as("the same reused instance on this thread").isSameAs(first);
            assertThat(uuid.version()).as("version").isEqualTo(4);
            assertThat(uuid.variant()).as("variant").isEqualTo(2);
            // copied, since the reused instance mutates under the set
            assertThat(seen.add(new UUID(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits())))
                    .as("UUID #%d must be unique", i).isTrue();
        }
    }

    @Test
    void testThreadLocalFlavoursDoNotOverwriteEachOther() {
        UUID v7 = generator.threadLocalV7();
        UUID v4 = generator.threadLocalV4();
        UUID raw = generator.threadLocalUuid(1L, 2L);

        assertThat(v7).isNotSameAs(v4).isNotSameAs(raw);
        assertThat(v4).isNotSameAs(raw);
        // each flavour owns its own slot, so the values taken above are still the ones handed out
        assertThat(v7.version()).isEqualTo(7);
        assertThat(v4.version()).isEqualTo(4);
        assertThat(raw.getMostSignificantBits()).isEqualTo(1L);
        assertThat(raw.getLeastSignificantBits()).isEqualTo(2L);
    }

    @Test
    void testThreadLocalUuidWritesBothHalves() {
        UUID uuid = generator.threadLocalUuid(0x0190B3C09C7A7B3EL, 0x8F21123456789ABCL);
        assertThat(uuid).hasToString("0190b3c0-9c7a-7b3e-8f21-123456789abc");

        UUID again = generator.threadLocalUuid(-1L, -1L);
        assertThat(again).isSameAs(uuid);
        assertThat(again).hasToString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    }

    @Test
    void testThreadLocalInstancesArePerThread() throws Exception {
        int threads = 8;
        int perThread = 100_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>(threads * perThread);
        AtomicReferenceArray<UUID> instances = new AtomicReferenceArray<>(threads);
        AtomicReferenceArray<Throwable> failures = new AtomicReferenceArray<>(threads);

        for (int t = 0; t < threads; t++) {
            int threadIdx = t;
            pool.execute(() -> {
                try {
                    start.await();
                    instances.set(threadIdx, generator.threadLocalV7());
                    for (int i = 0; i < perThread; i++) {
                        UUID uuid = generator.threadLocalV7();
                        assertThat(uuid).isSameAs(instances.get(threadIdx));
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
        for (int t = 0; t < threads; t++) {
            for (int other = t + 1; other < threads; other++) {
                assertThat(instances.get(t)).as("threads %d and %d", t, other).isNotSameAs(instances.get(other));
            }
        }
    }

    @Test
    void testInstancesAreSharedPerIdAndIsolatedAcrossIds() {
        assertThat(UUIDsGenerator.instance()).isSameAs(UUIDsGenerator.instance());
        assertThat(UUIDsGenerator.instance("one")).isSameAs(UUIDsGenerator.instance("one"));
        assertThat(UUIDsGenerator.instance("one")).isNotSameAs(UUIDsGenerator.instance("two"));
        assertThat(UUIDsGenerator.instance("one")).isNotSameAs(UUIDsGenerator.instance());

        // isolated thread local slots: one id never overwrites the value another one handed out
        UUID one = UUIDsGenerator.instance("one").threadLocalV7();
        UUID two = UUIDsGenerator.instance("two").threadLocalV7();
        assertThat(one).isNotSameAs(two);
        assertThat(one).isNotEqualTo(two);

        assertThatThrownBy(() -> UUIDsGenerator.instance(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testGlobalInstanceSharesTheV7SequenceOfUuidV7() {
        // both draw from UUIDv7.instance(), so the sequence stays strictly increasing across the two APIs
        UUID fromGenerator = UUIDsGenerator.instance().newV7();
        UUID fromUuidV7 = UUIDv7.randomUuid();
        assertThat(fromUuidV7.compareTo(fromGenerator)).isEqualTo(1);
        assertThat(UUIDsGenerator.instance().newV7().compareTo(fromUuidV7)).isEqualTo(1);
    }

    @Test
    void testNamedInstanceSharesTheV7SequenceOfTheSameId() {
        String id = "shared-sequence";
        UUIDv7 sequence = UUIDv7.instance(id);
        UUIDsGenerator uuids = UUIDsGenerator.instance(id);

        // the same id means one ordered sequence, whichever API asks for the next value
        UUID fromGenerator = uuids.newV7();
        UUID fromSequence = sequence.generateUuid();
        assertThat(fromSequence.compareTo(fromGenerator)).isEqualTo(1);
        assertThat(uuids.threadLocalV7().compareTo(fromSequence)).isEqualTo(1);

        // and another id is a sequence of its own, unrelated to this one
        assertThat(UUIDsGenerator.instance("another-sequence")).isNotSameAs(uuids);
        assertThat(UUIDv7.instance("another-sequence")).isNotSameAs(sequence);
    }

    @Test
    void testCustomSourcesAreUsed() {
        UUIDv7 v7 = new UUIDv7(() -> 0L);
        UUIDsGenerator custom = new UUIDsGenerator(v7, dst -> Arrays.fill(dst, (byte) 0x00));

        // a constant-zero v4 source leaves every random bit cleared, only version and variant remain set
        UUID v4 = custom.newV4();
        assertThat(v4.getMostSignificantBits()).isEqualTo(0x0000000000004000L);
        assertThat(v4.getLeastSignificantBits()).isEqualTo(0x8000000000000000L);
        assertThat(v4.version()).isEqualTo(4);
        assertThat(v4.variant()).isEqualTo(2);
        assertThat(custom.threadLocalV4()).isEqualTo(v4);

        // the v7 rand_b bits come from the supplied UUIDv7, whose own source is zeroed too
        assertThat(custom.newV7().getLeastSignificantBits()).isEqualTo(0x8000000000000000L);
    }

    @Test
    void testBothHalvesOfTheV4SourceAreUsed() {
        // an all-ones source: everything but the version nibble and the two variant bits must survive
        UUIDsGenerator custom = new UUIDsGenerator(new UUIDv7(), dst -> Arrays.fill(dst, (byte) 0xFF));

        for (UUID v4 : List.of(custom.newV4(), custom.threadLocalV4())) {
            assertThat(v4.getMostSignificantBits()).isEqualTo(0xFFFFFFFFFFFF4FFFL);
            assertThat(v4.getLeastSignificantBits()).isEqualTo(0xBFFFFFFFFFFFFFFFL);
            assertThat(v4.version()).isEqualTo(4);
            assertThat(v4.variant()).isEqualTo(2);
        }
    }

    @Test
    void testTheV4SourceIsAskedOncePerUuid() {
        // one draw fills all 16 bytes: a source that changes on every call must not be split across two ids
        AtomicInteger calls = new AtomicInteger();
        UUIDsGenerator custom = new UUIDsGenerator(new UUIDv7(), dst -> {
            byte counter = (byte) calls.incrementAndGet();
            Arrays.fill(dst, counter);
        });

        UUID first = custom.newV4();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(first.getMostSignificantBits()).isEqualTo(v4Msb(repeatedByte(1)));
        assertThat(first.getLeastSignificantBits()).isEqualTo(v4Lsb(repeatedByte(1)));

        UUID second = custom.newV4();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(second.getMostSignificantBits()).isEqualTo(v4Msb(repeatedByte(2)));

        custom.threadLocalV4();
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void testV4LayoutIsTheOneJavaRandomUuidProduces() {
        // the reference is java.util.UUID.randomUUID's own body: 16 random bytes, byte 6 and byte 8 patched, then
        // assembled big-endian. Feeding our generator the very same bytes must produce the very same UUID.
        Random rdm = new Random();
        byte[] randomBytes = new byte[16];
        UUIDsGenerator custom = new UUIDsGenerator(new UUIDv7(),
                dst -> System.arraycopy(randomBytes, 0, dst, 0, randomBytes.length));

        for (int i = 0; i < 1_000_000; i++) {
            rdm.nextBytes(randomBytes);

            UUID expected = randomUuidTheJdkWay(randomBytes);

            assertThat(custom.newV4()).as("newV4 of %s", expected).isEqualTo(expected);
            assertThat(custom.threadLocalV4()).as("threadLocalV4 of %s", expected).isEqualTo(expected);
        }
    }

    @Test
    void testDefaultV4SourceVariesEveryRandomBitAndFixesTheOtherSix() {
        // catches an assembly bug the structural assertions cannot see: a half dropped, duplicated, or byte-swapped
        // would leave whole bit positions stuck. Only the 4 version bits and the 2 variant bits may be constant.
        long msbSeen0 = 0;
        long msbSeen1 = 0;
        long lsbSeen0 = 0;
        long lsbSeen1 = 0;
        for (int i = 0; i < 10_000; i++) {
            UUID uuid = generator.newV4();
            msbSeen0 |= ~uuid.getMostSignificantBits();
            msbSeen1 |= uuid.getMostSignificantBits();
            lsbSeen0 |= ~uuid.getLeastSignificantBits();
            lsbSeen1 |= uuid.getLeastSignificantBits();
        }

        // a bit that took both values is set in both masks; the version nibble and the variant bits are the only
        // ones allowed to be stuck
        assertThat(msbSeen0 & msbSeen1).as("msb bits that varied").isEqualTo(0xFFFFFFFFFFFF0FFFL);
        assertThat(lsbSeen0 & lsbSeen1).as("lsb bits that varied").isEqualTo(0x3FFFFFFFFFFFFFFFL);
        // and those six are stuck at the version 4 / variant 10 values, never anything else
        assertThat(msbSeen1 & ~0xFFFFFFFFFFFF0FFFL).isEqualTo(0x0000000000004000L);
        assertThat(lsbSeen1 & ~0x3FFFFFFFFFFFFFFFL).isEqualTo(0x8000000000000000L);
    }

    @Test
    void testDefaultV4SourceDrawsTwoIndependentHalves() {
        // the stuck-bit test above still passes if the two halves come from the same 64 bits, which would quietly
        // halve the entropy and correlate them. Compare the bits no patch touches in either half instead.
        long untouchedByBothPatches = 0x0000FFFFFFFF0FFFL;
        for (int i = 0; i < 10_000; i++) {
            UUID uuid = generator.newV4();
            assertThat(uuid.getMostSignificantBits() & untouchedByBothPatches)
                    .as("the two halves of %s must be independent draws", uuid)
                    .isNotEqualTo(uuid.getLeastSignificantBits() & untouchedByBothPatches);
        }
    }

    @Test
    void testAnyJdkRandomApiPlugsInAsAMethodReference() {
        // the point of RandomBytesSource's shape: java.util.Random, SecureRandom and (on JDK 17+) RandomGenerator
        // all declare nextBytes(byte[]), so none of them needs an adapter
        UUIDsGenerator fromRandom = new UUIDsGenerator(new UUIDv7(), new Random(42)::nextBytes);
        UUIDsGenerator fromSecureRandom = new UUIDsGenerator(new UUIDv7(), new SecureRandom()::nextBytes);

        for (UUIDsGenerator generatorUnderTest : List.of(fromRandom, fromSecureRandom)) {
            UUID v4 = generatorUnderTest.newV4();
            assertThat(v4.version()).isEqualTo(4);
            assertThat(v4.variant()).isEqualTo(2);
        }

        // and a seeded source is reproducible, which is the other reason to allow a non-crypto one
        assertThat(new UUIDsGenerator(new UUIDv7(), new Random(7)::nextBytes).newV4())
                .isEqualTo(new UUIDsGenerator(new UUIDv7(), new Random(7)::nextBytes).newV4());
    }

    @Test
    void testConstructorRejectsNullSources() {
        assertThatThrownBy(() -> new UUIDsGenerator(null, new SecureRandom()::nextBytes))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new UUIDsGenerator(new UUIDv7(), (RandomBytesSource) null))
                .isInstanceOf(NullPointerException.class);
    }
}
