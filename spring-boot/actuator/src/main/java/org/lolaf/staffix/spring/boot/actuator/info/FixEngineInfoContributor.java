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
package org.lolaf.staffix.spring.boot.actuator.info;

import org.lolaf.staffix.spring.boot.actuator.ActuatorSessionsRegistry;
import org.lolaf.staffix.spring.boot.actuator.spring.ActuatorMonitoringProps;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adds the engine's name and version to {@code /actuator/info}.
 */
public class FixEngineInfoContributor implements InfoContributor {

    private final ActuatorSessionsRegistry registry;
    private final ActuatorMonitoringProps props;

    public FixEngineInfoContributor(ActuatorSessionsRegistry registry, ActuatorMonitoringProps props) {
        this.registry = registry;
        this.props = props;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> staffix = new LinkedHashMap<>();
        staffix.put("monitoringInstanceId", props.getInstanceId());
        staffix.put("knownSessions", registry.snapshot().size());
        builder.withDetail("staffix", staffix);
    }
}
