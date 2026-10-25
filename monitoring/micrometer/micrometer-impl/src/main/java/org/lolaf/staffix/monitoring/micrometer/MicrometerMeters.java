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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.api.monitoring.FixEngineMonitoringContext;
import org.lolaf.staffix.api.monitoring.FixMeterDescriptor;
import org.lolaf.staffix.api.monitoring.MetersContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.ToDoubleFunction;

/**
 * The meters obtained through a {@link MetersContext}, a session's or the engine's, each tagged with the context's own
 * tags and removed together.
 */
class MicrometerMeters implements FixEngineMonitoringContext {

    private final MeterRegistry meterRegistry;
    private final MicrometerMonitoringManagerSettings settings;
    private final Tags contextTags;
    private final Map<String, MicrometerTimerWrapper> timers = new ConcurrentHashMap<>();
    private final Map<String, CounterWrapper> counters = new ConcurrentHashMap<>();
    private final Map<String, RegisteredGauge> gauges = new ConcurrentHashMap<>();
    private final Map<String, FixMeterDescriptor> descriptors = new ConcurrentHashMap<>();
    private final boolean custom;

    /**
     * @param custom whether the meters are the application's own, as a session's are, rather than staffix's
     */
    MicrometerMeters(MeterRegistry meterRegistry, MicrometerMonitoringManagerSettings settings, Tags contextTags, boolean custom) {
        this.meterRegistry = meterRegistry;
        this.settings = settings;
        this.contextTags = contextTags;
        this.custom = custom;
    }

    static Timer timer(String name, String description, MeterRegistry meterRegistry, Tags tags,
                       MicrometerMonitoringManagerSettings.TimerSettings timerSettings) {
        return Timer.builder(name)
                .tags(tags)
                .description(description)
                .publishPercentileHistogram(timerSettings.isPublishPercentileHistogram())
                .publishPercentiles(timerSettings.getHistogramsPercentiles().stream().mapToDouble(Double::doubleValue).toArray())
                .distributionStatisticExpiry(timerSettings.getDistributionStatisticExpiry())
                .minimumExpectedValue(timerSettings.getMinExpectedValue())
                .maximumExpectedValue(timerSettings.getMaxExpectedValue())
                .distributionStatisticBufferLength(timerSettings.getDistributionStatisticBufferLength())
                .percentilePrecision(timerSettings.getPercentilePrecision())
                .serviceLevelObjectives(timerSettings.getServiceLevelObjectives().toArray(new Duration[0]))
                .register(meterRegistry);
    }

    private static String meterKey(String id, Map<String, String> tags) {
        if (tags.isEmpty()) {
            return id;
        }
        StringBuilder tagsToString = new StringBuilder();
        tags.forEach((k, v) -> tagsToString.append(k).append(v));
        return id + "-" + tagsToString.toString().hashCode();
    }

    private static FixMeterDescriptor withTagKeysOf(FixMeterDescriptor known, FixMeterDescriptor added) {
        Set<String> tagKeys = new LinkedHashSet<>(known.getTagKeys());
        if (!tagKeys.addAll(added.getTagKeys())) {
            return known;
        }
        return known.toBuilder().clearTagKeys().tagKeys(tagKeys).build();
    }

    @Override
    public org.lolaf.staffix.api.monitoring.Timer getTimer(String id, String description, Map<String, String> tags) {
        MicrometerMonitoringManagerSettings.TimerSettings timerSettings = settings.getTimerSettings().getOrDefault(id, settings.getDefaultTimersSettings());
        return timers.computeIfAbsent(meterKey(id, tags), key -> {
            Timer raw = timer(id, description, meterRegistry, tags(tags), timerSettings);
            describe(FixMeterDescriptor.builder().name(id).type(FixMeterDescriptor.Type.TIMER).description(description)
                    .tagKeys(tags.keySet()).percentiles(timerSettings.isPublishPercentileHistogram()).custom(custom).build());
            return new MicrometerTimerWrapper(raw);
        });
    }

    @Override
    public org.lolaf.staffix.api.monitoring.Counter getCounter(String id, String description, Map<String, String> tags) {
        return counters.computeIfAbsent(meterKey(id, tags), key -> {
            Counter counter = Counter.builder(id)
                    .description(description)
                    .tags(tags(tags))
                    .register(meterRegistry);
            describe(id, description, tags, FixMeterDescriptor.Type.COUNTER);
            return new PushedCounter(counter);
        });
    }

    @Override
    public org.lolaf.staffix.api.monitoring.Counter getCounter(String id, String description, Map<String, String> tags, LongSupplier total) {
        return counters.computeIfAbsent(meterKey(id, tags), key -> {
            FunctionCounter counter = FunctionCounter.builder(id, total, supplier -> supplier.getAsLong())
                    .description(description)
                    .tags(tags(tags))
                    .register(meterRegistry);
            describe(id, description, tags, FixMeterDescriptor.Type.COUNTER);
            return new PulledCounter(counter);
        });
    }

    @Override
    public org.lolaf.staffix.api.monitoring.Gauge getGauge(String id, String description, Map<String, String> tags) {
        return gauges.computeIfAbsent(meterKey(id, tags), key -> {
            PushedGauge gauge = new PushedGauge();
            gauge.meter = registerGauge(id, description, tags, gauge, PushedGauge::value);
            return gauge;
        });
    }

    @Override
    public org.lolaf.staffix.api.monitoring.Gauge getGauge(String id, String description, Map<String, String> tags, DoubleSupplier value) {
        return gauges.computeIfAbsent(meterKey(id, tags), key -> {
            PulledGauge gauge = new PulledGauge();
            gauge.meter = registerGauge(id, description, tags, value, DoubleSupplier::getAsDouble);
            return gauge;
        });
    }

    private <T> Gauge registerGauge(String id, String description, Map<String, String> tags, T state, ToDoubleFunction<T> value) {
        Gauge gauge = Gauge.builder(id, state, value)
                .description(description)
                .tags(tags(tags))
                .strongReference(true)
                .register(meterRegistry);
        describe(id, description, tags, FixMeterDescriptor.Type.GAUGE);
        return gauge;
    }

    private void describe(String id, String description, Map<String, String> tags, FixMeterDescriptor.Type type) {
        describe(FixMeterDescriptor.builder().name(id).type(type).description(description).tagKeys(tags.keySet())
                .custom(custom).build());
    }

    private void describe(FixMeterDescriptor descriptor) {
        descriptors.merge(descriptor.getName(), descriptor, MicrometerMeters::withTagKeysOf);
    }

    private Tags tags(Map<String, String> tags) {
        Tags all = contextTags;
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            all = all.and(tag.getKey(), tag.getValue());
        }
        return all;
    }

    @Override
    public List<FixMeterDescriptor> getMeterDescriptors() {
        return new ArrayList<>(descriptors.values());
    }

    boolean isEmpty() {
        return descriptors.isEmpty();
    }

    void remove() {
        timers.values().forEach(timer -> meterRegistry.remove(timer.timer));
        counters.values().forEach(counter -> meterRegistry.remove(counter.meter()));
        gauges.values().forEach(gauge -> meterRegistry.remove(gauge.meter));
        timers.clear();
        counters.clear();
        gauges.clear();
        descriptors.clear();
    }

    private interface CounterWrapper extends org.lolaf.staffix.api.monitoring.Counter {
        Meter meter();
    }

    @RequiredArgsConstructor
    private static final class PushedCounter implements CounterWrapper {

        private final Counter counter;

        @Override
        public void increment() {
            counter.increment();
        }

        @Override
        public void increment(long amount) {
            counter.increment(amount);
        }

        @Override
        public Meter meter() {
            return counter;
        }
    }

    @RequiredArgsConstructor
    private static final class PulledCounter implements CounterWrapper {

        private final FunctionCounter counter;

        @Override
        public void increment() {
            // the supplier gives the total
        }

        @Override
        public void increment(long amount) {
            // the supplier gives the total
        }

        @Override
        public Meter meter() {
            return counter;
        }
    }

    private abstract static class RegisteredGauge implements org.lolaf.staffix.api.monitoring.Gauge {
        Gauge meter;
    }

    private static final class PushedGauge extends RegisteredGauge {

        private final AtomicLong bits = new AtomicLong(Double.doubleToRawLongBits(0));

        @Override
        public void set(double value) {
            bits.set(Double.doubleToRawLongBits(value));
        }

        @Override
        public void add(double delta) {
            long current;
            do {
                current = bits.get();
            } while (!bits.compareAndSet(current, Double.doubleToRawLongBits(Double.longBitsToDouble(current) + delta)));
        }

        double value() {
            return Double.longBitsToDouble(bits.get());
        }
    }

    private static final class PulledGauge extends RegisteredGauge {

        @Override
        public void set(double value) {
            // the supplier gives the value
        }

        @Override
        public void add(double delta) {
            // the supplier gives the value
        }
    }

    @RequiredArgsConstructor
    private static final class MicrometerTimerWrapper implements org.lolaf.staffix.api.monitoring.Timer {

        private final Timer timer;
        private long startTime;

        @Override
        public void start() {
            startTime = System.nanoTime();
        }

        @Override
        public void stop() {
            timer.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
        }

        @Override
        public void record(long duration, TimeUnit unit) {
            timer.record(duration, unit);
        }
    }
}
