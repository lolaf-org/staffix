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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.threading.FastThreadLocal;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;

@Slf4j
class JdbcFixSessionMessagesStore extends Startable.SimpleStartable<FixMessagesStore.FixSessionMessagesStore>
        implements FixMessagesStore.BatchingFixSessionMessagesStore {

    private final FastThreadLocal<List<CachedInputStream>> cachedInputStreamTL;
    private final DataSource dataSource;
    private final FixSessionId fixSessionId;
    private final BiPredicate<MessageType, ByteBuffer> messageFilter;
    private final AtomicLong incomingSequenceNumber;
    private final AtomicLong outgoingSequenceNumber;
    @Getter(AccessLevel.PACKAGE)
    private final String tableName;

    // SQL statements
    private final String insertMessageSql;
    private final String updateIncomingSeqNumSql;
    private final String updateOutgoingSeqNumSql;
    private final String selectMessagesSql;
    private final String countMessagesSql;
    // Pruning configuration
    private final int maxMessagesPerSession;
    private final Duration pruningCheckInterval;
    private final ScheduledExecutorService scheduledExecutorService;
    private String findCutoffSeqNumSql;
    private ScheduledFuture<?> pruningTask;

    JdbcFixSessionMessagesStore(JdbcMessageStoreSettings settings, FixSessionId fixSessionId, ScheduledExecutorService scheduledExecutorService) {
        this.cachedInputStreamTL = FastThreadLocal.withInitial(ArrayList::new);
        this.dataSource = settings.getDataSource();
        this.fixSessionId = fixSessionId;
        this.messageFilter = settings.getMessageFilter();
        this.incomingSequenceNumber = new AtomicLong(1);
        this.outgoingSequenceNumber = new AtomicLong(1);
        this.tableName = settings.getTablePrefix().isEmpty() ? "fix_messages" : settings.getTablePrefix() + "_fix_messages";

        this.insertMessageSql = "INSERT INTO " + tableName +
                " (session_id, sequence_number, message_data) VALUES (?, ?, ?)";
        this.updateIncomingSeqNumSql = "UPDATE " + tableName + "_session_state " +
                "SET incoming_seq_num = ? WHERE session_id = ?";
        this.updateOutgoingSeqNumSql = "UPDATE " + tableName + "_session_state " +
                "SET outgoing_seq_num = ? WHERE session_id = ?";
        this.selectMessagesSql = "SELECT sequence_number, message_data FROM " + tableName +
                " WHERE session_id = ? AND sequence_number BETWEEN ? AND ? ORDER BY sequence_number";
        this.countMessagesSql = "SELECT COUNT(*) FROM " + tableName + " WHERE session_id = ?";

        // Pruning configuration
        this.maxMessagesPerSession = settings.getMaxMessagesPerSession();
        this.pruningCheckInterval = settings.getPruningCheckInterval();
        this.scheduledExecutorService = scheduledExecutorService;
    }

    @Override
    public boolean isUnderlyingStorageResourceAvailable() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public String getUnderlyingStorageResourceDescription() {
        try {
            return "JDBC store for datasource " + dataSource.toString();
        } catch (Exception ex) {
            return "JDBC store";
        }
    }

    @Override
    protected void startMe() throws StartStopException {
        try {
            initializeSessionStateTableIfNeeded();
            loadSequenceNumbers();
            createCutoffSqlQuery();
            if (maxMessagesPerSession > 0) {
                pruningTask = scheduledExecutorService.scheduleAtFixedRate(
                        this::pruneOldMessages, pruningCheckInterval.toMillis(), pruningCheckInterval.toMillis(), TimeUnit.MILLISECONDS);
                log.info("Scheduled messages pruning task for session {} with interval {}, maxMessages {}",
                        fixSessionId, pruningCheckInterval, maxMessagesPerSession);
            }
            log.info("JDBC messages store for FIX session {} started with incomingSeqNum {} and outgoingSeqNum {}",
                    fixSessionId, incomingSequenceNumber.get(), outgoingSequenceNumber.get());
        } catch (SQLException e) {
            throw new StartStopException("Failed to start JDBC session messages store", e);
        }
    }

    private void createCutoffSqlQuery() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            boolean isOracle = conn.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("oracle");
            this.findCutoffSeqNumSql = isOracle
                    ? "SELECT sequence_number FROM " + tableName +
                    " WHERE session_id = ? ORDER BY sequence_number ASC" +
                    " OFFSET ? ROWS FETCH NEXT 1 ROWS ONLY"
                    : "SELECT sequence_number FROM " + tableName +
                    " WHERE session_id = ? ORDER BY sequence_number ASC LIMIT 1 OFFSET ?";
        }
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        if (pruningTask != null) {
            pruningTask.cancel(false);
            pruningTask = null;
        }
        log.info("JDBC messages store for FIX session {} stopped with incomingSeqNum {} and outgoingSeqNum {}",
                fixSessionId, incomingSequenceNumber.get(), outgoingSequenceNumber.get());
    }

    private void initializeSessionStateTableIfNeeded() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Check if session state exists
            String checkSql = "SELECT COUNT(*) FROM " + tableName + "_session_state WHERE session_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(checkSql)) {
                stmt.setString(1, fixSessionId.getId());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        // Insert initial state
                        String insertSql = "INSERT INTO " + tableName + "_session_state " +
                                "(session_id, incoming_seq_num, outgoing_seq_num) VALUES (?, 1, 1)";
                        try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                            insertStmt.setString(1, fixSessionId.getId());
                            insertStmt.executeUpdate();
                        }
                    }
                }
            }
        }
    }

    private void loadSequenceNumbers() throws SQLException {
        String seqNumSelect = "SELECT incoming_seq_num, outgoing_seq_num FROM " +
                tableName + "_session_state WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(seqNumSelect)) {
            stmt.setString(1, fixSessionId.getId());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    incomingSequenceNumber.set(rs.getLong(1));
                    outgoingSequenceNumber.set(rs.getLong(2));
                }
            }
        }
    }

    @Override
    public boolean filter(MessageType messageType, ByteBuffer message) {
        return messageFilter.test(messageType, message);
    }

    @Override
    public void resetSequenceNumbers() {
        try (Connection conn = dataSource.getConnection()) {
            String resetSeqNumSql = "UPDATE " + tableName + "_session_state " +
                    "SET incoming_seq_num = 1, outgoing_seq_num = 1 WHERE session_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(resetSeqNumSql)) {
                stmt.setString(1, fixSessionId.getId());
                stmt.executeUpdate();
            }

            String deleteAllSql = "DELETE FROM " + tableName + " WHERE session_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(deleteAllSql)) {
                stmt.setString(1, fixSessionId.getId());
                stmt.executeUpdate();
            }
            incomingSequenceNumber.set(1);
            outgoingSequenceNumber.set(1);
            log.info("Reset sequence numbers for session {}", fixSessionId);
        } catch (SQLException e) {
            throw new StoreException("Failed to reset sequence numbers for session " + fixSessionId, e);
        }
    }

    @Override
    public long getIncomingSeqNum() {
        return incomingSequenceNumber.get();
    }

    @Override
    public long getOutgoingSeqNum() {
        return outgoingSequenceNumber.get();
    }

    @Override
    public long getNextOutgoingSeqNum() throws StoreException {
        return outgoingSequenceNumber.getAndIncrement();
    }

    @Override
    public void storeNextIncomingSeqNum(long nextIncomingSeqNum) {
        incomingSequenceNumber.set(nextIncomingSeqNum);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(updateIncomingSeqNumSql)) {
            updateSequenceNumber(nextIncomingSeqNum, stmt);
        } catch (SQLException e) {
            throw new StoreException("Failed to store incoming sequence number for session " + fixSessionId, e);
        }
    }

    @Override
    public void storeNextOutgoingSeqNum(long nextOutgoingSeqNum) {
        outgoingSequenceNumber.set(nextOutgoingSeqNum);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(updateOutgoingSeqNumSql)) {
            updateSequenceNumber(nextOutgoingSeqNum, stmt);
        } catch (SQLException e) {
            throw new StoreException("Failed to store outgoing sequence number for session " + fixSessionId, e);
        }
    }

    private void updateSequenceNumber(long nextSeqNum, PreparedStatement stmt) throws SQLException {
        stmt.setLong(1, nextSeqNum);
        stmt.setString(2, fixSessionId.getId());
        stmt.executeUpdate();
    }

    @Override
    public void storeMessageSent(long outgoingSeqNum, ByteBuffer message) {
        List<CachedInputStream> cachedInputStreams = cachedInputStreamTL.get();
        if (cachedInputStreams.isEmpty()) {
            cachedInputStreams.add(new CachedInputStream());
        }
        CachedInputStream cachedInputStream = cachedInputStreams.get(0);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement insert = conn.prepareStatement(insertMessageSql);
             PreparedStatement updateSequenceStmt = conn.prepareStatement(updateOutgoingSeqNumSql)) {

            storeMessage(outgoingSeqNum, message, insert, cachedInputStream);
            insert.executeUpdate();
            long nextOutgoingSeqNum = outgoingSeqNum + 1;
            updateSequenceNumber(nextOutgoingSeqNum, updateSequenceStmt);
            outgoingSequenceNumber.set(nextOutgoingSeqNum);
        } catch (SQLException e) {
            throw new StoreException("Failed to store message for session " + fixSessionId, e);
        } finally {
            cachedInputStream.clean();
        }
    }

    @Override
    public void storeSentFixMessages(SentFixMessage[] sentFixMessages, int sentFixMessageCount) throws StoreException {
        List<CachedInputStream> cachedInputStreams = cachedInputStreamTL.get();
        while (cachedInputStreams.size() < sentFixMessageCount) {
            cachedInputStreams.add(new CachedInputStream());
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(insertMessageSql)) {

            for (int i = 0; i < sentFixMessageCount; i++) {
                SentFixMessage sfm = sentFixMessages[i];
                storeMessage(sfm.getSequenceNumber(), sfm.getMessage(), stmt, cachedInputStreams.get(i));
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            throw new StoreException("Failed to store batched messages for session " + fixSessionId, e);
        } finally {
            for (int i = 0; i < sentFixMessageCount; i++) {
                cachedInputStreams.get(i).clean();
            }
        }
    }

    private void storeMessage(long outgoingSequenceNumber, ByteBuffer message, PreparedStatement insert, CachedInputStream cachedInputStream) throws SQLException {
        insert.setString(1, fixSessionId.getId());
        insert.setLong(2, outgoingSequenceNumber);
        insert.setBinaryStream(3, cachedInputStream.setBuffer(message), message.remaining());
    }

    @Override
    public void find(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(selectMessagesSql)) {

            stmt.setString(1, fixSessionId.getId());
            stmt.setLong(2, startSequenceNumber);
            stmt.setLong(3, stopSequenceNumber);
            try (ResultSet rs = stmt.executeQuery()) {
                // handed over row by row rather than collected first: a peer is free to ask for a range of any width,
                // and this is the store most likely to be asked for one that does not fit in memory
                while (rs.next() && consumer.onMessage(rs.getLong(1), ByteBuffer.wrap(rs.getBytes(2)))) {
                    // nothing more to do, the consumer has taken the message
                }
            }
        } catch (SQLException e) {
            throw new StoreException("Failed to load FIX messages for session " + fixSessionId, e);
        }
    }

    private void pruneOldMessages() {
        try {
            long messageCount = countMessages();
            if (messageCount > maxMessagesPerSession) {
                long excessCount = messageCount - maxMessagesPerSession;
                Long cutoffSeqNum = findCutoffSequenceNumber(excessCount);
                if (cutoffSeqNum != null) {
                    int deletedCount = deleteMessagesUpTo(cutoffSeqNum);
                    log.info("Pruned {} old messages for session {} (before: {}, after: ~{}, cutoffSeq: {})",
                            deletedCount, fixSessionId, messageCount, maxMessagesPerSession, cutoffSeqNum);
                }
            }
        } catch (Exception e) {
            log.error("Error occurred when pruning old messages for session {}", fixSessionId, e);
        }
    }

    private Long findCutoffSequenceNumber(long excessCount) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(findCutoffSeqNumSql)) {
            stmt.setString(1, fixSessionId.getId());
            stmt.setLong(2, excessCount - 1); // OFFSET is 0-indexed
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return null;
    }

    private int deleteMessagesUpTo(long cutoffSeqNum) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM " + tableName + " WHERE session_id = ? AND sequence_number <= ?")) {
            stmt.setString(1, fixSessionId.getId());
            stmt.setLong(2, cutoffSeqNum);
            return stmt.executeUpdate();
        }
    }

    private long countMessages() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(countMessagesSql)) {
            stmt.setString(1, fixSessionId.getId());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0;
    }

    private static final class CachedInputStream extends InputStream {

        private ByteBuffer buffer;

        CachedInputStream setBuffer(ByteBuffer buffer) {
            this.buffer = buffer;
            return this;
        }

        void clean() {
            buffer = null;
        }

        @Override
        public int read(byte[] bytes, int off, int len) {
            buffer.get(bytes, off, len);
            return len;
        }

        @Override
        public int read() {
            if (!buffer.hasRemaining()) {
                return -1;
            }
            return buffer.get() & 0xFF;
        }
    }
}