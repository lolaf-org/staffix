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

import org.lolaf.staffix.benchmarks.impl.HaLoad;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Where a highly available pair saturates: many sessions at a fixed message rate, an open loop. The figures are in
 * the logs, not in JMH's score, which is only how long each iteration took: latency percentiles from each request's
 * due time, and the replication's meters (follower lag, laps, snapshots) per iteration.
 */
@Warmup(iterations = 1, batchSize = 1)
@Measurement(iterations = 3, batchSize = 1)
@Fork(value = 1, jvmArgsPrepend = {
        "--enable-native-access=ALL-UNNAMED",
        "-XX:-RestrictContended", "-XX:ContendedPaddingWidth=64",
        "--add-opens", "java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "-Xmx4g", "-Xms4g"})
public class HaLoadBenchmark {

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public long openLoop(HaLoad load) {
        return load.run();
    }
}
