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

import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.codec.decoders.DeserializationContextImpl;
import org.lolaf.staffix.codec.serde.BigDecimalSerde;
import org.lolaf.staffix.codec.serde.DecimalFloatTLSerde;
import org.lolaf.staffix.codec.serde.DoubleSerde;
import org.openjdk.jmh.annotations.*;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Fork(value = 1, jvmArgsPrepend = {
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xmx1g", "-Xms1g"
})
public class FloatSerDeBenchmark {

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public byte[] doubleSerialize(SerDeState state) {
        return DoubleSerde.serialize(state.doubleValue);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public double doubleDeserialize(SerDeState state) {
        return DoubleSerde.deserialize(state.ctx);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void bigDecimalSerialize(SerDeState state) {
        state.bigDecimalSerde.serialize(state.outBuf, state.bigDecimalValue);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public BigDecimal bigDecimalDeserialize(SerDeState state) {
        return state.bigDecimalSerde.deserialize(state.ctx);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void decimalFloatSerialize(SerDeState state) {
        state.decimalFloatSerde.serialize(state.outBuf, state.decimalFloatValue);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public DecimalFloat decimalFloatDeserialize(SerDeState state) {
        return state.decimalFloatSerde.deserialize(state.ctx);
    }

    @State(Scope.Thread)
    public static class SerDeState {

        @Param({"123", "123.456", "1.23456789012345", "-9876.54321", "999999999999999"})
        String input;

        double doubleValue;
        BigDecimal bigDecimalValue;
        DecimalFloat decimalFloatValue;
        DeserializationContextImpl ctx;
        ByteBuffer outBuf;
        DecimalFloatTLSerde decimalFloatSerde;
        BigDecimalSerde bigDecimalSerde;

        @Setup(Level.Trial)
        public void setup() {
            decimalFloatSerde = DecimalFloatTLSerde.instance();
            bigDecimalSerde = BigDecimalSerde.instance();
            byte[] inputBytes = input.getBytes(StandardCharsets.US_ASCII);
            ctx = new DeserializationContextImpl();
            ctx.setup(inputBytes);
            outBuf = ByteBuffer.allocate(64);

            doubleValue = DoubleSerde.deserialize(inputBytes);
            bigDecimalValue = bigDecimalSerde.deserialize(ctx);
            decimalFloatValue = decimalFloatSerde.deserialize(ctx);
        }

        @Setup(Level.Invocation)
        public void setupInvocation() {
            outBuf.clear();
        }
    }
}
