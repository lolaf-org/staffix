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
package org.lolaf.staffix.benchmarks.impl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.stores.messages.file.FileMessageStoreSettings;
import org.lolaf.staffix.stores.messages.jdbc.JdbcMessageStoreSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Where a benchmark's follower stores what it replicates, and what that storage needs around it: a directory to
 * delete, or a PostgreSQL container to run.
 */
public final class FollowerStorage implements AutoCloseable {

    /**
     * Memory; a file written through on every message; PostgreSQL in a container, which needs Docker.
     */
    public enum Kind {
        MEMORY,
        FILE,
        JDBC
    }

    private final FixMessagesStoreSettings settings;
    private Path directory;
    private PostgreSQLContainer postgres;
    private HikariDataSource dataSource;

    public FollowerStorage(Kind kind) {
        switch (kind) {
            case FILE:
                directory = createDirectory();
                settings = FileMessageStoreSettings.builder().storageDirectoryPath(directory.toString()).build();
                break;
            case JDBC:
                settings = JdbcMessageStoreSettings.builder().dataSource(startPostgres()).build();
                break;
            default:
                settings = MemoryMessageStoreSettings.builder().build();
        }
    }

    public FixMessagesStoreSettings settings() {
        return settings;
    }

    private static Path createDirectory() {
        try {
            return Files.createTempDirectory("ha-follower");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HikariDataSource startPostgres() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withDatabaseName("staffix-benchmark")
                .withUsername("benchmark")
                .withPassword("benchmark");
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);
        createSchema();
        return dataSource;
    }

    private void createSchema() {
        try (InputStream schema = FollowerStorage.class.getResourceAsStream("/db/schema-postgres.sql");
             Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : new String(schema.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Cannot create the follower's schema", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (dataSource != null) {
            dataSource.close();
            postgres.stop();
        }
        if (directory != null) {
            try (Stream<Path> files = Files.walk(directory)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }
}
