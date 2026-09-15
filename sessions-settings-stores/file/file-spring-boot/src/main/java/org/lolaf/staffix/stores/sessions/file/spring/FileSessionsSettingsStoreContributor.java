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

import org.lolaf.staffix.api.session.ConfigValueResolver;
import org.lolaf.staffix.api.session.FixSessionsSettingsStoreSettings;
import org.lolaf.staffix.spring.boot.spi.FixSessionsSettingsStoreSettingsContributor;
import org.lolaf.staffix.stores.sessions.file.FileSessionsSettingsStoreSettings;

import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Contributes the YAML session settings store's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class FileSessionsSettingsStoreContributor implements FixSessionsSettingsStoreSettingsContributor {

    private final FileSessionsSettingsStoreProps props;
    private final ApplicationContext ctx;
    private final Environment environment;

    public FileSessionsSettingsStoreContributor(FileSessionsSettingsStoreProps props,
                                                ApplicationContext ctx, Environment environment) {
        this.props = props;
        this.ctx = ctx;
        this.environment = environment;
    }

    private List<ConfigValueResolver> configValueResolvers(FileStoreInstanceProps ip) {
        List<ConfigValueResolver> resolvers = new ArrayList<>();
        resolvers.add(ConfigValueResolver.SystemPropertyConfigValueResolver.getInstance());
        resolvers.add(ConfigValueResolver.EnvironmentVariableConfigValueResolver.getInstance());
        ip.getConfigValueResolverBeans()
                .forEach(bean -> resolvers.add(ctx.getBean(bean, ConfigValueResolver.class)));
        resolvers.add(new EnvironmentConfigValueResolver(environment));
        return resolvers;
    }

    @Override
    public void contribute(Map<String, FixSessionsSettingsStoreSettings> registry) {
        props.getInstances().forEach((mapKey, ip) -> {
            boolean hasDirectory = ip.getDirectory() != null && !ip.getDirectory().isBlank();
            if (hasDirectory == !ip.getUris().isEmpty()) {
                throw new IllegalArgumentException("staffix.sessions-settings-stores-file.instances."
                        + mapKey + " needs exactly one of directory or uris");
            }
            FileSessionsSettingsStoreSettings settings = FileSessionsSettingsStoreSettings.builder()
                    .instanceId(mapKey)
                    .fixSessionSettingsDirectory(hasDirectory ? new File(ip.getDirectory()) : null)
                    .fixSessionSettingsUris(ip.getUris())
                    .configValueResolvers(configValueResolvers(ip))
                    .build();
            if (registry.putIfAbsent(mapKey, settings) != null) {
                throw new IllegalStateException("staffix.sessions-settings-stores-file.instances." + mapKey
                        + " collides with another sessions-settings-store contributor for the same key");
            }
        });
    }
}
