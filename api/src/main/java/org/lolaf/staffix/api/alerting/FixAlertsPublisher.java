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

import org.lolaf.staffix.api.InstanceIdSupplier;
import org.lolaf.staffix.api.Startable;

/**
 * Where the alerts of the sessions bound to it go. One instance serves every such session of an engine; publishing to
 * several destinations is the job of a publisher that forwards to others.
 */
public interface FixAlertsPublisher extends InstanceIdSupplier, Startable<FixAlertsPublisher> {

    /**
     * Called on the engine's probes thread, one event at a time: a slow publisher delays the next evaluations. An
     * exception is logged and the event dropped.
     */
    void publish(AlertEvent event);
}
