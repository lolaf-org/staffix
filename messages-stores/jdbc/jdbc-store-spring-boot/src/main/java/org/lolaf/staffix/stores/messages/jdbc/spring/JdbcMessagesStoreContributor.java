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
package org.lolaf.staffix.stores.messages.jdbc.spring;

import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.spring.boot.spi.FixMessagesStoreSettingsContributor;
import org.lolaf.staffix.stores.messages.jdbc.JdbcMessageStoreSettings;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Contributes the JDBC message store's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class JdbcMessagesStoreContributor implements FixMessagesStoreSettingsContributor {

    private final JdbcMessagesStoreProps props;
    private final ApplicationContext ctx;

    public JdbcMessagesStoreContributor(JdbcMessagesStoreProps props, ApplicationContext ctx) {
        this.props = props;
        this.ctx = ctx;
    }

    @Override
    public void contribute(Map<String, FixMessagesStoreSettings> registry) {
        props.getInstances().forEach((key, p) -> {
            if (registry.putIfAbsent(key, build(key, p)) != null) {
                throw new IllegalStateException("staffix.messages-stores-jdbc.instances." + key
                        + " collides with another store contributor for the same key");
            }
        });
    }

    private FixMessagesStoreSettings build(String mapKey, JdbcStoreEntryProps p) {
        if (p.getDataSourceBean() == null) {
            throw new IllegalArgumentException("staffix.messages-stores-jdbc.instances." + mapKey
                    + ".data-source-bean is required");
        }
        DataSource ds = ctx.getBean(p.getDataSourceBean(), DataSource.class);
        JdbcMessageStoreSettings.JdbcMessageStoreSettingsBuilder b = JdbcMessageStoreSettings.builder()
                .instanceId(mapKey)
                .dataSource(ds);
        if (p.getTablePrefix() != null) b.tablePrefix(p.getTablePrefix());
        if (p.getMaxMessagesPerSession() != null) b.maxMessagesPerSession(p.getMaxMessagesPerSession());
        if (p.getPruningCheckInterval() != null) b.pruningCheckInterval(p.getPruningCheckInterval());
        return b.build();
    }
}
