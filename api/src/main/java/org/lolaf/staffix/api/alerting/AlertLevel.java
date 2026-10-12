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
 * How serious an ongoing problem is, in increasing order, so the worst of several alerts is the greatest. Named after
 * the ITU X.733 alarm severities; a healthy probe has no alert rather than a level of its own.
 */
public enum AlertLevel {
    /**
     * Degraded but still working; worth watching before it gets worse.
     */
    WARNING,
    /**
     * Service is impaired and needs attention.
     */
    MAJOR,
    /**
     * Service is lost or about to be; needs immediate action.
     */
    CRITICAL
}
