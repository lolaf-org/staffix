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

import lombok.Getter;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeDefinition;
import org.lolaf.staffix.api.alerting.ProbeScope;
import org.lolaf.staffix.api.alerting.Probes;
import org.lolaf.staffix.api.alerting.ProbesListener;

import java.util.Arrays;
import java.util.List;

/**
 * Copy on write: probes are added or removed rarely, while the evaluation reads them every tick without locking or
 * allocating. The listener is told under the same lock as the change, so it never sees two changes out of order.
 */
public final class ProbesImpl implements Probes {

    private static final Probe[] NO_PROBES = new Probe[0];

    @Getter
    private final ProbeScope scope;
    private volatile Probe[] probes = NO_PROBES;
    private ProbesListener listener;

    public ProbesImpl(ProbeScope scope) {
        this.scope = scope;
    }

    @Override
    public synchronized void add(Probe probe) {
        if (indexOf(probe.getName()) >= 0) {
            throw new IllegalArgumentException("A probe named " + probe.getName() + " already exists in " + scope);
        }
        for (String labelName : probe.getLabels().keySet()) {
            if (scope.getLabels().containsKey(labelName)) {
                throw new IllegalArgumentException("Probe " + probe.getName() + " label " + labelName + " is a label of its scope " + scope);
            }
        }
        Probe[] updated = Arrays.copyOf(probes, probes.length + 1);
        updated[probes.length] = probe;
        probes = updated;
        if (listener != null) {
            listener.onProbeAdded(scope, ProbeDefinition.of(probe));
        }
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
        if (listener != null) {
            listener.onProbeRemoved(scope, name);
        }
        return true;
    }

    @Override
    public List<Probe> list() {
        return List.of(probes);
    }

    /**
     * Tells the listener about every probe already present, then about each change.
     */
    synchronized void attach(ProbesListener probesListener) {
        listener = probesListener;
        for (Probe probe : probes) {
            listener.onProbeAdded(scope, ProbeDefinition.of(probe));
        }
    }

    /**
     * Tells the listener every probe is gone, and stops telling it anything.
     */
    synchronized void detach() {
        if (listener == null) {
            return;
        }
        for (Probe probe : probes) {
            listener.onProbeRemoved(scope, probe.getName());
        }
        listener = null;
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
