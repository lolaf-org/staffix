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

import org.lolaf.staffix.spring.boot.props.StaffixProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnProperty(prefix = "staffix", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(StaffixProperties.class)
@Import({
        StaffixApplicationFactoryConfiguration.class,
        StaffixEngineConfiguration.class,
        StaffixDynamicBeansRegistrar.class
})
/**
 * The starter's entry point: builds an engine from {@code staffix.*} properties and manages it with the
 * application context.
 *
 * <p>Backs off entirely if the application defines its own engine, so the starter is a default rather than a
 * constraint.
 */
public class StaffixAutoConfiguration {
}
