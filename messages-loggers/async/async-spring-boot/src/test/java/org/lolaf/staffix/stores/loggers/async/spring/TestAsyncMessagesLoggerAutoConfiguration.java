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
package org.lolaf.staffix.stores.loggers.async.spring;

import net.openhft.chronicle.queue.RollCycles;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.stores.core.async.AsyncStoreSettings;
import org.lolaf.staffix.stores.loggers.async.AsyncMessagesLoggerSettings;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestAsyncMessagesLoggerAutoConfiguration {

    private static final FixSessionId FIX_SESSION_ID = FixSessionId.of("test", FixRegularVersion.VERSION_44, "sender", "target");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AsyncMessagesLoggerAutoConfiguration.class));

    @Test
    void bindsTheResourceCheckIntervalAndTheAlertClearDelay() {
        runner.withPropertyValues("staffix.messages-loggers-async.instances.LOGS.wraps=LOGS",
                        "staffix.messages-loggers-async.instances.LOGS.async-queue-directory=target/async-logger",
                        "staffix.messages-loggers-async.instances.LOGS.resource-check-interval=2s",
                        "staffix.messages-loggers-async.instances.LOGS.alert-clear-delay=1m")
                .run(context -> {
                    FixMessagesLoggerSettings settings = contribute(context.getBean(AsyncMessagesLoggerContributor.class));

                    assertThat(settings.getResourceCheckInterval()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(settings.getAlertClearDelay()).isEqualTo(Duration.ofMinutes(1));
                });
    }

    @Test
    void defaultsTheResourceCheckIntervalAndTheAlertClearDelay() {
        runner.withPropertyValues("staffix.messages-loggers-async.instances.LOGS.wraps=LOGS",
                        "staffix.messages-loggers-async.instances.LOGS.async-queue-directory=target/async-logger")
                .run(context -> {
                    FixMessagesLoggerSettings settings = contribute(context.getBean(AsyncMessagesLoggerContributor.class));

                    assertThat(settings.getResourceCheckInterval()).isEqualTo(FixMessagesLoggerSettings.DEFAULT_RESOURCE_CHECK_INTERVAL);
                    assertThat(settings.getAlertClearDelay()).isEqualTo(FixMessagesLoggerSettings.DEFAULT_ALERT_CLEAR_DELAY);
                });
    }

    @Test
    void bindsTheQueueTuning() {
        runner.withPropertyValues("staffix.messages-loggers-async.instances.LOGS.wraps=LOGS",
                        "staffix.messages-loggers-async.instances.LOGS.async-queue-directory=target/async-logger",
                        "staffix.messages-loggers-async.instances.LOGS.events-batching=50",
                        "staffix.messages-loggers-async.instances.LOGS.batching-flush-interval=20ms",
                        "staffix.messages-loggers-async.instances.LOGS.reader-threads-count=3",
                        "staffix.messages-loggers-async.instances.LOGS.reader-thread-max-events-processing-per-store=64",
                        "staffix.messages-loggers-async.instances.LOGS.roll-cycle=five-minutely",
                        "staffix.messages-loggers-async.instances.LOGS.pretouch-interval=0",
                        "staffix.messages-loggers-async.instances.LOGS.pretouch-ahead-time=80ms",
                        "staffix.messages-loggers-async.instances.LOGS.pretouch-min-ahead-bytes=4194304")
                .run(context -> {
                    AsyncStoreSettings settings = asyncStoreSettings(context.getBean(AsyncMessagesLoggerContributor.class));

                    assertThat(settings.getEventsBatching()).isEqualTo(50);
                    assertThat(settings.getBatchingFlushInterval()).isEqualTo(Duration.ofMillis(20));
                    assertThat(settings.getReaderThreadsCount()).isEqualTo(3);
                    assertThat(settings.getReaderThreadMaxEventsProcessingPerStore()).isEqualTo(64);
                    assertThat(settings.getRollCycleProvider().apply(FIX_SESSION_ID)).isEqualTo(RollCycles.FIVE_MINUTELY);
                    assertThat(settings.getPretouchInterval()).isZero();
                    assertThat(settings.getPretouchAheadTime()).isEqualTo(Duration.ofMillis(80));
                    assertThat(settings.getPretouchMinAheadBytes()).isEqualTo(4L << 20);
                });
    }

    @Test
    void defaultsTheQueueTuning() {
        runner.withPropertyValues("staffix.messages-loggers-async.instances.LOGS.wraps=LOGS",
                        "staffix.messages-loggers-async.instances.LOGS.async-queue-directory=target/async-logger")
                .run(context -> {
                    AsyncStoreSettings settings = asyncStoreSettings(context.getBean(AsyncMessagesLoggerContributor.class));
                    AsyncStoreSettings defaults = AsyncStoreSettings.builder().build();

                    assertThat(settings.getAsyncQueueDirectory()).isEqualTo("target/async-logger");
                    assertThat(settings.getEventsBatching()).isEqualTo(defaults.getEventsBatching());
                    assertThat(settings.getBatchingFlushInterval()).isEqualTo(defaults.getBatchingFlushInterval());
                    assertThat(settings.getReaderThreadsCount()).isEqualTo(defaults.getReaderThreadsCount());
                    assertThat(settings.getReaderThreadMaxEventsProcessingPerStore())
                            .isEqualTo(defaults.getReaderThreadMaxEventsProcessingPerStore());
                    assertThat(settings.getRollCycleProvider().apply(FIX_SESSION_ID))
                            .isEqualTo(defaults.getRollCycleProvider().apply(FIX_SESSION_ID));
                    assertThat(settings.getPretouchInterval()).isEqualTo(defaults.getPretouchInterval());
                    assertThat(settings.getPretouchAheadTime()).isEqualTo(defaults.getPretouchAheadTime());
                    assertThat(settings.getPretouchMinAheadBytes()).isEqualTo(defaults.getPretouchMinAheadBytes());
                });
    }

    private static AsyncStoreSettings asyncStoreSettings(AsyncMessagesLoggerContributor contributor) {
        return ((AsyncMessagesLoggerSettings) contribute(contributor)).getAsyncStoreSettings();
    }

    private static FixMessagesLoggerSettings contribute(AsyncMessagesLoggerContributor contributor) {
        Map<String, FixMessagesLoggerSettings> registry = new HashMap<>();
        registry.put("LOGS", mock(FixMessagesLoggerSettings.class));
        contributor.contribute(registry);
        return registry.get("LOGS");
    }
}
