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
import org.lolaf.staffix.api.alerting.AlertLevel;

import java.time.Instant;

/**
 * One entry of {@code GET alerts}: a probe in alert, with the session it belongs to named as in
 * {@link EngineSessions}, so the console can place it without another call, or the engine component it belongs to.
 * Exactly one of {@code component} and the session's {@code group}, {@code name} and {@code selectedConfig} is present.
 */
@Value
public class ActiveAlert {
    /**
     * The engine component the probe belongs to, such as {@code ha}.
     */
    String component;
    String group;
    /**
     * The main config's name, unique within the group.
     */
    String name;
    /**
     * The config the alert was raised on; differs from the name when an initiator runs a backup.
     */
    String selectedConfig;
    /**
     * The probe's name, unique within the session; its description is in {@code GET sessions/{group}/{name}/probes}.
     */
    String probe;
    AlertLevel level;
    /**
     * The ongoing problem, as the probe last described it; it may change without the level changing.
     */
    String message;
    /**
     * When the alert was raised; kept across level changes.
     */
    Instant since;
    /**
     * The probe's runbook, absent when it has none.
     */
    String runbookUrl;
}
