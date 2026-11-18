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
package org.lolaf.staffix.stores.messages.async.spring;

import net.openhft.chronicle.queue.RollCycles;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.stores.core.async.AsyncStoreSettings;
import org.lolaf.staffix.stores.messages.async.AsyncMessagesStoreSettings;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestAsyncMessagesStoreAutoConfiguration {

    private static final FixSessionId FIX_SESSION_ID = FixSessionId.of("test", FixRegularVersion.VERSION_44, "sender", "target");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AsyncMessagesStoreAutoConfiguration.class));

    @Test
    void bindsTheResourceCheckIntervalAndTheAlertClearDelay() {
        runner.withPropertyValues("staffix.messages-stores-async.instances.MAIN.wraps=MAIN",
                        "staffix.messages-stores-async.instances.MAIN.async-queue-directory=target/async-store",
                        "staffix.messages-stores-async.instances.MAIN.resource-check-interval=2s",
                        "staffix.messages-stores-async.instances.MAIN.alert-clear-delay=1m")
                .run(context -> {
                    FixMessagesStoreSettings settings = contribute(context.getBean(AsyncMessagesStoreContributor.class));

                    assertThat(settings.getResourceCheckInterval()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(settings.getAlertClearDelay()).isEqualTo(Duration.ofMinutes(1));
                });
    }

    @Test
    void defaultsTheResourceCheckIntervalAndTheAlertClearDelay() {
        runner.withPropertyValues("staffix.messages-stores-async.instances.MAIN.wraps=MAIN",
                        "staffix.messages-stores-async.instances.MAIN.async-queue-directory=target/async-store")
                .run(context -> {
                    FixMessagesStoreSettings settings = contribute(context.getBean(AsyncMessagesStoreContributor.class));

                    assertThat(settings.getResourceCheckInterval()).isEqualTo(FixMessagesStoreSettings.DEFAULT_RESOURCE_CHECK_INTERVAL);
                    assertThat(settings.getAlertClearDelay()).isEqualTo(FixMessagesStoreSettings.DEFAULT_ALERT_CLEAR_DELAY);
                });
    }

    @Test
    void bindsTheQueueTuning() {
        runner.withPropertyValues("staffix.messages-stores-async.instances.MAIN.wraps=MAIN",
                        "staffix.messages-stores-async.instances.MAIN.async-queue-directory=target/async-store",
                        "staffix.messages-stores-async.instances.MAIN.events-batching=50",
                        "staffix.messages-stores-async.instances.MAIN.batching-flush-interval=20ms",
                        "staffix.messages-stores-async.instances.MAIN.reader-threads-count=3",
                        "staffix.messages-stores-async.instances.MAIN.reader-thread-max-events-processing-per-store=64",
                        "staffix.messages-stores-async.instances.MAIN.roll-cycle=five-minutely",
                        "staffix.messages-stores-async.instances.MAIN.pretouch-interval=0",
                        "staffix.messages-stores-async.instances.MAIN.pretouch-ahead-time=80ms",
                        "staffix.messages-stores-async.instances.MAIN.pretouch-min-ahead-bytes=4194304")
                .run(context -> {
                    AsyncStoreSettings settings = asyncStoreSettings(context.getBean(AsyncMessagesStoreContributor.class));

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
        runner.withPropertyValues("staffix.messages-stores-async.instances.MAIN.wraps=MAIN",
                        "staffix.messages-stores-async.instances.MAIN.async-queue-directory=target/async-store")
                .run(context -> {
                    AsyncStoreSettings settings = asyncStoreSettings(context.getBean(AsyncMessagesStoreContributor.class));
                    AsyncStoreSettings defaults = AsyncStoreSettings.builder().build();

                    assertThat(settings.getAsyncQueueDirectory()).isEqualTo("target/async-store");
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

    private static AsyncStoreSettings asyncStoreSettings(AsyncMessagesStoreContributor contributor) {
        return ((AsyncMessagesStoreSettings) contribute(contributor)).getAsyncStoreSettings();
    }

    private static FixMessagesStoreSettings contribute(AsyncMessagesStoreContributor contributor) {
        Map<String, FixMessagesStoreSettings> registry = new HashMap<>();
        registry.put("MAIN", mock(FixMessagesStoreSettings.class));
        contributor.contribute(registry);
        return registry.get("MAIN");
    }
}
