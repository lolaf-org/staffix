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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.alerting.ProbeDefinition;
import org.lolaf.staffix.api.alerting.ProbeState;
import org.lolaf.staffix.api.alerting.ProbesListener;
import org.lolaf.staffix.api.session.FixSessionId;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Fans out to the registered listeners, so one failing does not keep the others from being told.
 */
@Slf4j
public final class ProbesListeners implements ProbesListener {

    private final List<ProbesListener> listeners = new CopyOnWriteArrayList<>();

    public void add(ProbesListener listener) {
        listeners.add(listener);
    }

    public void remove(ProbesListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void onProbeAdded(FixSessionId fixSessionId, ProbeDefinition probe) {
        forEach(l -> l.onProbeAdded(fixSessionId, probe));
    }

    @Override
    public void onProbeRemoved(FixSessionId fixSessionId, String probeName) {
        forEach(l -> l.onProbeRemoved(fixSessionId, probeName));
    }

    @Override
    public void onAlertTransition(FixSessionId fixSessionId, ProbeState state) {
        forEach(l -> l.onAlertTransition(fixSessionId, state));
    }

    private void forEach(Consumer<ProbesListener> call) {
        for (ProbesListener listener : listeners) {
            try {
                call.accept(listener);
            } catch (RuntimeException e) {
                log.warn("Probes listener {} failed", listener, e);
            }
        }
    }
}
