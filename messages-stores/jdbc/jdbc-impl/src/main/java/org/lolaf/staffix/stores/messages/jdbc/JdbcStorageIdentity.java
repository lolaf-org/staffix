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

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Optional;

/**
 * Identifies the tables a JDBC store writes to, without a table of its own: the database server's own id where it
 * can be read, else the connection URL, then the catalog, the schema and the table prefix. The server's id wins
 * because two nodes may reach one database through different host names. Hashed, as a URL may hold credentials.
 *
 * <p>Read once, on first use; a failure is not kept, so the next call tries again.
 */
@Slf4j
final class JdbcStorageIdentity {

    private final DataSource dataSource;
    private final String tablePrefix;
    private final @Nullable String configured;
    private volatile Optional<String> identity = Optional.empty();

    JdbcStorageIdentity(JdbcMessageStoreSettings settings) {
        this.dataSource = settings.getDataSource();
        this.tablePrefix = settings.getTablePrefix();
        this.configured = settings.getStorageIdentity();
    }

    Optional<String> get() {
        if (configured != null) {
            return Optional.of(configured);
        }
        if (identity.isEmpty()) {
            identity = read();
        }
        return identity;
    }

    private Optional<String> read() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String serverId = serverId(connection, metaData.getDatabaseProductName());
            String database = serverId == null ? metaData.getURL() : metaData.getDatabaseProductName() + " " + serverId;
            return Optional.of("jdbc:" + sha256(String.join("|", database, connection.getCatalog(), connection.getSchema(), tablePrefix)));
        } catch (SQLException | RuntimeException e) {
            log.warn("Cannot read the storage identity of the JDBC store", e);
            return Optional.empty();
        }
    }

    /**
     * Best effort: a query the database or the user's privileges refuse leaves the URL to identify it.
     */
    private static @Nullable String serverId(Connection connection, String productName) throws SQLException {
        String query = serverIdQuery(productName.toLowerCase(Locale.ROOT));
        if (query == null) {
            return null;
        }
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            return result.next() ? result.getString(1) : null;
        } catch (SQLException e) {
            log.debug("Cannot read the server id of {} with {}", productName, query, e);
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
            return null;
        }
    }

    private static @Nullable String serverIdQuery(String productName) {
        if (productName.contains("postgresql")) {
            return "SELECT system_identifier FROM pg_control_system()";
        }
        if (productName.contains("mysql")) {
            return "SELECT @@server_uuid";
        }
        if (productName.contains("sql server")) {
            return "SELECT @@SERVERNAME";
        }
        if (productName.contains("oracle")) {
            return "SELECT dbid FROM v$database";
        }
        return null;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
