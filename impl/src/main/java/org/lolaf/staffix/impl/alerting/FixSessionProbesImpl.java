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

import java.util.Arrays;
import java.util.List;

/**
 * Copy on write: probes are added or removed rarely, while the evaluation reads them every tick without locking or
 * allocating.
 */
public final class FixSessionProbesImpl implements FixSessionProbes {

    private static final Probe[] NO_PROBES = new Probe[0];

    private volatile Probe[] probes = NO_PROBES;

    @Override
    public synchronized void add(Probe probe) {
        if (indexOf(probe.getName()) >= 0) {
            throw new IllegalArgumentException("A probe named " + probe.getName() + " already exists in this session");
        }
        Probe[] updated = Arrays.copyOf(probes, probes.length + 1);
        updated[probes.length] = probe;
        probes = updated;
    }

    @Override
    public synchronized boolean remove(String name) {
        int index = indexOf(name);
        if (index < 0) {
            return false;
        }
        Probe[] updated = new Probe[probes.length - 1];
        System.arraycopy(probes, 0, updated, 0, index);
        System.arraycopy(probes, index + 1, updated, index, updated.length - index);
        probes = updated;
        return true;
    }

    @Override
    public List<Probe> list() {
        return List.of(probes);
    }

    /**
     * Never modified, replaced on every change, so a caller can tell a change by reference.
     */
    Probe[] array() {
        return probes;
    }

    private int indexOf(String name) {
        Probe[] current = probes;
        for (int i = 0; i < current.length; i++) {
            if (current[i].getName().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
