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
package org.lolaf.staffix.stores.sessions.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import java.time.Duration;

@Testcontainers
class TestOracleJdbcFixSessionsSettingsStore extends AbstractJdbcFixSessionsSettingsStoreTest {

    /**
     * As for the messages store: the image can take minutes to report ready on a CI runner.
     */
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

    @Container
    private static final OracleContainer oracle = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withStartupTimeout(STARTUP_TIMEOUT);

    @Override
    String schemaResourceName() {
        return "schema-oracle.sql";
    }

    @Override
    HikariDataSource newDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(oracle.getJdbcUrl());
        config.setUsername(oracle.getUsername());
        config.setPassword(oracle.getPassword());
        config.setMaximumPoolSize(4);
        return new HikariDataSource(config);
    }
}
