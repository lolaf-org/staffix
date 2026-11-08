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
package org.lolaf.staffix.spring.boot.spi;

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.session.ConfigValueResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * The placeholder resolvers of a session settings store configured in Spring.
 */
@UtilityClass
public class SpringConfigValueResolvers {

    /**
     * @param beanNames extra resolvers, asked after the system property and environment ones and before Spring's own
     *                  environment, which is unnamed and so would answer in their place
     */
    public static List<ConfigValueResolver> of(ApplicationContext context, Environment environment, List<String> beanNames) {
        List<ConfigValueResolver> resolvers = new ArrayList<>();
        resolvers.add(ConfigValueResolver.SystemPropertyConfigValueResolver.getInstance());
        resolvers.add(ConfigValueResolver.EnvironmentVariableConfigValueResolver.getInstance());
        beanNames.forEach(bean -> resolvers.add(context.getBean(bean, ConfigValueResolver.class)));
        resolvers.add(new EnvironmentConfigValueResolver(environment));
        return resolvers;
    }
}
