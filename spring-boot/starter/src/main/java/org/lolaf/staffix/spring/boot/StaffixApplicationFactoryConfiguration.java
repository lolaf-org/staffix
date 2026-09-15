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
package org.lolaf.staffix.spring.boot;

import org.lolaf.staffix.application.factories.spring.SpringApplicationFactorySettings;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.lolaf.staffix.spring.boot.spi.StaffixApplicationFactoryConstants.SPRING_FACTORY_INSTANCE_ID;

/**
 * Wires the Spring application factory, so a session's {@code FixApplication} is a bean with its dependencies
 * injected rather than a class instantiated by name.
 */
@Configuration(proxyBeanMethods = false)
public class StaffixApplicationFactoryConfiguration {

    @Bean
    public SpringApplicationFactorySettings staffixSpringApplicationFactory(ApplicationContext ctx) {
        return SpringApplicationFactorySettings.builder()
                .instanceId(SPRING_FACTORY_INSTANCE_ID)
                .applicationContext(ctx)
                .build();
    }
}
