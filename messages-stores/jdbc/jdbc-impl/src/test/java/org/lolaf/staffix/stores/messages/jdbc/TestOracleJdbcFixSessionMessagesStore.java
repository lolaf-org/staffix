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
package org.lolaf.staffix.stores.messages.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import java.time.Duration;

@Testcontainers
public class TestOracleJdbcFixSessionMessagesStore extends AbstractJdbcFixSessionMessagesStore {

    /**
     * The image has to bring a database up before it says "DATABASE IS READY TO USE!", which is what Testcontainers
     * waits for, and on a CI runner that has taken longer than the default deadline allows - the container is then
     * torn down and retried, and the retries run out. Failing here costs far more than the wait: this module is built
     * before staffix-impl, so the whole FIX suite is skipped behind it.
     */
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

    @Container
    private static final OracleContainer oracle = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withStartupTimeout(STARTUP_TIMEOUT);

    @Override
    String getDbSchemaResourceName() {
        return "schema-oracle.sql";
    }

    @Override
    String transformSchemaInitStatement(String sql) {
        if (sql.contains("create index")) {
            return "";
        }
        return sql;
    }

    @Override
    HikariDataSource getDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(oracle.getJdbcUrl());
        config.setUsername(oracle.getUsername());
        config.setPassword(oracle.getPassword());
        config.setMaximumPoolSize(10);
        return new HikariDataSource(config);
    }
}