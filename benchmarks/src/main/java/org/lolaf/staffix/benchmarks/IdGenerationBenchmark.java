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
package org.lolaf.staffix.benchmarks;

import org.lolaf.staffix.api.ids.SnowflakeId;
import org.lolaf.staffix.api.ids.UUIDsGenerator;
import org.lolaf.staffix.api.ids.UUIDv7;
import org.openjdk.jmh.annotations.*;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Cost of minting one identifier, which application code does once per outgoing message.
 *
 * <p>{@link #jdkRandomUuid()} is the baseline everybody writes first; the rest are what this library offers instead.
 * The UUID benchmarks run once per {@link InstanceMode}: {@code NEW_INSTANCE} allocates a {@link UUID} per id as any
 * caller would expect, {@code THREAD_LOCAL} rewrites this thread's reused instance in place. A Snowflake id is a
 * {@code long}, so it has no such choice to make.
 *
 * <p>Version 4 additionally runs once per {@link RandomSource}, because that is where its cost actually lives: the
 * JDK's {@link java.security.SecureRandom} allocates on every draw, and no amount of reuse on our side removes it.
 *
 * <p>Run it with the GC profiler, because allocation is half the point:
 *
 * <pre>
 * java -jar benchmarks/target/benchmarks.jar IdGenerationBenchmark -prof gc
 * </pre>
 *
 * <p>Single-threaded by default. Add {@code -t 8} to see what the JDK's process-wide {@link java.security.SecureRandom}
 * costs under contention, which is the difference the per-thread source is there to remove.
 */
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Fork(value = 1, jvmArgsPrepend = {"--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED", "-Xmx1g", "-Xms1g"})
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class IdGenerationBenchmark {

    /**
     * The baseline: a shared {@link java.security.SecureRandom}, a throwaway 16-byte array, and a {@link UUID}.
     */
    @Benchmark
    public UUID jdkRandomUuid() {
        return UUID.randomUUID();
    }

    /**
     * The same value from {@link UUIDsGenerator}: a per-thread random source drawn in one go, over both
     * {@link RandomSource}s.
     */
    @Benchmark
    public UUID uuidV4(UuidV4State state) {
        return state.v4();
    }

    /**
     * A time-ordered v7 UUID: one CAS on the sequence, plus the random {@code rand_b} bits.
     */
    @Benchmark
    public UUID uuidV7(UuidV7State state) {
        return state.v7();
    }

    /**
     * A 64-bit Snowflake id: one CAS and some shifting, no object in sight.
     */
    @Benchmark
    public long snowflakeNextId(SnowflakesState state) {
        return state.snowflakes.nextId();
    }

    /**
     * Where the value lands: a fresh {@link UUID} per call, or the calling thread's reused one.
     */
    public enum InstanceMode {
        NEW_INSTANCE,
        THREAD_LOCAL
    }

    /**
     * Where the 122 random bits of a version 4 UUID come from.
     *
     * <p>{@code SECURE_RANDOM} is the default and the only one that produces unguessable ids; it is also the one that
     * allocates, inside the JDK, on every draw. {@code THREAD_LOCAL_RANDOM} is here to show what is left of the cost
     * once the entropy source is not the bottleneck: {@link ThreadLocalRandom#nextBytes(byte[])} fills the buffer
     * from {@code nextInt()} without allocating, so a v4 id becomes free of garbage - at the price of ids an
     * attacker can predict, which is a trade only some applications can make.
     */
    public enum RandomSource {
        SECURE_RANDOM,
        THREAD_LOCAL_RANDOM
    }

    @State(Scope.Benchmark)
    public static class UuidV7State {

        // generators are held once by an application, never created per message
        private final UUIDsGenerator uuids = new UUIDsGenerator();
        @Param({"NEW_INSTANCE", "THREAD_LOCAL"})
        InstanceMode instanceMode;
        // resolved once in setup: each param value is its own JMH run, so this branch never alternates
        private boolean threadLocal;

        @Setup
        public void setup() {
            threadLocal = instanceMode == InstanceMode.THREAD_LOCAL;
        }

        UUID v7() {
            return threadLocal ? uuids.threadLocalV7() : uuids.newV7();
        }
    }

    @State(Scope.Benchmark)
    public static class UuidV4State {

        @Param({"NEW_INSTANCE", "THREAD_LOCAL"})
        InstanceMode instanceMode;

        @Param({"SECURE_RANDOM", "THREAD_LOCAL_RANDOM"})
        RandomSource randomSource;

        private UUIDsGenerator uuids;
        private boolean threadLocal;

        @Setup
        public void setup() {
            threadLocal = instanceMode == InstanceMode.THREAD_LOCAL;
            // the default constructor already uses a per-thread SecureRandom; spelling it out keeps the two arms
            // of the parameter symmetrical, both going through the RandomBytesSource the caller supplies
            uuids = randomSource == RandomSource.SECURE_RANDOM
                    ? new UUIDsGenerator()
                    : new UUIDsGenerator(new UUIDv7(), dst -> ThreadLocalRandom.current().nextBytes(dst));
        }

        UUID v4() {
            return threadLocal ? uuids.threadLocalV4() : uuids.newV4();
        }
    }

    @State(Scope.Benchmark)
    public static class SnowflakesState {

        // an explicit node id, so the benchmark does not depend on a system property being set
        final SnowflakeId snowflakes = new SnowflakeId(1);
    }
}
