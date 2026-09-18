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

import org.lolaf.staffix.codec.serde.IntSerde;
import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Baseline for {@link IntSerde} serialization/deserialization across the magnitude bands that matter for FIX:
 * short tag-like values (1-2 digits), mid-range values, and the signed boundaries. Establishes numbers to
 * measure the planned algorithm changes (length-specialized parse, O(1) sizing, packed digit table, SWAR, itoa)
 * against.
 *
 * <p>{@code serializeIntoBuffer} and {@code deserializeUnsigned} mirror the real hot paths: encoders write into a
 * pre-sized slice, and the parser reads an unsigned field tag out of a sub-range of the message bytes.
 */
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Fork(value = 1, jvmArgsPrepend = {
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xmx1g", "-Xms1g"
})
public class IntSerDeBenchmark {

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public byte[] serializeAllocating(SerDeState state) {
        return IntSerde.serialize(state.value);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public byte[] serializeIntoBuffer(SerDeState state) {
        IntSerde.serialize(state.value, state.outBuf, state.valueSize, 0);
        return state.outBuf;
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public byte[] cachedSerialize(SerDeState state) {
        return IntSerde.cachedSerialize(state.value);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public int deserialize(SerDeState state) {
        return IntSerde.deserialize(state.serialized);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public int deserializeUnsigned(SerDeState state) {
        return IntSerde.deserializeUnsigned(state.absSerialized, 0, state.absSize);
    }

    @State(Scope.Thread)
    public static class SerDeState {

        @Param({"7", "42", "789", "6789", "12345", "1234567890", "2147483647", "-2147483648", "-98765"})
        String input;

        int value;
        int valueSize;
        byte[] serialized;
        byte[] outBuf;

        int absValue;
        int absSize;
        byte[] absSerialized;

        @Setup(Level.Trial)
        public void setup() {
            value = Integer.parseInt(input);
            serialized = IntSerde.serialize(value);
            valueSize = serialized.length;
            outBuf = new byte[16];

            absValue = value == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(value);
            absSerialized = Integer.toString(absValue).getBytes(StandardCharsets.US_ASCII);
            absSize = absSerialized.length;
        }
    }
}
