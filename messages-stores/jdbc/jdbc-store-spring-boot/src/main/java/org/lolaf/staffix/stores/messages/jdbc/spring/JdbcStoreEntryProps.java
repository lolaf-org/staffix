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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

import java.time.Duration;

/**
 * One configured instance of the JDBC message store: its instance id and the settings a session naming that id gets.
 */
@Data
@ConfigurationPropertiesSource
public class JdbcStoreEntryProps {
    /**
     * Spring bean name of the {@link javax.sql.DataSource} to use. Resolved via
     * {@code ApplicationContext.getBean(name, DataSource.class)} at startup.
     */
    private String dataSourceBean;
    /**
     * Prefix on the table names, so several engines can share one schema.
     */
    private String tablePrefix;
    /**
     * How many messages a session retains before the oldest are pruned.
     */
    private Integer maxMessagesPerSession;
    /**
     * How often pruning runs.
     */
    private Duration pruningCheckInterval;
}
