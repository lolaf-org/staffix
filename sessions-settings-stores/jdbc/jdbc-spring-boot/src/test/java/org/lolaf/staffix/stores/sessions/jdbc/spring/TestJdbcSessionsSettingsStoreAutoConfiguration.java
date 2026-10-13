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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.session.FixSessionsSettingsStoreSettings;
import org.lolaf.staffix.stores.sessions.jdbc.JdbcSessionsSettingsStoreSettings;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TestJdbcSessionsSettingsStoreAutoConfiguration {

    private final DataSource dataSource = mock(DataSource.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JdbcSessionsSettingsStoreAutoConfiguration.class))
            .withBean("dataSource", DataSource.class, () -> dataSource);

    @Test
    void bindsTheDataSourceBeanAndTheTablePrefix() {
        runner.withPropertyValues("staffix.sessions-settings-stores-jdbc.instances.MAIN.data-source-bean=dataSource",
                        "staffix.sessions-settings-stores-jdbc.instances.MAIN.table-prefix=desk")
                .run(context -> {
                    JdbcSessionsSettingsStoreSettings settings =
                            contribute(context.getBean(JdbcSessionsSettingsStoreContributor.class));

                    assertThat(settings.getInstanceId()).isEqualTo("MAIN");
                    assertThat(settings.getDataSource()).isSameAs(dataSource);
                    assertThat(settings.getTablePrefix()).isEqualTo("desk");
                });
    }

    @Test
    void requiresTheDataSourceBean() {
        runner.withPropertyValues("staffix.sessions-settings-stores-jdbc.instances.MAIN.table-prefix=desk")
                .run(context -> assertThatThrownBy(() -> contribute(context.getBean(JdbcSessionsSettingsStoreContributor.class)))
                        .hasMessageContaining("MAIN.data-source-bean is required"));
    }

    private static JdbcSessionsSettingsStoreSettings contribute(JdbcSessionsSettingsStoreContributor contributor) {
        Map<String, FixSessionsSettingsStoreSettings> registry = new HashMap<>();
        contributor.contribute(registry);
        return (JdbcSessionsSettingsStoreSettings) registry.get("MAIN");
    }
}
