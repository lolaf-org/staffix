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

import org.lolaf.staffix.codec.serde.LongSerde;
import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Baseline for {@link LongSerde} deserialization across the magnitude bands that matter for FIX long fields
 * (notably MsgSeqNum, deserialized unsigned on every parsed message). {@code deserializeUnsigned} mirrors that
 * hot path; {@code deserialize} covers signed long field values.
 */
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Fork(value = 1, jvmArgsPrepend = {
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xmx1g", "-Xms1g"
})
public class LongSerDeBenchmark {

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public long deserialize(SerDeState state) {
        return LongSerde.deserialize(state.serialized);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public long deserializeUnsigned(SerDeState state) {
        return LongSerde.deserializeUnsigned(state.absSerialized, 0, state.absSize);
    }

    @State(Scope.Thread)
    public static class SerDeState {

        @Param({"7", "42", "789", "6789", "1234567", "1234567890123", "9223372036854775807", "-987654321"})
        String input;

        long value;
        byte[] serialized;

        long absValue;
        int absSize;
        byte[] absSerialized;

        @Setup(Level.Trial)
        public void setup() {
            value = Long.parseLong(input);
            serialized = LongSerde.serialize(value);

            absValue = value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
            absSerialized = Long.toString(absValue).getBytes(StandardCharsets.US_ASCII);
            absSize = absSerialized.length;
        }
    }
}
