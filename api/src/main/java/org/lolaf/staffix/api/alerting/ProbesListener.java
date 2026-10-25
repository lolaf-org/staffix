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
package org.lolaf.staffix.api.alerting;

/**
 * Told about the probes of every scope a source evaluates, so an admin tool can keep its own view current instead of
 * polling. Called synchronously on the thread that caused the change: it must only update its own state and return.
 * An exception is logged and the other listeners are still called.
 */
public interface ProbesListener {

    /**
     * Called on the thread adding the probe, and for every probe a scope already has when its evaluation starts.
     */
    void onProbeAdded(ProbeScope scope, ProbeDefinition probe);

    /**
     * Called on the thread removing the probe, and for every probe of a scope no longer evaluated. A probe
     * removed while in alert still gets its cleared {@link #onAlertTransition} at the next evaluation tick.
     */
    void onProbeRemoved(ProbeScope scope, String probeName);

    /**
     * Called on the probes thread when a probe's alert is raised, changes level or clears; neither a reminder nor a
     * change of message alone is a transition.
     */
    void onAlertTransition(ProbeScope scope, ProbeState state);
}
