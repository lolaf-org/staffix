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
package org.lolaf.staffix.ha.follower;

import org.lolaf.staffix.api.monitoring.Counter;
import org.lolaf.staffix.api.monitoring.FixEngineMonitoringContext;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringContext;
import org.lolaf.staffix.api.monitoring.FixMeterDescriptor;
import org.lolaf.staffix.api.monitoring.Gauge;
import org.lolaf.staffix.api.monitoring.Timer;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Engine or session meters a test reads back: pulled values by name and tags, and how many times each timer recorded.
 */
public final class RecordingMeters implements FixEngineMonitoringContext, FixSessionsMonitoringContext {

    private final Map<String, DoubleSupplier> pulled = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> recordings = new ConcurrentHashMap<>();

    private static String key(String id, Map<String, String> tags) {
        return id + new TreeMap<>(tags);
    }

    public double read(String id) {
        return read(id, Map.of());
    }

    public double read(String id, Map<String, String> tags) {
        DoubleSupplier value = pulled.get(key(id, tags));
        if (value == null) {
            throw new AssertionError("No meter " + key(id, tags) + " among " + pulled.keySet());
        }
        return value.getAsDouble();
    }

    public long recordings(String id) {
        return recordings(id, Map.of());
    }

    public long recordings(String id, Map<String, String> tags) {
        AtomicLong count = recordings.get(key(id, tags));
        return count == null ? 0 : count.get();
    }

    @Override
    public Timer getTimer(String id, String description, Map<String, String> tags) {
        AtomicLong count = recordings.computeIfAbsent(key(id, tags), key -> new AtomicLong());
        return new Timer() {
            @Override
            public void start() {
            }

            @Override
            public void stop() {
                count.incrementAndGet();
            }

            @Override
            public void record(long duration, TimeUnit unit) {
                count.incrementAndGet();
            }
        };
    }

    @Override
    public Counter getCounter(String id, String description, Map<String, String> tags) {
        throw new UnsupportedOperationException("pushed counter " + id);
    }

    @Override
    public Counter getCounter(String id, String description, Map<String, String> tags, LongSupplier total) {
        pulled.put(key(id, tags), total::getAsLong);
        return Counter.VoidCounter.getInstance();
    }

    @Override
    public Gauge getGauge(String id, String description, Map<String, String> tags) {
        throw new UnsupportedOperationException("pushed gauge " + id);
    }

    @Override
    public Gauge getGauge(String id, String description, Map<String, String> tags, DoubleSupplier value) {
        pulled.put(key(id, tags), value);
        return Gauge.VoidGauge.getInstance();
    }

    @Override
    public List<FixMeterDescriptor> getMeterDescriptors() {
        return List.of();
    }
}
