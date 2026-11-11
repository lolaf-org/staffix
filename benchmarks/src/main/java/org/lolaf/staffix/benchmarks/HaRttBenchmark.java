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

import org.lolaf.staffix.benchmarks.impl.HaPair;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * The round trip of {@link FixEngineRTTBenchmark}, the answering engine highly available: what each replication mode
 * adds, with a slower follower store, and beside a session trading continuously. Each trial logs the replication's
 * ack timers over its measurement iterations, which split the follower's part from the leader's. The leader and the
 * follower share one JVM and talk over loopback, so a network between them adds its round trip to every {@code SYNC}
 * figure. Run with {@code -p mode=SYNC_APPLIED -p followerStore=MEMORY -p replicationWaiting=SENDER_SPIN,SENDER_WAITER_SPIN,ALL_SPIN}
 * for what each spinning thread buys.
 */
@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 3, time = 5)
@Fork(value = 1, jvmArgsPrepend = {
        "--enable-native-access=ALL-UNNAMED",
        "-XX:-RestrictContended", "-XX:ContendedPaddingWidth=64", "-XX:AutoBoxCacheMax=65536",
        "--add-opens", "java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "-Xmx4g", "-Xms4g"})
public class HaRttBenchmark {

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public void roundTrip(HaPair pair) {
        pair.roundTrip();
    }
}
