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
package org.lolaf.staffix.jvmwarmup;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.stores.loggers.slf4j.Slf4jMessagesLoggerSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JvmWarmupTest {

    /**
     * Lower bound on the messages a warmup run must have exchanged in each direction. Every test here runs for 3
     * seconds throttled at 10 ms, so an unloaded machine reaches about 300; this bound sits deliberately far below
     * that. These are functional tests of the warmup, asserting it really drove traffic both ways rather than dying
     * after a handful of messages, and not throughput tests: a bound close to the ideal count just fails whenever the
     * machine is busy, which is exactly what a parallel build makes it.
     */
    private static final int MIN_MESSAGES_EXCHANGED = 30;

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void runsAndExchangesMessages() throws Exception {
        JvmWarmupOptions options = JvmWarmupOptions.builder()
                .warmupDuration(Duration.ofSeconds(3))
                .throttling(Duration.ofMillis(10))
                .port(freePort())
                .build();

        JvmWarmupResult result = JvmWarmup.run(options).get(30, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(result.isCancelled()).isFalse();
        assertThat(result.getMessagesSent()).as("messages sent").isGreaterThan(MIN_MESSAGES_EXCHANGED);
        assertThat(result.getMessagesReceived()).as("messages received").isGreaterThan(MIN_MESSAGES_EXCHANGED);
    }

    @Test
    void runsWithFixEngineBuilderStoresAndLoggers() throws Exception {
        FixEngineBuilder sourceBuilder = FixEngineBuilder.builder()
                .fixMessagesStore(MemoryMessageStoreSettings.builder().instanceId("store-a").maxEntriesInMemory(16).build())
                .fixMessagesStore(MemoryMessageStoreSettings.builder().instanceId("store-b").maxEntriesInMemory(16).build())
                .fixMessagesLogger(Slf4jMessagesLoggerSettings.builder().instanceId("logger-a")
                        .logIncoming(false).logOutgoing(false).build())
                .build();

        JvmWarmupOptions options = JvmWarmupOptions.builder()
                .warmupDuration(Duration.ofSeconds(3))
                .throttling(Duration.ofMillis(10))
                .port(freePort())
                .fixEngineBuilder(sourceBuilder)
                .build();

        JvmWarmupResult result = JvmWarmup.run(options).get(30, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(result.isCancelled()).isFalse();
        assertThat(result.getMessagesSent()).as("messages sent").isGreaterThan(MIN_MESSAGES_EXCHANGED);
        assertThat(result.getMessagesReceived()).as("messages received").isGreaterThan(MIN_MESSAGES_EXCHANGED);
    }

    @Test
    void runsWithFixEngineBuilderPlugins() throws Exception {
        FixEngineBuilder sourceBuilder = FixEngineBuilder.builder()
                .fixMessagesStore(MemoryMessageStoreSettings.builder().maxEntriesInMemory(0).build())
                .fixMessagesLogger(Slf4jMessagesLoggerSettings.builder()
                        .logIncoming(false).logOutgoing(false).build())
                .fixSessionsPlugin(new NoOpPluginSettings("plugin-a"))
                .build();

        JvmWarmupOptions options = JvmWarmupOptions.builder()
                .warmupDuration(Duration.ofSeconds(3))
                .throttling(Duration.ofMillis(10))
                .port(freePort())
                .fixEngineBuilder(sourceBuilder)
                .build();

        JvmWarmupResult result = JvmWarmup.run(options).get(30, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(result.isCancelled()).isFalse();
        assertThat(result.getMessagesSent()).as("messages sent").isGreaterThan(MIN_MESSAGES_EXCHANGED);
        assertThat(result.getMessagesReceived()).as("messages received").isGreaterThan(MIN_MESSAGES_EXCHANGED);
    }

    @Test
    void runsWithFixtTransport() throws Exception {
        JvmWarmupOptions options = JvmWarmupOptions.builder()
                .warmupDuration(Duration.ofSeconds(3))
                .throttling(Duration.ofMillis(10))
                .port(freePort())
                .fixt(true)
                .build();

        JvmWarmupResult result = JvmWarmup.run(options).get(30, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(result.isCancelled()).isFalse();
        assertThat(result.getMessagesSent()).as("messages sent").isGreaterThan(MIN_MESSAGES_EXCHANGED);
        assertThat(result.getMessagesReceived()).as("messages received").isGreaterThan(MIN_MESSAGES_EXCHANGED);
    }

    /**
     * Cancelling the future shortens the warmup instead of abandoning it: the run winds down early and the future
     * still completes normally, carrying the counters of what was exchanged before the stop. Asserted here because
     * it is the one place {@link JvmWarmup.WarmupFuture} deliberately departs from {@link CompletableFuture}, so a
     * later "simplification" back to the inherited cancel would silently turn every cancelled warmup's result into
     * a CancellationException.
     */
    @Test
    void cancellingShortensTheRunAndStillCompletesWithAResult() throws Exception {
        JvmWarmupOptions options = JvmWarmupOptions.builder()
                .warmupDuration(Duration.ofMinutes(10))
                .throttling(Duration.ofMillis(10))
                .port(freePort())
                .build();

        CompletableFuture<JvmWarmupResult> future = JvmWarmup.run(options);
        Thread.sleep(3_000);

        assertThat(future.cancel(true)).as("cancel() reports the future was not cancelled in the Future sense").isFalse();
        assertThat(future.isCancelled()).as("future not cancelled").isFalse();

        JvmWarmupResult result = future.get(30, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(result.isCancelled()).as("result flagged as cancelled").isTrue();
        assertThat(result.getActualDuration()).as("stopped well before the 10 minutes asked for")
                .isLessThan(Duration.ofMinutes(1));
        assertThat(result.getMessagesSent()).as("messages sent before the stop").isGreaterThan(MIN_MESSAGES_EXCHANGED);
        assertThat(result.getMessagesReceived()).as("messages received before the stop").isGreaterThan(MIN_MESSAGES_EXCHANGED);
    }

    private static class NoOpPlugin implements FixSessionsPlugin<PluginContext.VoidPluginContext> {

        private final String instanceId;

        NoOpPlugin(String instanceId) {
            this.instanceId = instanceId;
        }

        @Override
        public String getInstanceId() {
            return instanceId;
        }

        @Override
        public Optional<FixSessionPlugin<PluginContext.VoidPluginContext, Void>> onSessionCreated(
                String fixInstanceId, FixSession fixSession,
                Collection<MessageType> incomingMessageTypes,
                Collection<MessageType> outgoingMessageTypes) {
            return Optional.empty();
        }
    }

    private static class NoOpPluginSettings implements FixSessionsPluginSettings<NoOpPlugin> {

        private final String instanceId;

        NoOpPluginSettings(String instanceId) {
            this.instanceId = instanceId;
        }

        @Override
        public String getInstanceId() {
            return instanceId;
        }

        @Override
        public NoOpPlugin instance() {
            return new NoOpPlugin(instanceId);
        }
    }
}