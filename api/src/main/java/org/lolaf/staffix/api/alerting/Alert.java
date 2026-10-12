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

import lombok.NonNull;
import lombok.Value;

/**
 * An ongoing problem reported by a {@link ProbeEvaluator}.
 */
@Value
public class Alert {

    /**
     * How serious the problem is; a change of level is published, see {@link AlertEvent.Type#CHANGED}.
     */
    @NonNull
    AlertLevel level;
    /**
     * Describes the problem as it stands, e.g. the current queue depth. A change of message alone is not published,
     * so it may carry a value that moves at every evaluation.
     */
    @NonNull
    String message;

    /**
     * A {@link AlertLevel#WARNING} alert.
     */
    public static Alert warning(String message) {
        return new Alert(AlertLevel.WARNING, message);
    }

    /**
     * A {@link AlertLevel#MAJOR} alert.
     */
    public static Alert major(String message) {
        return new Alert(AlertLevel.MAJOR, message);
    }

    /**
     * A {@link AlertLevel#CRITICAL} alert.
     */
    public static Alert critical(String message) {
        return new Alert(AlertLevel.CRITICAL, message);
    }
}
