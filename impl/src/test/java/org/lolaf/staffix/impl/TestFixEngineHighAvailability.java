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
package org.lolaf.staffix.impl;

import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.FixEngineHa;
import org.lolaf.staffix.api.ha.FixEngineHa.ReplicatingStore;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.fix44.fields.Subject;
import org.lolaf.staffix.tests.TestingHaSettings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestFixEngineHighAvailability extends AbstractFixTests {

    private final FixEngineHa ha = mock(FixEngineHa.class);
    private final List<RecordingReplicatingStore> stores = new CopyOnWriteArrayList<>();
    private volatile FixEngineHa.EngineAccess engine;
    private boolean leadsOnBind;

    private static FixSessionSettings.HaSessionSettings replicated(ReplicationMode mode) {
        return FixSessionSettings.HaSessionSettings.builder().replicationMode(mode).build();
    }

    /**
     * A highly available engine starts standby; this one leads as soon as it is bound.
     */
    private void withHighAvailability() {
        leadsOnBind = true;
        withStandbyHighAvailability();
    }

    private void withStandbyHighAvailability() {
        doAnswer(invocation -> {
            engine = invocation.getArgument(0);
            if (leadsOnBind) {
                engine.activate();
            }
            return null;
        }).when(ha).bind(any());
        when(ha.replicatingStore(any(), any())).then(invocation -> {
            RecordingReplicatingStore store = new RecordingReplicatingStore(invocation.getArgument(1));
            stores.add(store);
            return store;
        });
        initiatorFixEngineBuilder = initiatorFixEngineBuilder.toBuilder()
                .highAvailability(TestingHaSettings.builder().mock(ha).build())
                .build();
    }

    private static boolean listening(InetSocketAddress address) {
        try (Socket socket = new Socket(address.getHostString(), address.getPort())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void aStandbyEngineBuildsNoSessionUntilItLeads() {
        withStandbyHighAvailability();
        setupInitiatorSessionSettings(b -> b.haSessionSettings(replicated(ReplicationMode.ASYNC)).build());
        startFixAcceptor();

        fixInitiator.start();

        assertThat(fixInitiator.isStarted()).isTrue();
        assertThat(fixInitiator.getSession()).isNull();
        verify(ha, never()).replicatingStore(any(), any());
        verify(fixInitiatorApplication, never()).onSessionCreated(any(), any(), any(), any());

        engine.activate();
        trapCreatedFixSession(ConnectorType.INITIATOR);
        fixInitiatorSession.logon();

        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
    }

    @Test
    void anEngineThatNoLongerLeadsDropsItsSessionsWithoutALogoutAndRunsThemAgainOnceItLeads() {
        withHighAvailability();
        setupInitiatorSessionSettings(b -> b.haSessionSettings(replicated(ReplicationMode.ASYNC)).build());
        logonClient();

        engine.deactivate();

        await().untilAsserted(() -> verify(fixAcceptorApplication).onDisconnected(any()));
        verify(fixAcceptorApplication, never()).onLogout(any(), any(), notNull());
        assertThat(fixInitiator.isConnected()).isFalse();
        assertThat(stores.get(0).isStarted()).as("the session's store is closed").isFalse();

        clearInvocations(fixInitiatorApplication);
        engine.activate();
        trapCreatedFixSession(ConnectorType.INITIATOR);
        fixInitiatorSession.logon();

        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
    }

    @Test
    void aStandbyAcceptorBindsNothingUntilItsEngineLeads() {
        acceptorFixEngineBuilder = acceptorFixEngineBuilder.toBuilder()
                .highAvailability(TestingHaSettings.builder().mock(ha).build())
                .build();
        withStandbyHighAvailability();
        setupAcceptorSessionSettings(FixSessionSettings.FixSessionSettingsBuilder::build);

        fixAcceptor.start();

        assertThat(listening(fixAcceptorBuilder.getBindAddress())).isFalse();
        engine.activate();
        assertThat(listening(fixAcceptorBuilder.getBindAddress())).isTrue();
    }

    @Test
    void aReplicatedSessionIsRefusedOnAnEngineWithoutHighAvailability() {
        assertThatThrownBy(() -> setupInitiatorSessionSettings(b -> b.haSessionSettings(replicated(ReplicationMode.ASYNC)).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("asks for replication mode ASYNC, but engine default has no high availability configured");
    }

    @Test
    void aReplicatedSessionWritesThroughTheReplicatingStore() {
        withHighAvailability();

        setupInitiatorSessionSettings(b -> b.haSessionSettings(replicated(ReplicationMode.SYNC)).build());
        logonClient();

        verify(ha).start();
        verify(ha).replicatingStore(argThat(settings -> settings.getHaSessionSettings().getReplicationMode() == ReplicationMode.SYNC),
                any());
    }

    @Test
    void aSyncSessionStoresEachMessageThenWaitsForItsReplicationBeforeWritingIt() {
        withHighAvailability();

        setupInitiatorSessionSettings(b -> b.haSessionSettings(replicated(ReplicationMode.SYNC)).build());
        logonClient();

        await().untilAsserted(() -> assertThat(stores.get(0).outgoingWrites)
                .startsWith("outgoing 2", "await")
                .containsOnlyOnce("outgoing 2"));
    }

    @Test
    void aSyncBatchStoresAllItsMessagesThenWaitsOnce() {
        withHighAvailability();
        setupInitiatorSessionSettings(b -> b.haSessionSettings(replicated(ReplicationMode.SYNC)).build());
        logonClient();
        FixSession fixSession = getFixSession(ConnectorType.INITIATOR);
        await().until(() -> stores.get(0).outgoingWrites.contains("await"));
        stores.get(0).outgoingWrites.clear();

        for (int i = 1; i <= 3; i++) {
            fixSession.bufferize(fixSession.newEncoder(EmailEncoder.class).begin().setSubject("subject-" + i), null);
        }
        fixSession.flush();

        await().untilAsserted(() -> assertThat(stores.get(0).outgoingWrites)
                .containsExactly("message 2", "message 3", "message 4", "await"));
        List<TestingDecodedFixMessage> received = getDecodedFixMessages(ConnectorType.ACCEPTOR);
        assertMessageReceived(received, Subject.get(), "subject-1");
        assertMessageReceived(received, Subject.get(), "subject-3");
    }

    @Test
    void anAsyncSessionNeverWaitsForReplication() {
        withHighAvailability();

        setupInitiatorSessionSettings(b -> b.haSessionSettings(replicated(ReplicationMode.ASYNC)).build());
        logonClient();

        await().untilAsserted(() -> assertThat(stores.get(0).outgoingWrites)
                .containsOnlyOnce("outgoing 2")
                .doesNotContain("await"));
    }

    @Test
    void theEngineLendsItsSessionsSettingsAndStoresToItsHighAvailability() {
        withHighAvailability();
        setupInitiatorSessionSettings(b -> b.haSessionSettings(replicated(ReplicationMode.ASYNC)).build());

        FixSessionSettings settings = engine.getFixSessionsSettings().stream()
                .filter(s -> s.getFixSessionType() == FixSession.FixSessionType.INITIATOR)
                .findFirst().orElseThrow();
        FixSessionMessagesStore store = engine.openStore(settings);

        store.start();
        store.storeNextIncomingSeqNum(42);
        assertThat(store.getIncomingSeqNum()).isEqualTo(42);
        store.stop(Deadline.unlimited());
    }

    @Test
    void anUnreplicatedSessionKeepsItsStoreUnwrapped() {
        withHighAvailability();

        setupInitiatorSessionSettings(FixSessionSettings.FixSessionSettingsBuilder::build);
        logonClient();

        verify(ha, never()).replicatingStore(any(), any());
    }

    @Test
    void theHighAvailabilityStopsWithTheEngine() {
        withHighAvailability();
        setupInitiatorSessionSettings(FixSessionSettings.FixSessionSettingsBuilder::build);

        initiatorFixEngine.stop(Deadline.unlimited());

        verify(ha).stop(any());
    }

    @RequiredArgsConstructor
    private static final class RecordingReplicatingStore implements ReplicatingStore {

        @Delegate(excludes = OutgoingWrites.class)
        private final FixSessionMessagesStore store;
        private final List<String> outgoingWrites = new CopyOnWriteArrayList<>();

        @Override
        public void storeMessageSent(long outgoingSeqNum, ByteBuffer message) {
            outgoingWrites.add("message " + outgoingSeqNum);
            store.storeMessageSent(outgoingSeqNum, message);
        }

        @Override
        public void storeNextOutgoingSeqNum(long nextOutgoingSeqNum) {
            outgoingWrites.add("outgoing " + nextOutgoingSeqNum);
            store.storeNextOutgoingSeqNum(nextOutgoingSeqNum);
        }

        @Override
        public void awaitReplicated() {
            outgoingWrites.add("await");
        }

        private interface OutgoingWrites {

            void storeMessageSent(long outgoingSeqNum, ByteBuffer message);

            void storeNextOutgoingSeqNum(long nextOutgoingSeqNum);
        }
    }
}
