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
package org.lolaf.staffix.stores.sessions.jdbc.spring;

import org.lolaf.staffix.api.session.FixSessionsSettingsStoreSettings;
import org.lolaf.staffix.spring.boot.spi.FixSessionsSettingsStoreSettingsContributor;
import org.lolaf.staffix.stores.sessions.jdbc.JdbcSessionsSettingsStoreSettings;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Contributes the JDBC session settings store's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class JdbcSessionsSettingsStoreContributor implements FixSessionsSettingsStoreSettingsContributor {

    private static final String PREFIX = "staffix.sessions-settings-stores-jdbc.instances.";

    private final JdbcSessionsSettingsStoreProps props;
    private final ApplicationContext ctx;

    public JdbcSessionsSettingsStoreContributor(JdbcSessionsSettingsStoreProps props, ApplicationContext ctx) {
        this.props = props;
        this.ctx = ctx;
    }

    @Override
    public void contribute(Map<String, FixSessionsSettingsStoreSettings> registry) {
        props.getInstances().forEach((mapKey, ip) -> {
            if (registry.putIfAbsent(mapKey, build(mapKey, ip)) != null) {
                throw new IllegalStateException(PREFIX + mapKey
                        + " collides with another sessions-settings-store contributor for the same key");
            }
        });
    }

    private JdbcSessionsSettingsStoreSettings build(String mapKey, JdbcStoreInstanceProps ip) {
        if (ip.getDataSourceBean() == null) {
            throw new IllegalArgumentException(PREFIX + mapKey + ".data-source-bean is required");
        }
        JdbcSessionsSettingsStoreSettings.JdbcSessionsSettingsStoreSettingsBuilder builder = JdbcSessionsSettingsStoreSettings.builder()
                .instanceId(mapKey)
                .dataSource(ctx.getBean(ip.getDataSourceBean(), DataSource.class));
        if (ip.getTablePrefix() != null) {
            builder.tablePrefix(ip.getTablePrefix());
        }
        return builder.build();
    }
}
