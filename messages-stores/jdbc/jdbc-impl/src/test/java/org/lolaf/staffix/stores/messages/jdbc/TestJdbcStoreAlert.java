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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.alerting.AlertLevel;
import org.lolaf.staffix.api.alerting.FixSessionProbes;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreException;
import org.lolaf.staffix.api.version.FixRegularVersion;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestJdbcStoreAlert {

    private static final FixSessionId SESSION_ID = FixSessionId.of("orders", FixRegularVersion.VERSION_44, "SENDER", "TARGET");

    private final DataSource dataSource = mock(DataSource.class);

    private JdbcFixSessionMessagesStore store(Duration alertClearDelay) {
        return new JdbcFixSessionMessagesStore(JdbcMessageStoreSettings.builder().dataSource(dataSource)
                .alertClearDelay(alertClearDelay).build(), SESSION_ID, null);
    }

    @Test
    void aFailedCallRaisesTheAlertAndTheNextSuccessfulOneClearsIt() throws Exception {
        JdbcFixSessionMessagesStore store = store(Duration.ZERO);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        assertThatThrownBy(() -> store.storeNextIncomingSeqNum(2)).isInstanceOf(StoreException.class);
        assertThat(store.getProbe().getEvaluator().evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.MAJOR);
            assertThat(alert.getMessage()).contains("Failed to store incoming sequence number", "connection refused");
        });

        doReturn(mock(Connection.class, RETURNS_MOCKS)).when(dataSource).getConnection();
        store.storeNextIncomingSeqNum(3);

        assertThat(store.getProbe().getEvaluator().evaluate()).isEmpty();
    }

    @Test
    void theAlertIsHeldForTheClearDelayAfterASuccess() throws Exception {
        JdbcFixSessionMessagesStore store = store(Duration.ofMinutes(1));
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));
        assertThatThrownBy(() -> store.storeNextOutgoingSeqNum(2)).isInstanceOf(StoreException.class);
        store.getProbe().getEvaluator().evaluate();

        doReturn(mock(Connection.class, RETURNS_MOCKS)).when(dataSource).getConnection();
        store.storeNextOutgoingSeqNum(3);

        assertThat(store.getProbe().getEvaluator().evaluate()).isPresent();
    }

    @Test
    void eachSessionStoreAddsItsProbe() {
        JdbcMessagesStore messagesStore = new JdbcMessagesStore(JdbcMessageStoreSettings.builder().dataSource(dataSource).build());
        FixSessionProbes probes = mock(FixSessionProbes.class);

        JdbcFixSessionMessagesStore store = (JdbcFixSessionMessagesStore) messagesStore.getStore(SESSION_ID, probes);

        verify(probes).add(store.getProbe());
        assertThat(store.getProbe().getName()).isEqualTo("jdbc-store");
    }
}
