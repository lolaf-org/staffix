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
package org.lolaf.staffix.alerting.slf4j.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

/**
 * One configured instance of the SLF4J alerts publisher.
 */
@Data
@ConfigurationPropertiesSource
public class Slf4jAlertsPublisherEntryProps {
    /**
     * Whether the session's group and name are put in the MDC ({@code fixSessionGroup}, {@code fixSessionName}), the
     * same keys the SLF4J message logger uses.
     */
    private Boolean useMDC;

    /**
     * Spring bean name of Function from ProbeScope to String that derives the SLF4J logger name per probe scope.
     */
    private String loggerNameForScopeBean;
}
