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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.impl.session.ClockImpl;
import org.openjdk.jmh.annotations.*;

import java.lang.reflect.Constructor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Fork(value = 1, jvmArgsPrepend = {"--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED", "-Xmx1g", "-Xms1g"})
public class ClockBenchmark {

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void testClock(ClockState clockState) {
        clockState.time();
    }

    public enum ClockType {
        STOCK,
        OPTIMIZED
    }

    @State(Scope.Benchmark)
    public static class ClockState {
        @Param({"STOCK", "OPTIMIZED"})
        ClockType clockType;
        Clock clock;

        @Setup
        public void setup() {
            boolean optimized = clockType.equals(ClockType.OPTIMIZED);
            try {
                Constructor<ClockImpl> c = ClockImpl.class.getDeclaredConstructor(boolean.class);
                c.setAccessible(true);
                clock = c.newInstance(optimized);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        public void time() {
            clock.now();
        }
    }
}
