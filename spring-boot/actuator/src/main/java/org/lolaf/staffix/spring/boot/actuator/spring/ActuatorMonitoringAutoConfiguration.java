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

import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.spring.boot.actuator.ActuatorMonitoringManager;
import org.lolaf.staffix.spring.boot.actuator.ActuatorSessionsRegistry;
import org.lolaf.staffix.spring.boot.actuator.endpoint.FixSessionsEndpoint;
import org.lolaf.staffix.spring.boot.actuator.health.FixSessionsHealthIndicator;
import org.lolaf.staffix.spring.boot.actuator.info.FixEngineInfoContributor;
import org.lolaf.staffix.spring.boot.spi.FixSessionSettingsPostProcessor;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * Auto-configuration for the Actuator integration, active when Actuator is on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(Endpoint.class)
@ConditionalOnProperty(prefix = "staffix.actuator", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ActuatorMonitoringProps.class)
public class ActuatorMonitoringAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ActuatorSessionsRegistry staffixActuatorSessionsRegistry() {
        return new ActuatorSessionsRegistry();
    }

    @Bean
    public ActuatorMonitoringContributor staffixActuatorMonitoringContributor(ActuatorMonitoringProps props,
                                                                              ActuatorSessionsRegistry registry) {
        return new ActuatorMonitoringContributor(props, registry);
    }

    @Bean
    @ConditionalOnAvailableEndpoint(endpoint = FixSessionsEndpoint.class)
    public FixSessionsEndpoint staffixFixSessionsEndpoint(ActuatorSessionsRegistry registry) {
        return new FixSessionsEndpoint(registry);
    }

    @Bean
    public FixSessionsHealthIndicator staffixFixSessionsHealthIndicator(ActuatorSessionsRegistry registry,
                                                                        ActuatorMonitoringProps props) {
        return new FixSessionsHealthIndicator(registry, props);
    }

    @Bean
    public FixEngineInfoContributor staffixFixEngineInfoContributor(ActuatorSessionsRegistry registry,
                                                                    ActuatorMonitoringProps props) {
        return new FixEngineInfoContributor(registry, props);
    }

    @Bean
    public FixSessionSettingsPostProcessor staffixActuatorSessionSettingsPostProcessor(ActuatorMonitoringProps props) {
        return new FixSessionSettingsPostProcessor() {
            @Override
            public int order() {
                return 100;
            }

            @Override
            public org.lolaf.staffix.api.session.FixSessionSettings postProcess(org.lolaf.staffix.api.session.FixSessionSettings settings) {
                if (!props.isEnabled()) {
                    return settings;
                }
                Class<? extends FixSessionsPlugin<?>> key = ActuatorMonitoringManager.class;
                Map<Class<? extends FixSessionsPlugin<?>>, String> existing = settings.getFixSessionPluginsInstanceIds();
                if (existing != null && existing.containsKey(key)) {
                    return settings;
                }
                return settings.toBuilder()
                        .fixSessionPluginsInstanceId(key, props.getInstanceId())
                        .build();
            }
        };
    }
}
