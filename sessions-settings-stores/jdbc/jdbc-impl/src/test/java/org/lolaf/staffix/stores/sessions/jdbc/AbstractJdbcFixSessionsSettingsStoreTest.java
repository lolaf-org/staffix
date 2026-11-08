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

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

abstract class AbstractJdbcFixSessionsSettingsStoreTest {

    private static final String ENGINE = "engine-a";
    private static final String STORE = "test";

    private static final String PLACEHOLDER_PROPERTY = "staffix.test.jdbc.settings.timeout";
    private static final String EXPRESSION = "${sysprop:" + PLACEHOLDER_PROPERTY + "}";
    private static final String TIMEOUT_POINTER = "/logInOrOutResponseTimeout";

    private HikariDataSource dataSource;

    abstract HikariDataSource newDataSource();

    abstract String schemaResourceName();

    @BeforeEach
    void createTable() throws SQLException {
        dataSource = newDataSource();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : readSchema().split(";")) {
                if (!sql.isBlank()) {
                    try {
                        statement.execute(sql.trim());
                    } catch (SQLException e) {
                        // ORA-00942: the first drop finds no table
                        if (e.getErrorCode() != 942) {
                            throw e;
                        }
                    }
                }
            }
        }
    }

    @AfterEach
    void closeDataSource() {
        System.clearProperty(PLACEHOLDER_PROPERTY);
        dataSource.close();
    }

    private String readSchema() {
        InputStream schema = getClass().getResourceAsStream("/db/" + schemaResourceName());
        assertThat(schema).isNotNull();
        return new BufferedReader(new InputStreamReader(schema, StandardCharsets.UTF_8)).lines()
                .collect(Collectors.joining("\n"));
    }

    private JdbcFixSessionsSettingsStore newStore(String engineId) {
        return newStore(engineId, STORE);
    }

    private JdbcFixSessionsSettingsStore newStore(String engineId, String storeInstanceId) {
        JdbcFixSessionsSettingsStore store = (JdbcFixSessionsSettingsStore) new JdbcFixSessionsSettingsStore.JdbcStoreFactoryImpl()
                .newInstance(JdbcSessionsSettingsStoreSettings.builder()
                        .instanceId(storeInstanceId)
                        .dataSource(dataSource)
                        .build());
        store.bind(engineId);
        return store;
    }

    private JdbcFixSessionsSettingsStore startedStore() {
        JdbcFixSessionsSettingsStore store = newStore(ENGINE);
        store.start();
        return store;
    }

    @Test
    void aRowHoldingAPlaceholderLoadsResolvedAndCarriesItsExpression() throws SQLException {
        System.setProperty(PLACEHOLDER_PROPERTY, "PT45S");
        startedStore().add(session("placeholder"));
        setRowTimeout(EXPRESSION);

        assertThat(startedStore().getSettings()).singleElement().satisfies(settings -> {
            assertThat(settings.getLogInOrOutResponseTimeout()).isEqualTo(Duration.ofSeconds(45));
            assertThat(settings.getPlaceholders()).containsEntry(TIMEOUT_POINTER, EXPRESSION);
        });
    }

    @Test
    void aRowsPlaceholderThatNothingResolvesFailsTheLoadNamingIt() throws SQLException {
        startedStore().add(session("placeholder"));
        setRowTimeout(EXPRESSION);

        assertThatThrownBy(this::startedStore).hasMessageContaining(PLACEHOLDER_PROPERTY);
    }

    @Test
    void settingsArrivingWithAPlaceholderStoreTheExpressionAndNeverTheValue() throws SQLException {
        System.setProperty(PLACEHOLDER_PROPERTY, "PT45S");
        JdbcFixSessionsSettingsStore store = startedStore();

        store.add(withPlaceholder(session("placeholder", Duration.ofSeconds(45))));

        assertThat(rowJson()).contains(EXPRESSION).doesNotContain("PT45S");
        assertThat(store.getSettings()).singleElement()
                .satisfies(settings -> assertThat(settings.getPlaceholders()).containsEntry(TIMEOUT_POINTER, EXPRESSION));
        System.setProperty(PLACEHOLDER_PROPERTY, "PT50S");
        assertThat(startedStore().getSettings()).singleElement()
                .satisfies(settings -> assertThat(settings.getLogInOrOutResponseTimeout()).isEqualTo(Duration.ofSeconds(50)));
    }

    @Test
    void anUpdateWithoutPlaceholdersKeepsTheExpressionWhileItsValueIsUnchanged() throws SQLException {
        System.setProperty(PLACEHOLDER_PROPERTY, "PT45S");
        JdbcFixSessionsSettingsStore store = startedStore();
        store.add(withPlaceholder(session("placeholder", Duration.ofSeconds(45))));

        store.update(session("placeholder", Duration.ofSeconds(45)).toBuilder().disconnectOnRemove(false).build());

        assertThat(rowJson()).contains(EXPRESSION).doesNotContain("PT45S");
    }

    @Test
    void changingTheValueOfAPlaceholderIsRefusedAndLeavesTheRowAsItWas() throws SQLException {
        System.setProperty(PLACEHOLDER_PROPERTY, "PT45S");
        JdbcFixSessionsSettingsStore store = startedStore();
        store.add(withPlaceholder(session("placeholder", Duration.ofSeconds(45))));
        String before = rowJson();

        assertThatThrownBy(() -> store.update(session("placeholder", Duration.ofSeconds(99))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be changed through the store")
                .hasMessageNotContaining("PT45S")
                .hasMessageNotContaining("PT1M39S");

        assertThat(rowJson()).isEqualTo(before);
        assertThat(store.getSettings()).singleElement()
                .satisfies(settings -> assertThat(settings.getLogInOrOutResponseTimeout()).isEqualTo(Duration.ofSeconds(45)));
    }

    @Test
    void anUpdateBringingANewExpressionReplacesTheOneHeld() throws SQLException {
        System.setProperty(PLACEHOLDER_PROPERTY, "PT45S");
        JdbcFixSessionsSettingsStore store = startedStore();
        store.add(withPlaceholder(session("placeholder", Duration.ofSeconds(45))));
        String other = "${sysprop:" + PLACEHOLDER_PROPERTY + ":PT45S}";

        store.update(session("placeholder", Duration.ofSeconds(45)).toBuilder().placeholders(Map.of(TIMEOUT_POINTER, other)).build());

        assertThat(rowJson()).contains(other);
    }

    @Test
    void aReloadPicksUpAnExpressionRewrittenForTheSameValue() throws SQLException {
        System.setProperty(PLACEHOLDER_PROPERTY, "PT45S");
        JdbcFixSessionsSettingsStore store = startedStore();
        store.add(withPlaceholder(session("placeholder", Duration.ofSeconds(45))));
        String rewritten = "${sysprop:" + PLACEHOLDER_PROPERTY + ":PT45S}";
        setRowTimeout(rewritten);

        store.load();

        assertThat(store.getSettings()).singleElement()
                .satisfies(settings -> assertThat(settings.getPlaceholders()).containsEntry(TIMEOUT_POINTER, rewritten));
    }

    @Test
    void aSessionWithoutPlaceholdersStoresItsValues() throws SQLException {
        JdbcFixSessionsSettingsStore store = startedStore();

        store.add(session("plain", Duration.ofSeconds(45)));

        assertThat(rowJson()).contains("PT45S");
        assertThat(store.load()).singleElement().satisfies(row -> assertThat(row.getPlaceholders()).isEmpty());
    }

    private static FixSessionSettings withPlaceholder(FixSessionSettings settings) {
        return settings.toBuilder().placeholders(Map.of(TIMEOUT_POINTER, EXPRESSION)).build();
    }

    private String rowJson() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT settings FROM fix_sessions_settings")) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private void setRowTimeout(String timeout) throws SQLException {
        String json = rowJson().replaceFirst("\"logInOrOutResponseTimeout\":\"[^\"]*\"",
                Matcher.quoteReplacement("\"logInOrOutResponseTimeout\":\"" + timeout + "\""));
        try (Connection connection = dataSource.getConnection();
             var update = connection.prepareStatement("UPDATE fix_sessions_settings SET settings = ?")) {
            update.setString(1, json);
            update.executeUpdate();
        }
    }

    @Test
    void theStorageIdentityTellsEnginesAndStoresApart() {
        assertThat(newStore(ENGINE).getStorageIdentity()).isPresent().isEqualTo(newStore(ENGINE).getStorageIdentity())
                .isNotEqualTo(newStore("engine-b").getStorageIdentity())
                .isNotEqualTo(newStore(ENGINE, "other").getStorageIdentity());
    }

    private static FixSessionSettings session(String name) {
        return session(name, Duration.ofSeconds(30));
    }

    private static FixSessionSettings session(String name, Duration responseTimeout) {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                        .name(name)
                        .group("desk")
                        .senderCompID("SENDER-" + name)
                        .targetCompID("TARGET-" + name)
                        .build()))
                .fixSessionType(FixSession.FixSessionType.INITIATOR)
                .logInOrOutResponseTimeout(responseTimeout)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("Europe/Zurich"))
                        .sessionSchedule(FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                                .startDay(DayOfWeek.MONDAY)
                                .startTime(LocalTime.of(7, 0))
                                .endDay(DayOfWeek.FRIDAY)
                                .endTime(LocalTime.of(22, 30))
                                .build())
                        .build())
                .build();
    }

    @Test
    void addedSessionIsReadBackByAnotherStore() {
        FixSessionSettings settings = session("alpha");
        startedStore().add(settings);

        assertThat(startedStore().getSettings()).containsExactly(settings);
    }

    @Test
    void updatedSessionIsReadBackByAnotherStore() {
        JdbcFixSessionsSettingsStore store = startedStore();
        store.add(session("alpha"));
        FixSessionSettings updated = session("alpha", Duration.ofSeconds(10));
        store.update(updated);

        assertThat(startedStore().getSettings()).containsExactly(updated);
        assertThat(store.getSettings()).containsExactly(updated);
    }

    @Test
    void removedSessionIsDeleted() {
        JdbcFixSessionsSettingsStore store = startedStore();
        FixSessionSettings settings = session("alpha");
        store.add(settings);
        store.remove(settings);

        assertThat(store.getSettings()).isEmpty();
        assertThat(startedStore().getSettings()).isEmpty();
    }

    @Test
    void loadReadsTheTableWithoutMutatingManagedSettings() {
        JdbcFixSessionsSettingsStore store = startedStore();
        FixSessionSettings settings = session("alpha");
        startedStore().add(settings);

        assertThat(store.load()).containsExactly(settings);
        assertThat(store.getSettings()).isEmpty();
    }

    @Test
    void updateOfARowChangedMeanwhileIsRefusedAndLeavesTheStoreUnchanged() {
        FixSessionSettings original = session("alpha");
        JdbcFixSessionsSettingsStore first = startedStore();
        first.add(original);
        JdbcFixSessionsSettingsStore second = startedStore();
        second.update(session("alpha", Duration.ofSeconds(10)));

        assertThatThrownBy(() -> first.update(session("alpha", Duration.ofSeconds(20))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("since it was read at version 1");
        assertThat(first.getSettings()).containsExactly(original);
    }

    @Test
    void reloadAdoptsAChangeMadeByAnotherWriterWithoutWritingItBack() {
        JdbcFixSessionsSettingsStore store = startedStore();
        store.add(session("alpha"));
        FixSessionSettings changedElsewhere = session("alpha", Duration.ofSeconds(10));
        startedStore().update(changedElsewhere);

        // what the engine's reload does: apply what load() read through update
        assertThat(store.load()).containsExactly(changedElsewhere);
        store.update(changedElsewhere);
        FixSessionSettings next = session("alpha", Duration.ofSeconds(20));
        store.update(next);

        assertThat(startedStore().getSettings()).containsExactly(next);
    }

    @Test
    void sessionRemovedAndAddedAgainAfterAReloadIsWritten() {
        JdbcFixSessionsSettingsStore store = startedStore();
        FixSessionSettings settings = session("alpha");
        startedStore().add(settings);
        store.load();
        store.add(settings);

        store.remove(settings);
        store.add(settings);

        assertThat(startedStore().getSettings()).containsExactly(settings);
    }

    @Test
    void enginesSharingTheTableSeeOnlyTheirOwnSessions() {
        FixSessionSettings forA = session("alpha");
        FixSessionSettings forB = session("alpha", Duration.ofSeconds(10));
        startedStore().add(forA);
        JdbcFixSessionsSettingsStore engineB = newStore("engine-b");
        engineB.start();
        engineB.add(forB);

        assertThat(startedStore().getSettings()).containsExactly(forA);
        JdbcFixSessionsSettingsStore engineBAgain = newStore("engine-b");
        engineBAgain.start();
        assertThat(engineBAgain.getSettings()).containsExactly(forB);
    }

    @Test
    void storesOfOneEngineSharingTheTableSeeOnlyTheirOwnSessions() {
        FixSessionSettings alpha = session("alpha");
        FixSessionSettings beta = session("beta");
        JdbcFixSessionsSettingsStore acceptors = newStore(ENGINE, "acceptors");
        acceptors.start();
        acceptors.add(alpha);
        JdbcFixSessionsSettingsStore initiators = newStore(ENGINE, "initiators");
        initiators.start();
        initiators.add(beta);

        JdbcFixSessionsSettingsStore acceptorsAgain = newStore(ENGINE, "acceptors");
        acceptorsAgain.start();
        assertThat(acceptorsAgain.getSettings()).containsExactly(alpha);
    }

    @Test
    void rowWrittenByANewerStaffixIsRefusedNamingIt() throws SQLException {
        insertRow("desk.alpha", "{\"schemaVersion\": 99}");

        assertThatThrownBy(this::startedStore)
                .hasMessageContaining("fix_sessions_settings row engine-a/test/desk.alpha")
                .hasMessageContaining("written by a newer staffix");
    }

    @Test
    void rowKeyedForAnotherSessionIsRefused() throws SQLException {
        JdbcFixSessionsSettingsStore writer = startedStore();
        writer.add(session("alpha"));
        String json;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT settings FROM fix_sessions_settings")) {
            resultSet.next();
            json = resultSet.getString(1);
        }
        insertRow("desk.beta", json);

        assertThatThrownBy(this::startedStore)
                .hasMessageContaining("row engine-a/test/desk.beta is keyed desk.beta INITIATOR")
                .hasMessageContaining("holds the settings of desk.alpha");
    }

    @Test
    void tablePrefixThatIsNotAnIdentifierIsRefused() {
        assertThatThrownBy(() -> new JdbcFixSessionsSettingsStore.JdbcStoreFactoryImpl()
                .newInstance(JdbcSessionsSettingsStoreSettings.builder()
                        .dataSource(dataSource)
                        .tablePrefix("x; drop table y")
                        .build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void insertRow(String qualifiedName, String settings) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO fix_sessions_settings"
                     + " (engine_id, store_instance_id, qualified_name, session_type, settings, version, updated_at)"
                     + " VALUES (?, ?, ?, ?, ?, 1, ?)")) {
            insert.setString(1, ENGINE);
            insert.setString(2, STORE);
            insert.setString(3, qualifiedName);
            insert.setString(4, "INITIATOR");
            insert.setString(5, settings);
            insert.setTimestamp(6, Timestamp.from(Instant.now()));
            insert.executeUpdate();
        }
    }
}
