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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.session.*;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocument;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocumentMapper;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocumentUpgrader;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocumentValidator;
import org.lolaf.staffix.sessions.settings.document.FromFixSessionSettingsDocumentTransformer;
import org.lolaf.staffix.sessions.settings.document.ToFixSessionSettingsDocumentTransformer;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Keeps each session's settings document as JSON in one row of {@code fix_sessions_settings}, keyed by the engine id
 * and the session's qualified name, so engines sharing the table each see only their own sessions.
 *
 * <p>Every write checks the row's {@code version}: a change made meanwhile by another writer fails the write instead
 * of being overwritten, and the store keeps its previous state.
 */
@Slf4j
public class JdbcFixSessionsSettingsStore extends FixSessionsSettingsStore.AbstractFixSessionSettingsStore {

    private static final Pattern TABLE_PREFIX = Pattern.compile("\\w*");

    @Getter
    private final String instanceId;
    private final DataSource dataSource;
    private final String tableName;
    private final ObjectMapper mapper = FixSessionSettingsDocumentMapper.configure(new ObjectMapper());
    private final Set<FixSessionSettings> fixSessionSettings = ConcurrentHashMap.newKeySet();
    private final Map<FixSessionId, Long> versions = new ConcurrentHashMap<>();
    /**
     * What the last {@link #load()} read: the engine applies it back through add and update, which must then adopt
     * the row rather than write it, or the version check would refuse a change that came from the table itself. An
     * entry is used once, so a session removed and added again afterwards is written.
     */
    private final Map<FixSessionId, Row> lastLoaded = new ConcurrentHashMap<>();

    /**
     * @throws IllegalArgumentException if the table prefix is not made of letters, digits and underscores only
     */
    protected JdbcFixSessionsSettingsStore(JdbcSessionsSettingsStoreSettings settings) {
        if (!TABLE_PREFIX.matcher(settings.getTablePrefix()).matches()) {
            throw new IllegalArgumentException("The table prefix is written into the SQL statements as it is, so it may only "
                    + "hold letters, digits and underscores: '" + settings.getTablePrefix() + "'");
        }
        this.instanceId = settings.getInstanceId();
        this.dataSource = Objects.requireNonNull(settings.getDataSource(), "dataSource");
        this.tableName = settings.getTablePrefix().isEmpty()
                ? "fix_sessions_settings"
                : settings.getTablePrefix() + "_fix_sessions_settings";
    }

    @Override
    public Collection<FixSessionSettings> getSettings() {
        return fixSessionSettings;
    }

    @Override
    public Optional<FixSessionSettings> find(FixSessionId fixSessionId, FixSession.FixSessionType fixSessionType) {
        return fixSessionSettings.stream()
                .filter(s -> s.getFixSessionId().equals(fixSessionId) && s.getFixSessionType() == fixSessionType)
                .findFirst();
    }

    @Override
    protected void startMe() throws StartStopException {
        for (Row row : readRows().values()) {
            fixSessionSettings.add(row.getSettings());
            versions.put(row.getSettings().getFixSessionId(), row.getVersion());
        }
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        // nothing to do: the data source belongs to the application
    }

    @Override
    public Set<FixSessionSettings> load() {
        Map<FixSessionId, Row> rows = readRows();
        lastLoaded.clear();
        lastLoaded.putAll(rows);
        Set<FixSessionSettings> loaded = new HashSet<>();
        rows.values().forEach(row -> loaded.add(row.getSettings()));
        return loaded;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void onAdd(FixSessionSettings settings) {
        if (!adoptLoaded(settings)) {
            insert(settings);
        }
        fixSessionSettings.add(settings);
    }

    @Override
    public void onUpdate(FixSessionSettings settings) {
        if (!adoptLoaded(settings)) {
            update(settings, versionOf(settings.getFixSessionId()));
        }
        fixSessionSettings.removeIf(s -> s.getFixSessionId().equals(settings.getFixSessionId()));
        fixSessionSettings.add(settings);
    }

    /**
     * Deletes whatever version the row is at: removing a session is not a change another writer's could be lost to.
     */
    @Override
    public void onRemove(FixSessionSettings settings) {
        lastLoaded.remove(settings.getFixSessionId());
        if (fixSessionSettings.remove(settings)) {
            delete(settings.getFixSessionId());
            versions.remove(settings.getFixSessionId());
        }
    }

    private boolean adoptLoaded(FixSessionSettings settings) {
        Row row = lastLoaded.remove(settings.getFixSessionId());
        if (row == null || !row.getSettings().equals(settings)) {
            return false;
        }
        versions.put(settings.getFixSessionId(), row.getVersion());
        return true;
    }

    private long versionOf(FixSessionId fixSessionId) {
        Long version = versions.get(fixSessionId);
        if (version == null) {
            throw new IllegalStateException("FIX session " + fixSessionId + " has no row in " + tableName);
        }
        return version;
    }

    private Map<FixSessionId, Row> readRows() {
        String sql = "SELECT qualified_name, session_type, settings, version FROM " + tableName + " WHERE engine_id = ?";
        Map<FixSessionId, Row> rows = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, getEngineId());
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    Row row = toRow(resultSet);
                    rows.put(row.getSettings().getFixSessionId(), row);
                    log.info("Loaded FixSessionSettings {} from {}", row.getSettings().getFixSessionId(), tableName);
                }
            }
        } catch (SQLException e) {
            throw failed("read the sessions of engine " + getEngineId() + " from", e);
        }
        return rows;
    }

    private Row toRow(ResultSet resultSet) throws SQLException {
        String qualifiedName = resultSet.getString("qualified_name");
        String source = tableName + " row " + getEngineId() + "/" + qualifiedName;
        FixSessionSettingsDocument document = read(resultSet.getString("settings"), source);
        FixSessionSettingsDocumentValidator.validate(document, source);
        FixSessionSettings settings = FromFixSessionSettingsDocumentTransformer.toFixSessionSettings(document);
        String sessionType = resultSet.getString("session_type");
        if (!settings.getFixSessionId().getQualifiedName().equals(qualifiedName)
                || !settings.getFixSessionType().name().equals(sessionType)) {
            throw new IllegalStateException(source + " is keyed " + qualifiedName + " " + sessionType
                    + " but holds the settings of " + settings.getFixSessionId().getQualifiedName() + " "
                    + settings.getFixSessionType());
        }
        return new Row(settings, resultSet.getLong("version"));
    }

    private FixSessionSettingsDocument read(String json, String source) {
        try {
            JsonNode document = FixSessionSettingsDocumentUpgrader.upgrade(mapper.readValue(json, JsonNode.class), source);
            return mapper.treeToValue(document, FixSessionSettingsDocument.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to read the FIX session settings of " + source, e);
        }
    }

    private String write(FixSessionSettings settings) {
        FixSessionSettingsDocument document = ToFixSessionSettingsDocumentTransformer.toDocument(settings);
        FixSessionSettingsDocumentValidator.validate(document, settings.getFixSessionId().toString());
        try {
            return mapper.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to write the FIX session settings of " + settings.getFixSessionId(), e);
        }
    }

    private void insert(FixSessionSettings settings) {
        String sql = "INSERT INTO " + tableName
                + " (engine_id, qualified_name, session_type, settings, version, updated_at) VALUES (?, ?, ?, ?, 1, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, getEngineId());
            insert.setString(2, settings.getFixSessionId().getQualifiedName());
            insert.setString(3, settings.getFixSessionType().name());
            insert.setString(4, write(settings));
            insert.setTimestamp(5, Timestamp.from(Instant.now()));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw failed("insert FIX session " + settings.getFixSessionId() + " into", e);
        }
        versions.put(settings.getFixSessionId(), 1L);
    }

    private void update(FixSessionSettings settings, long version) {
        String sql = "UPDATE " + tableName + " SET session_type = ?, settings = ?, version = ?, updated_at = ?"
                + " WHERE engine_id = ? AND qualified_name = ? AND version = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, settings.getFixSessionType().name());
            update.setString(2, write(settings));
            update.setLong(3, version + 1);
            update.setTimestamp(4, Timestamp.from(Instant.now()));
            update.setString(5, getEngineId());
            update.setString(6, settings.getFixSessionId().getQualifiedName());
            update.setLong(7, version);
            if (update.executeUpdate() == 0) {
                throw new IllegalStateException("FIX session " + settings.getFixSessionId() + " changed in " + tableName
                        + " since it was read at version " + version + "; reload the store and apply the change again");
            }
        } catch (SQLException e) {
            throw failed("update FIX session " + settings.getFixSessionId() + " in", e);
        }
        versions.put(settings.getFixSessionId(), version + 1);
    }

    private void delete(FixSessionId fixSessionId) {
        String sql = "DELETE FROM " + tableName + " WHERE engine_id = ? AND qualified_name = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(sql)) {
            delete.setString(1, getEngineId());
            delete.setString(2, fixSessionId.getQualifiedName());
            delete.executeUpdate();
        } catch (SQLException e) {
            throw failed("delete FIX session " + fixSessionId + " from", e);
        }
    }

    private IllegalStateException failed(String what, SQLException e) {
        return new IllegalStateException("Unable to " + what + " " + tableName, e);
    }

    @Value
    private static class Row {
        FixSessionSettings settings;
        long version;
    }

    public static class JdbcStoreFactoryImpl implements FixSessionsSettingsStoreSettings.FixSessionsStoreFactory<JdbcSessionsSettingsStoreSettings> {

        @Override
        public FixSessionsSettingsStore newInstance(JdbcSessionsSettingsStoreSettings settings) {
            return new JdbcFixSessionsSettingsStore(settings);
        }

        @Override
        public Class<JdbcSessionsSettingsStoreSettings> getSettingsClass() {
            return JdbcSessionsSettingsStoreSettings.class;
        }
    }
}
