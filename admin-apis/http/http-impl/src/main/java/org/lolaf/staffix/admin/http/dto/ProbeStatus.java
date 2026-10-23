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
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.AlertLevel;
import org.lolaf.staffix.api.alerting.ProbeState;

/**
 * Where a probe stood at its last evaluation; level, message and since are absent while it is normal. Times are
 * ISO-8601 instants.
 */
@Value
public class ProbeStatus {
    String probe;
    AlertLevel level;
    String message;
    String since;
    String lastEvaluation;
    boolean suspended;

    public static ProbeStatus of(ProbeState state) {
        Alert alert = state.getAlert().orElse(null);
        return new ProbeStatus(state.getProbeName(),
                alert == null ? null : alert.getLevel(),
                alert == null ? null : alert.getMessage(),
                state.getSince().map(Object::toString).orElse(null),
                state.getLastEvaluation().toString(),
                state.isSuspended());
    }
}
