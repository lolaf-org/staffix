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
package org.lolaf.staffix.plugins.throttling.spring;

import org.lolaf.staffix.plugins.throttling.ThrottlingFixSessionsPluginSettings;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for the throttling session plugin wrapper, active when this module is on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(ThrottlingFixSessionsPluginSettings.class)
@ConditionalOnProperty(prefix = "staffix", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ThrottlingPluginProps.class)
public class ThrottlingPluginAutoConfiguration {

    @Bean
    public ThrottlingPluginContributor staffixThrottlingPluginContributor(ThrottlingPluginProps props) {
        return new ThrottlingPluginContributor(props);
    }
}
