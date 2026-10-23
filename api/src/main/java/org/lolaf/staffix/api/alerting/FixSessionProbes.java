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

import java.util.List;

/**
 * The probes of one session, added and removed at runtime from any thread. They live as long as the session: a
 * session rebuilt after a settings change starts with none, which is why an application adds its probes in
 * {@link org.lolaf.staffix.api.application.FixApplication#onSessionCreated}, the first callback where an alerting meter
 * can read the session's monitoring. A probe need not be removed on logout: unless
 * {@link Probe#isOnlyWhileLoggedIn()} says otherwise, it is suspended until the next logon.
 */
public interface FixSessionProbes {

    /**
     * Starts evaluating the probe on the next tick.
     *
     * @throws IllegalArgumentException if the session already has a probe of that name
     */
    void add(Probe probe);

    /**
     * Adds a probe evaluated by {@code evaluator}, with the defaults of {@link Probe}, so only while logged in, and
     * returns the evaluator, so an alerting meter such as {@link AlertingTimer} is kept with its own type. Use
     * {@link #add(Probe)} for labels, a runbook, other intervals or a probe that runs while logged out.
     *
     * @throws IllegalArgumentException if the session already has a probe of that name
     */
    default <T extends ProbeEvaluator> T watch(String name, String description, T evaluator) {
        add(Probe.builder().name(name).description(description).evaluator(evaluator).build());
        return evaluator;
    }

    /**
     * Stops evaluating the probe; an alert it had ongoing is published as cleared.
     *
     * @return false if the session has no probe of that name
     */
    boolean remove(String name);

    /**
     * The probes in the order they were added, as an immutable snapshot.
     */
    List<Probe> list();
}
