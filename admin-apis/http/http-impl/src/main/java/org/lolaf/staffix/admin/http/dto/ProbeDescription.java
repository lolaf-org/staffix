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
package org.lolaf.staffix.admin.http.dto;

import lombok.Value;
import org.lolaf.staffix.api.alerting.ProbeDefinition;

/**
 * A probe of a session, as it was added; intervals are ISO-8601 durations.
 */
@Value
public class ProbeDescription {
    String name;
    String description;
    String evaluationInterval;
    /**
     * Absent for a probe that publishes transitions only.
     */
    String reminderInterval;

    public static ProbeDescription of(ProbeDefinition probe) {
        return new ProbeDescription(probe.getName(), probe.getDescription(), probe.getEvaluationInterval().toString(),
                probe.getReminderInterval().map(Object::toString).orElse(null));
    }
}
