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
package org.lolaf.staffix.alerting.slf4j;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.lolaf.staffix.api.alerting.AbstractFixAlertsPublisherSettings;
import org.lolaf.staffix.api.session.FixSessionId;

import java.util.function.Function;

/**
 * The logger name an alert is logged under. The level follows the alert: warn for a warning, error for major and
 * critical, info once cleared.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class Slf4jAlertsPublisherSettings extends AbstractFixAlertsPublisherSettings {

    /**
     * Chooses the logger name per session, so one session's alerts can be routed or silenced independently.
     */
    @Builder.Default
    private Function<FixSessionId, String> loggerNameForFixSession = s -> Slf4jAlertsPublisher.class.getName();
    /**
     * Whether the session's group and name are put in the MDC ({@code fixSessionGroup}, {@code fixSessionName}), the
     * same keys the SLF4J message logger uses.
     */
    @Builder.Default
    private boolean useMDC = true;
}
