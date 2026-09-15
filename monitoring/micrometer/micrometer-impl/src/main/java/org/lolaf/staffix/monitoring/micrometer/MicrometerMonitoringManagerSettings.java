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
package org.lolaf.staffix.monitoring.micrometer;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.experimental.SuperBuilder;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.monitoring.FixSessionMonitoringManagerSettings;
import org.lolaf.staffix.api.msg.MessageType;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The registry to publish to, and which timers are enabled - each one costs a measurement on the message path.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class MicrometerMonitoringManagerSettings implements FixSessionMonitoringManagerSettings {

    /**
     * Names this instance, so a session can select it by id when more than one is configured.
     */
    @Builder.Default
    private final String instanceId = DEFAULT_INSTANCE_ID;
    /**
     * Called with the registry as the engine stops, for a backend that needs a final push before shutdown.
     */
    @Builder.Default
    private final Consumer<MeterRegistry> stoppingMeterRegistryConsumer = InstanceProvider.emptyConsumer();
    /**
     * Called with the registry once the engine is up, for binding extra meters to the same registry.
     */
    @Builder.Default
    private final Consumer<MeterRegistry> startedMeterRegistryConsumer = InstanceProvider.emptyConsumer();
    @Builder.Default
    /**
     * The timer settings every timer uses unless overridden below.
     */
    private final TimerSettings defaultTimersSettings = TimerSettings.builder().build();
    /**
     * Per-message-type overrides of the timer settings, for measuring one message type more finely than the rest.
     */
    @Singular
    private final Map<MessageType, TimerSettings> builtInTimerSettings;
    /**
     * Overrides by timer name, for the timers that are not per message type.
     */
    @Singular
    private final Map<String, TimerSettings> timerSettings;
    /**
     * Whether to enable the {@code messages.read.latency} timer. Defaults to {@code true}.
     */
    @Builder.Default
    private final boolean readLatencyEnabled = true;
    /**
     * Whether to enable the {@code messages.write.latency} timer. Defaults to {@code true}.
     */
    @Builder.Default
    private final boolean writeLatencyEnabled = true;
    /**
     * Whether to enable the {@code messages.decoding.latency} timer. Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean decodingLatencyEnabled = false;
    /**
     * Whether to enable the {@code messages.encoding.latency} timer. Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean encodingLatencyEnabled = false;
    /**
     * Whether to enable the {@code session.rtt} timer fed from
     * {@link org.lolaf.staffix.api.session.RttMeasurement#getRoundTripTime()} after each accepted
     * sample. Defaults to {@code false} — RTT measurement is itself off by default at the session level.
     */
    @Builder.Default
    private final boolean rttLatencyEnabled = false;
    /**
     * Whether to enable the {@code session.clock.offset} gauge fed from
     * {@link org.lolaf.staffix.api.session.RttMeasurement#getClockOffset()} after each accepted sample.
     * The value is the latest EMA-smoothed offset in nanoseconds (signed: positive means the
     * remote clock is ahead of the local clock). A Gauge is used instead of a histogram because
     * Micrometer's distribution instruments silently drop negative values.
     * Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean clockOffsetEnabled = false;
    /**
     * Histogram/percentile settings for the {@code session.rtt} timer.
     */
    @Builder.Default
    private final TimerSettings rttTimerSettings = TimerSettings.builder()
            .maxExpectedValue(Duration.ofSeconds(2))
            .publishPercentileHistogram(false)
            .build();
    /**
     * Supplies the registry to publish to. Required.
     */
    private Supplier<MeterRegistry> meterRegistrySupplier;

    @Getter
    @Builder
    public static class TimerSettings {

        /**
         * Whether a histogram is published as well as the summary statistics, which is what lets a backend compute
     * percentiles across instances rather than per instance.
         */
        @Builder.Default
        private boolean publishPercentileHistogram = true;

        /**
         * The percentiles computed locally.
         */
        @Builder.Default
        private List<Double> histogramsPercentiles = List.of(0.5, 0.9, 0.95, 0.99);

        /**
         * How long a measurement counts towards the rolling distribution.
         */
        @Builder.Default
        private Duration distributionStatisticExpiry = Duration.ofSeconds(10);

        /**
         * How many expiry windows are kept, so the statistics do not reset to nothing at each boundary.
         */
        @Builder.Default
        private int distributionStatisticBufferLength = 3;

        /**
         * Digits of precision for the local percentiles, traded against the memory the histogram takes.
         */
        @Builder.Default
        private int percentilePrecision = 1;

        /**
         * Boundaries to publish counts for, for alerting on "how many exceeded this" rather than on a percentile.
         */
        @Singular
        private List<Duration> serviceLevelObjectives;

        /**
         * The bottom of the histogram range. Measurements below it are still counted, but not resolved.
         */
        @Builder.Default
        private Duration minExpectedValue = Duration.ofNanos(TimeUnit.MICROSECONDS.toNanos(5));

        /**
         * The top of the histogram range, which should be set above the worst latency worth distinguishing.
         */
        @Builder.Default
        private Duration maxExpectedValue = Duration.ofMillis(3);

    }

}