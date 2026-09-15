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
package org.lolaf.staffix.examples;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

@Slf4j
@UtilityClass
public class EmbeddedDbSetup {

    public static DataSource setupDatabase() {

        PostgreSQLContainer postgres =
                new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                        .withDatabaseName("staffix-examples")
                        .withUsername("example")
                        .withPassword("example");
        postgres.start();

        log.info("PostgreSQL started: {}", postgres.getJdbcUrl());

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(10);
        HikariDataSource dataSource = new HikariDataSource(config);
        try {
            initializeDbSchema(dataSource);
        } catch (SQLException e) {
            throw new IllegalStateException();
        }
        return dataSource;
    }

    private static void initializeDbSchema(DataSource dataSource) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             InputStream schemaStream = FixExamplesBase.class.getResourceAsStream("/db/schema.sql")) {

            String schemaSql = new BufferedReader(new InputStreamReader(schemaStream, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
            stmt.execute(schemaSql);
        } catch (IOException exception) {
            throw new SQLException("Unexpected IOException", exception);
        }
    }
}
