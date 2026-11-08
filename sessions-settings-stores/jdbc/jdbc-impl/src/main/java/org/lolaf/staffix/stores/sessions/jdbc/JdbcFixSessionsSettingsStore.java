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
import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.session.*;
import org.lolaf.staffix.api.stores.JdbcStorageIdentity;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocumentCodec;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocumentCodec.WrittenDocument;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocumentMapper;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Keeps each session's settings document as JSON in one row of {@code fix_sessions_settings}, keyed by the engine id,
 * this store's instance id and the session's qualified name, so the stores and engines sharing the table each see
 * only their own sessions.
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
    private final Map<FixSessionId, Long> versions = new ConcurrentHashMap<>();
    private JdbcStorageIdentity storageIdentity;
    private final FixSessionSettingsDocumentCodec codec;
    private final @Nullable String engineIdOverride;
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
        this.engineIdOverride = settings.getEngineIdOverride();
        this.dataSource = Objects.requireNonNull(settings.getDataSource(), "dataSource");
        this.codec = new FixSessionSettingsDocumentCodec(mapper, settings.getConfigValueResolvers());
        this.tableName = settings.getTablePrefix().isEmpty()
                ? "fix_sessions_settings"
                : settings.getTablePrefix() + "_fix_sessions_settings";
    }

    @Override
    protected void startMe() throws StartStopException {
        for (Row row : readRows().values()) {
            hold(row.getSettings());
            versions.put(row.getSettings().getFixSessionId(), row.getVersion());
        }
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        // nothing to do: the data source belongs to the application
    }

    @Override
    protected Set<FixSessionSettings> onLoad() {
        Map<FixSessionId, Row> rows = readRows();
        lastLoaded.clear();
        lastLoaded.putAll(rows);
        // an equal row is not reconciled, so its newer version would fail the next write
        rows.values().forEach(row -> find(row.getSettings().getFixSessionId(), row.getSettings().getFixSessionType())
                .filter(row.getSettings()::equals)
                .ifPresent(held -> versions.put(held.getFixSessionId(), row.getVersion())));
        Set<FixSessionSettings> loaded = new HashSet<>();
        rows.values().forEach(row -> loaded.add(row.getSettings()));
        return loaded;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    protected FixSessionSettings onAdd(FixSessionSettings settings) {
        if (adoptLoaded(settings)) {
            return settings;
        }
        WrittenDocument written = write(settings);
        insert(settings, written.getTree());
        return written.getSettings();
    }

    @Override
    protected FixSessionSettings onUpdate(FixSessionSettings settings) {
        if (adoptLoaded(settings)) {
            return settings;
        }
        WrittenDocument written = write(settings);
        update(settings, written.getTree(), versionOf(settings.getFixSessionId()));
        return written.getSettings();
    }

    /**
     * Deletes whatever version the row is at: removing a session is not a change another writer's could be lost to.
     */
    @Override
    protected void onRemove(FixSessionSettings settings) {
        lastLoaded.remove(settings.getFixSessionId());
        delete(settings.getFixSessionId());
        versions.remove(settings.getFixSessionId());
    }

    @Override
    public void bind(String engineId) {
        super.bind(engineId);
        storageIdentity = new JdbcStorageIdentity(dataSource, tableName + "|" + instanceId + "|" + getEngineId(), null);
    }

    @Override
    protected String getEngineId() {
        return engineIdOverride != null ? engineIdOverride : super.getEngineId();
    }

    @Override
    public Optional<String> getStorageIdentity() {
        return storageIdentity.get();
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
        codec.refreshResolvers();
        String sql = "SELECT qualified_name, session_type, settings, version FROM " + tableName
                + " WHERE engine_id = ? AND store_instance_id = ?";
        Map<FixSessionId, Row> rows = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, getEngineId());
            select.setString(2, instanceId);
            try (ResultSet resultSet = select.executeQuery()) {
                while (resultSet.next()) {
                    Row row = toRow(resultSet);
                    rows.put(row.getSettings().getFixSessionId(), row);
                    log.info("Loaded FixSessionSettings {} from {}", row.getSettings().getFixSessionId(), tableName);
                }
            }
        } catch (SQLException e) {
            throw failed("read the sessions of engine " + getEngineId() + " and store " + instanceId + " from", e);
        }
        return rows;
    }

    private Row toRow(ResultSet resultSet) throws SQLException {
        String qualifiedName = resultSet.getString("qualified_name");
        String source = tableName + " row " + getEngineId() + "/" + instanceId + "/" + qualifiedName;
        FixSessionSettings settings = codec.toSettings(codec.read(parse(resultSet.getString("settings"), source), source), Optional.empty(), source);
        String sessionType = resultSet.getString("session_type");
        if (!settings.getFixSessionId().getQualifiedName().equals(qualifiedName)
                || !settings.getFixSessionType().name().equals(sessionType)) {
            throw new IllegalStateException(source + " is keyed " + qualifiedName + " " + sessionType
                    + " but holds the settings of " + settings.getFixSessionId().getQualifiedName() + " "
                    + settings.getFixSessionType());
        }
        return new Row(settings, resultSet.getLong("version"));
    }

    private JsonNode parse(String json, String source) {
        try {
            return mapper.readValue(json, JsonNode.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to read the FIX session settings of " + source, e);
        }
    }

    private WrittenDocument write(FixSessionSettings settings) {
        return codec.write(settings, find(settings.getFixSessionId(), settings.getFixSessionType()), settings.getFixSessionId().toString());
    }

    private String json(FixSessionSettings settings, JsonNode tree) {
        try {
            return mapper.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to write the FIX session settings of " + settings.getFixSessionId(), e);
        }
    }

    private void insert(FixSessionSettings settings, JsonNode tree) {
        String sql = "INSERT INTO " + tableName
                + " (engine_id, store_instance_id, qualified_name, session_type, settings, version, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, 1, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, getEngineId());
            insert.setString(2, instanceId);
            insert.setString(3, settings.getFixSessionId().getQualifiedName());
            insert.setString(4, settings.getFixSessionType().name());
            insert.setString(5, json(settings, tree));
            insert.setTimestamp(6, Timestamp.from(Instant.now()));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw failed("insert FIX session " + settings.getFixSessionId() + " into", e);
        }
        versions.put(settings.getFixSessionId(), 1L);
    }

    private void update(FixSessionSettings settings, JsonNode tree, long version) {
        String sql = "UPDATE " + tableName + " SET session_type = ?, settings = ?, version = ?, updated_at = ?"
                + " WHERE engine_id = ? AND store_instance_id = ? AND qualified_name = ? AND version = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, settings.getFixSessionType().name());
            update.setString(2, json(settings, tree));
            update.setLong(3, version + 1);
            update.setTimestamp(4, Timestamp.from(Instant.now()));
            update.setString(5, getEngineId());
            update.setString(6, instanceId);
            update.setString(7, settings.getFixSessionId().getQualifiedName());
            update.setLong(8, version);
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
        String sql = "DELETE FROM " + tableName + " WHERE engine_id = ? AND store_instance_id = ? AND qualified_name = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(sql)) {
            delete.setString(1, getEngineId());
            delete.setString(2, instanceId);
            delete.setString(3, fixSessionId.getQualifiedName());
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
