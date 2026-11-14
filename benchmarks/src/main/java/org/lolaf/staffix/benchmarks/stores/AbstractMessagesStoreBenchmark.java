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
package org.lolaf.staffix.benchmarks.stores;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.Probes;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Times {@code storeMessageSent} as a session calls it, one message at a time, for every store alike. The calls are
 * paced, outside the timing, so that a store writing behind the call, such as the async one, keeps a bounded queue;
 * 0 messages per second does not pace.
 */
@Slf4j
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(value = 1, jvmArgsPrepend = {
        "--add-opens", "java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio.channels.spi=ALL-UNNAMED",
        "-Xmx2g", "-Xms2g"})
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public abstract class AbstractMessagesStoreBenchmark {

    public enum FileSystem {
        SHM,
        DISK;

        Path directory(String name) {
            return this == SHM ? Paths.get("/dev/shm", "staffix-messages-store-benchmark", name)
                    : Paths.get("target", "messages-store-benchmark", name).toAbsolutePath();
        }
    }

    @Param({"20000"})
    int messagesPerSecond;
    @Param({"300"})
    int messageSize;

    private FixMessagesStore store;
    private FixSessionMessagesStore sessionStore;
    private ByteBuffer message;
    private long seqNum = 1;
    private long callInterval;
    private long nextCallDue;

    protected abstract FixMessagesStore newStore();

    /**
     * Called once the session's store started.
     */
    protected void afterStart() throws IOException {
    }

    /**
     * Timed with the call it follows.
     */
    protected void afterStore() throws IOException {
    }

    /**
     * Called before the store starts and once it stopped.
     */
    protected void cleanUp() throws IOException {
    }

    @Setup(Level.Trial)
    public void startStore() throws Exception {
        cleanUp();
        store = newStore();
        store.start();
        sessionStore = store.getStore(FixSessionId.of("benchmark", FixRegularVersion.VERSION_44, "SENDER", "TARGET"), new NoProbes());
        sessionStore.start();
        afterStart();
        byte[] bytes = new byte[messageSize];
        Arrays.fill(bytes, (byte) 'X');
        message = ByteBuffer.wrap(bytes);
    }

    @Setup(Level.Iteration)
    public void startPacing() {
        callInterval = messagesPerSecond == 0 ? 0 : TimeUnit.SECONDS.toNanos(1) / messagesPerSecond;
        nextCallDue = System.nanoTime();
    }

    /**
     * A call that took longer than the interval makes the next ones due at once, as a session's backlog would.
     */
    @Setup(Level.Invocation)
    public void awaitCallDue() {
        if (callInterval > 0) {
            nextCallDue += callInterval;
            while (System.nanoTime() < nextCallDue) {
                Thread.onSpinWait();
            }
        }
    }

    @Benchmark
    public void storeMessageSent() throws IOException {
        message.rewind();
        sessionStore.storeMessageSent(seqNum++, message);
        afterStore();
    }

    @TearDown(Level.Iteration)
    public void logPendingWrites() {
        long pending = sessionStore.getPendingWrites();
        if (pending > 0) {
            log.info("{} of {} writes still pending", pending, seqNum - 1);
        }
    }

    @TearDown(Level.Trial)
    public void stopStore() throws Exception {
        sessionStore.stop(Deadline.immediate());
        store.stop(Deadline.of(Duration.ofSeconds(5)));
        cleanUp();
    }

    static void deleteDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> files = Files.walk(directory)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static class NoProbes implements Probes {

        @Override
        public void add(Probe probe) {
        }

        @Override
        public boolean remove(String name) {
            return false;
        }

        @Override
        public List<Probe> list() {
            return List.of();
        }
    }
}
