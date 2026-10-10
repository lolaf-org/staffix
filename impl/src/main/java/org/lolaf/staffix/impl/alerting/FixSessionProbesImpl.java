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
package org.lolaf.staffix.impl.alerting;

import org.lolaf.staffix.api.alerting.FixSessionProbes;
import org.lolaf.staffix.api.alerting.Probe;

import java.util.ArrayList;
import java.util.List;

/**
 * Copy on write: probes are added or removed rarely, while the evaluation reads them every tick without locking.
 */
public final class FixSessionProbesImpl implements FixSessionProbes {

    private volatile List<Probe> probes = List.of();

    @Override
    public synchronized void add(Probe probe) {
        if (find(probe.getName()) != null) {
            throw new IllegalArgumentException("A probe named " + probe.getName() + " already exists in this session");
        }
        List<Probe> updated = new ArrayList<>(probes);
        updated.add(probe);
        probes = List.copyOf(updated);
    }

    @Override
    public synchronized boolean remove(String name) {
        Probe probe = find(name);
        if (probe == null) {
            return false;
        }
        List<Probe> updated = new ArrayList<>(probes);
        updated.remove(probe);
        probes = List.copyOf(updated);
        return true;
    }

    @Override
    public List<Probe> list() {
        return probes;
    }

    private Probe find(String name) {
        for (Probe probe : probes) {
            if (probe.getName().equals(name)) {
                return probe;
            }
        }
        return null;
    }
}
