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

import com.zaxxer.hikari.HikariDataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.stores.messages.testkit.AbstractFixSessionMessagesStoreTest;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


abstract class AbstractJdbcFixSessionMessagesStore extends AbstractFixSessionMessagesStoreTest<JdbcFixSessionMessagesStore> {

    private HikariDataSource dataSource;
    private ScheduledExecutorService scheduledExecutorService;

    @Override
    protected final void beforeSetUp() throws Exception {
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        dataSource = getDataSource();
        initializeSchema(getDbSchemaResourceName());
    }

    abstract HikariDataSource getDataSource();

    abstract String getDbSchemaResourceName();

    String transformSchemaInitStatement(String sql) {
        return sql;
    }

    @Override
    protected JdbcFixSessionMessagesStore createSessionStore(FixSessionId sessionId, BiPredicate<MessageType, ByteBuffer> messagesFilter) {
        return new JdbcFixSessionMessagesStore(JdbcMessageStoreSettings.builder()
                .dataSource(dataSource)
                .messageFilter(messagesFilter)
                .build(), sessionId, scheduledExecutorService);
    }

    @Override
    protected void beforeTearDown() {
        dataSource.close();
        dataSource = null;
        scheduledExecutorService.shutdown();
    }

    private void initializeSchema(String schemaName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            InputStream schemaStream = getClass().getResourceAsStream("/db/" + schemaName);
            assertThat(schemaStream).isNotNull();
            String schemaSql = new BufferedReader(new InputStreamReader(schemaStream, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
            for (String sql : schemaSql.split(";")) {
                String trimmed = transformSchemaInitStatement(sql.trim());
                if (!trimmed.isEmpty()) {
                    try {
                        stmt.execute(trimmed);
                    } catch (SQLException e) {
                        // ORA-00942: table or view does not exist fix
                        if (!e.getMessage().contains("ORA-00942") && e.getErrorCode() != 942) {
                            throw e;
                        }
                    }
                }
            }
        }
    }

    @Test
    @Override
    protected void shouldHaveSequenceNumbersStartingWithOneForNewSessionsStores() {
        super.shouldHaveSequenceNumbersStartingWithOneForNewSessionsStores();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT incoming_seq_num, outgoing_seq_num FROM fix_messages_session_state WHERE session_id = ?")) {
            stmt.setString(1, sessionId.getId());
            ResultSet rs = stmt.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("incoming_seq_num")).isEqualTo(1L);
            assertThat(rs.getLong("outgoing_seq_num")).isEqualTo(1L);
        } catch (SQLException e) {
            fail(e);
        }
    }


    @Test
    @Override
    protected void shouldUpdateIncomingSequenceNumber() {
        super.shouldUpdateIncomingSequenceNumber();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT incoming_seq_num FROM fix_messages_session_state WHERE session_id = ?")) {
            stmt.setString(1, sessionId.getId());
            ResultSet rs = stmt.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("incoming_seq_num")).isEqualTo(42L);
        } catch (SQLException e) {
            fail(e);
        }
    }

    @Test
    @Override
    protected void shouldUpdateOutgoingSequenceNumber() {
        super.shouldUpdateOutgoingSequenceNumber();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT outgoing_seq_num FROM fix_messages_session_state WHERE session_id = ?")) {
            stmt.setString(1, sessionId.getId());
            ResultSet rs = stmt.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("outgoing_seq_num")).isEqualTo(99L);
        } catch (SQLException e) {
            fail(e);
        }
    }

    @Test
    @Override
    protected void shouldResetSequenceNumbersAndDeleteMessages() {
        super.shouldResetSequenceNumbersAndDeleteMessages();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT incoming_seq_num, outgoing_seq_num FROM fix_messages_session_state WHERE session_id = ?")) {
            stmt.setString(1, sessionId.getId());
            ResultSet rs = stmt.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("incoming_seq_num")).isEqualTo(1L);
            assertThat(rs.getLong("outgoing_seq_num")).isEqualTo(1L);
        } catch (SQLException e) {
            fail(e);
        }
    }

    @Test
    void shouldHandleTablePrefixConfiguration() {

        assertThat(messageStore.getTableName()).hasToString("fix_messages");

        messageStore = new JdbcFixSessionMessagesStore(JdbcMessageStoreSettings.builder()
                .dataSource(dataSource)
                .tablePrefix("test")
                .build(), sessionId, scheduledExecutorService);

        assertThat(messageStore.getTableName()).hasToString("test_fix_messages");
    }

    @Test
    void shouldPruneOldMessagesWithNonGappedSequenceNumbers() throws Exception {
        getMessageStoreForPruning(100);

        // Store 150 messages to exceed the threshold. The store stays stopped until the fixture is
        // built and asserted: start() schedules the pruner one pruningCheckInterval out, and on a slow
        // dialect these 150 round-trips can outlast that window, pruning the fixture as it is written.
        for (long i = 1; i <= 150; i++) {
            insertMessageDirectly(sessionId.getId(), i);
        }

        assertThat(countMessagesInDatabase(sessionId.getId())).isEqualTo(150);

        messageStore.start();

        Awaitility.await().untilAsserted(() -> assertThat(countMessagesInDatabase(sessionId.getId()))
                .as("Message count after pruning should be exactly maxMessagesPerSession (100)")
                .isEqualTo(100));

        // Verify min and max sequence numbers
        long[] minMax = getMinMaxSequenceNumbers(sessionId.getId());
        long minSeq = minMax[0];
        long maxSeq = minMax[1];

        assertThat(maxSeq)
                .as("Maximum sequence number should be 150 (last message stored)")
                .isEqualTo(150);

        assertThat(minSeq)
                .as("Minimum sequence number should be 51 (150 - 100 + 1)")
                .isEqualTo(51);

        // Verify we can retrieve all the retained messages
        var messages = messageStore.find(minSeq, maxSeq);
        assertThat(messages)
                .as("Should be able to retrieve all retained messages")
                .hasSize(100);

        // Verify the first retained message has sequence number 51
        assertThat(messages.get(0).getSeqNum())
                .as("First retained message should have sequence number 51")
                .isEqualTo(51);

        // Verify the last retained message has sequence number 150
        assertThat(messages.get(messages.size() - 1).getSeqNum())
                .as("Last retained message should have sequence number 150")
                .isEqualTo(150);
    }

    @Test
    void shouldPruneOldMessagesWithGappedSequenceNumbers() throws Exception {
        getMessageStoreForPruning(3);

        // Insert 6 messages with non-contiguous seq nums: 1, 4, 6, 7, 10, 15. Started only after the
        // fixture is asserted, for the reason given above.
        for (long seqNum : new long[]{1L, 4L, 6L, 7L, 10L, 15L}) {
            insertMessageDirectly(sessionId.getId(), seqNum);
        }
        assertThat(countMessagesInDatabase(sessionId.getId())).isEqualTo(6);

        messageStore.start();

        // excessCount=3 OFFSET 2 -> cutoff=6, delete <=6 → keeps (7, 10, 15)
        Awaitility.await().untilAsserted(() ->
                assertThat(countMessagesInDatabase(sessionId.getId()))
                        .as("Message count after pruning should be exactly maxMessagesPerSession (3)")
                        .isEqualTo(3));

        long[] minMax = getMinMaxSequenceNumbers(sessionId.getId());
        assertThat(minMax[0])
                .as("Minimum sequence number should be 7 (oldest of the 3 kept messages)")
                .isEqualTo(7);
        assertThat(minMax[1])
                .as("Maximum sequence number should be 15")
                .isEqualTo(15);

        var messages = messageStore.find(0, 100);
        assertThat(messages)
                .as("Should be able to retrieve all retained messages")
                .hasSize(3);

        assertThat(messages.stream().map(FixMessagesStore.FixSessionMessagesStore.StoreMessage::getSeqNum).collect(Collectors.toList()))
                .as("First retained message should have sequence number 51")
                .containsExactly(7L, 10L, 15L);
    }

    private void getMessageStoreForPruning(int maxMessagesPerSession) {
        messageStore = new JdbcFixSessionMessagesStore(
                JdbcMessageStoreSettings.builder()
                        .dataSource(dataSource)
                        .maxMessagesPerSession(maxMessagesPerSession)
                        .pruningCheckInterval(java.time.Duration.ofSeconds(1))
                        .build(),
                sessionId,
                scheduledExecutorService);
    }

    private void insertMessageDirectly(String sessionIdStr, long seqNum) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO fix_messages (session_id, sequence_number, message_data) VALUES (?, ?, ?)")) {
            stmt.setString(1, sessionIdStr);
            stmt.setLong(2, seqNum);
            stmt.setBytes(3, ("Message-" + seqNum).getBytes(StandardCharsets.UTF_8));
            stmt.executeUpdate();
        }
    }

    private long countMessagesInDatabase(String sessionIdStr) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM fix_messages WHERE session_id = ?")) {
            stmt.setString(1, sessionIdStr);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        }
    }

    private long[] getMinMaxSequenceNumbers(String sessionIdStr) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT MIN(sequence_number), MAX(sequence_number) FROM fix_messages WHERE session_id = ?")) {
            stmt.setString(1, sessionIdStr);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return new long[]{rs.getLong(1), rs.getLong(2)};
            }
            return new long[]{0, 0};
        }
    }
}