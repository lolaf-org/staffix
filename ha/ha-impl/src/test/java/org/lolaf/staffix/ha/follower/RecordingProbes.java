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

import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.Probes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Probes a test evaluates itself, by name, with no delay or scheduling.
 */
public final class RecordingProbes implements Probes {

    private final Map<String, Probe> probes = new ConcurrentHashMap<>();
    /**
     * Whether the component is active, as the engine was told; always for a session's probes.
     */
    public volatile BooleanSupplier active = () -> true;

    @Override
    public void add(Probe probe) {
        if (probes.putIfAbsent(probe.getName(), probe) != null) {
            throw new IllegalArgumentException("Already a probe " + probe.getName());
        }
    }

    @Override
    public boolean remove(String name) {
        return probes.remove(name) != null;
    }

    @Override
    public List<Probe> list() {
        return List.copyOf(probes.values());
    }

    public Probe get(String name) {
        return probes.get(name);
    }

    public Optional<Alert> evaluate(String name) {
        return probes.get(name).getEvaluator().evaluate();
    }
}
