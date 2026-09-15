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
package org.lolaf.staffix.stores.sessions.file.spring;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.session.ConfigValueResolver;
import org.lolaf.staffix.api.session.ConfigValueResolverChain;
import org.lolaf.staffix.api.session.FixSessionsSettingsStoreSettings;
import org.lolaf.staffix.stores.sessions.file.FileSessionsSettingsStoreSettings;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class TestFileStoreConfigValueResolvers {

    @Test
    void ordersTheBuiltInsBeforeSpringsEnvironment() {
        List<ConfigValueResolver> resolvers = contribute(new GenericApplicationContext(),
                Collections.emptyList()).getConfigValueResolvers();

        assertThat(resolvers).hasSize(3);
        assertThat(resolvers.get(0)).isInstanceOf(ConfigValueResolver.SystemPropertyConfigValueResolver.class);
        assertThat(resolvers.get(1)).isInstanceOf(ConfigValueResolver.EnvironmentVariableConfigValueResolver.class);
        assertThat(resolvers.get(2)).isInstanceOf(EnvironmentConfigValueResolver.class);
    }

    /**
     * The reason the environment goes last: it answers for any key it holds, including the system
     * properties the prefixed resolver owns.
     */
    @Test
    void resolvesFromTheEnvironmentWhenNoBuiltInAnswers() {
        ConfigValueResolverChain chain =
                new ConfigValueResolverChain(contribute(new GenericApplicationContext(),
                        Collections.emptyList()).getConfigValueResolvers());

        assertThat(chain.resolve("${from.application.yaml}", "session.yaml")).isEqualTo("bound");
        assertThat(chain.resolve("${missing.everywhere:fallback}", "session.yaml")).isEqualTo("fallback");
    }

    @Test
    void putsANamedBeanBeforeTheEnvironment() {
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.registerBean("myResolver", ConfigValueResolver.class,
                () -> placeholder -> java.util.Optional.of("from-bean"));
        ctx.refresh();

        ConfigValueResolverChain chain = new ConfigValueResolverChain(
                contribute(ctx, List.of("myResolver")).getConfigValueResolvers());

        assertThat(chain.resolve("${from.application.yaml}", "session.yaml")).isEqualTo("from-bean");
    }

    private FileSessionsSettingsStoreSettings contribute(GenericApplicationContext ctx,
                                                         List<String> resolverBeans) {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> bound = new HashMap<>();
        bound.put("from.application.yaml", "bound");
        environment.getPropertySources().addFirst(new MapPropertySource("test", bound));

        FileStoreInstanceProps instance = new FileStoreInstanceProps();
        instance.setDirectory("target/sessions");
        instance.setConfigValueResolverBeans(resolverBeans);
        FileSessionsSettingsStoreProps props = new FileSessionsSettingsStoreProps();
        props.getInstances().put("test", instance);

        Map<String, FixSessionsSettingsStoreSettings> registry = new LinkedHashMap<>();
        new FileSessionsSettingsStoreContributor(props, ctx, environment).contribute(registry);
        return (FileSessionsSettingsStoreSettings) registry.get("test");
    }
}
