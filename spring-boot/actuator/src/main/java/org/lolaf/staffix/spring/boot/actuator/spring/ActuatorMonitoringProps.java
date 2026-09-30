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
package org.lolaf.staffix.spring.boot.actuator.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Actuator integration's {@code staffix.*} properties.
 */
@Data
@ConfigurationProperties(prefix = "staffix.actuator")
public class ActuatorMonitoringProps {

    /**
     * Whether the staffix actuator monitoring contributor is registered with the FIX engine.
     */
    private boolean enabled = true;

    /**
     * Plugin instance id used as the key in the FIX engine plugin registry.
     */
    private String instanceId = "actuator-monitoring";

    /**
     * Whether a FIX session that should be logged in and is not turns the health status DOWN. A session outside its
     * schedule or logged out on purpose never does. Off by default, since a counterparty outage is not a reason for a
     * liveness probe to restart the application.
     */
    private boolean fixSessionStateContributesToHealthStatus;
}