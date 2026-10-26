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
import org.lolaf.staffix.api.ComponentProbeScope;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.AlertEvent;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeDefinition;
import org.lolaf.staffix.api.alerting.ProbeId;
import org.lolaf.staffix.api.alerting.ProbeScope;
import org.lolaf.staffix.api.alerting.ProbeState;
import org.lolaf.staffix.api.alerting.Probes;
import org.lolaf.staffix.api.alerting.ScopedProbeState;
import org.lolaf.staffix.api.ha.FixEngineHa;
import org.lolaf.staffix.api.ha.FixEngineHa.ReplicatingStore;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringContext;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.fix44.fields.Subject;
import org.lolaf.staffix.tests.TestingFixAlertsPublisherSettings;
import org.lolaf.staffix.tests.TestingFixSessionMonitoringManagerSettings;
import org.lolaf.staffix.tests.TestingHaSettings;
import org.lolaf.staffix.api.monitoring.FixEngineMonitoringContext;
import org.lolaf.staffix.api.monitoring.FixEngineMonitoringManager;
import org.lolaf.staffix.api.monitoring.FixMeterDescriptor;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

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
        when(ha.replicatingStore(any(), any(), any())).then(invocation -> {
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
        verify(ha, never()).replicatingStore(any(), any(), any());
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
    void aStandbyRefusesEverySessionActionWithAClearMessage() {
        withStandbyHighAvailability();
        setupInitiatorSessionSettings(FixSessionSettings.FixSessionSettingsBuilder::build);
        fixInitiator.start();
        AdminApi admin = (AdminApi) initiatorFixEngine;
        FixSessionId id = fixInitiatorBuilder.getMainTarget().getFixSessionId();

        List<ThrowingCallable> actions = List.of(
                () -> admin.logonSession(id),
                () -> admin.logoutSession(id),
                () -> admin.resetSession(id, AdminApi.ResetFixSessionMode.RESET_SEQUENCE),
                () -> admin.setIncomingSeqNum(id, 5),
                () -> admin.setOutgoingSeqNum(id, 5),
                () -> admin.sendFixMessage(id, "35=0|", '|', false),
                () -> admin.getIncomingSeqNum(id),
                () -> admin.getOutgoingSeqNum(id),
                () -> admin.switchInitiatorSession(id));
        actions.forEach(action -> assertThatThrownBy(action)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is a standby and does not run FIX session " + id)
                .hasMessageContaining("on the engine that leads"));
    }

    @Test
    void anEngineThatNoLongerLeadsRefusesSessionActions() {
        withHighAvailability();
        setupInitiatorSessionSettings(FixSessionSettings.FixSessionSettingsBuilder::build);
        logonClient();
        AdminApi admin = (AdminApi) initiatorFixEngine;
        FixSessionId id = fixInitiatorBuilder.getMainTarget().getFixSessionId();
        assertThat(admin.getOutgoingSeqNum(id)).isPositive();

        engine.deactivate();

        assertThatThrownBy(() -> admin.setOutgoingSeqNum(id, 100)).isInstanceOf(IllegalStateException.class);
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
    void theAdminApiShowsTheHighAvailabilityOnlyWhenTheEngineHasOne() {
        setupInitiatorSessionSettings(b -> b.build());
        assertThat(((AdminApi) initiatorFixEngine).getHa()).isEmpty();

        withHighAvailability();
        setupInitiatorSessionSettings(b -> b.build());
        assertThat(((AdminApi) initiatorFixEngine).getHa()).containsSame(ha);
    }

    @Test
    void theHighAvailabilityGetsTheEngineMetersOfTheMonitoringItNamesAndTheAdminApiListsThem() {
        FixSessionsMonitoringManager monitoring = mock(FixSessionsMonitoringManager.class,
                withSettings().extraInterfaces(FixEngineMonitoringManager.class));
        FixEngineMonitoringContext engineMeters = mock(FixEngineMonitoringContext.class);
        FixMeterDescriptor role = FixMeterDescriptor.builder().name("ha.role").type(FixMeterDescriptor.Type.GAUGE).build();
        when(((FixEngineMonitoringManager) monitoring).engineContext(any())).thenReturn(engineMeters);
        when(engineMeters.getMeterDescriptors()).thenReturn(List.of(role));
        initiatorFixEngineBuilder = initiatorFixEngineBuilder.toBuilder()
                .fixSessionsPlugin(TestingFixSessionMonitoringManagerSettings.builder().instanceId("monitoring").mock(monitoring).build())
                .build();
        withHighAvailability();
        setupInitiatorSessionSettings(b -> b.build());

        assertThat(((AdminApi) initiatorFixEngine).getEngineMeters()).isEmpty();
        assertThat(engine.monitoring("monitoring")).isSameAs(engineMeters);
        verify((FixEngineMonitoringManager) monitoring).engineContext(((AdminApi) initiatorFixEngine).getInstanceId());
        assertThat(((AdminApi) initiatorFixEngine).getEngineMeters()).containsExactly(role);
        assertThatThrownBy(() -> engine.monitoring("elsewhere")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no engine monitoring manager elsewhere");
    }

    @Test
    void theHighAvailabilityGetsComponentProbesTheEngineEvaluatesAndPublishesToThePublisherItNames() {
        TestingFixAlertsPublisherSettings alerts = TestingFixAlertsPublisherSettings.builder().instanceId("alerts").build();
        initiatorFixEngineBuilder = initiatorFixEngineBuilder.toBuilder().fixAlertsPublisher(alerts).build();
        withHighAvailability();
        setupInitiatorSessionSettings(b -> b.fixAlertsPublisherInstanceId("alerts").build());
        AdminApi admin = (AdminApi) initiatorFixEngine;
        ProbeScope haScope = ComponentProbeScope.of("ha");
        AtomicBoolean leading = new AtomicBoolean(true);

        Probes probes = engine.probes("ha", "alerts", leading::get);
        probes.add(Probe.builder().name("followers").description("No follower").evaluator(() -> Optional.of(Alert.major("none"))).build());

        assertThat(engine.probes("ha", "alerts", () -> false)).isSameAs(probes);
        assertThat(admin.getComponentProbeScopes()).containsExactly(haScope);
        assertThat(admin.getProbeDefinitions(haScope)).extracting(ProbeDefinition::getName).containsExactly("followers");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(alerts.getEvents())
                .filteredOn(event -> event.getProbeId().getScope().equals(haScope)).singleElement().satisfies(event -> {
                    assertThat(event.getProbeId()).isEqualTo(ProbeId.of(haScope, "followers"));
                    assertThat(event.getLabels()).containsEntry(ComponentProbeScope.COMPONENT_LABEL, "ha");
                }));
        assertThat(admin.getActiveAlerts()).extracting(ScopedProbeState::getScope).contains(haScope);
        assertThat(admin.getProbeStates(haScope)).extracting(ProbeState::getProbeName).containsExactly("followers");

        leading.set(false);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(alerts.getEvents())
                .filteredOn(event -> event.getProbeId().getScope().equals(haScope))
                .extracting(AlertEvent::getType).containsExactly(AlertEvent.Type.RAISED, AlertEvent.Type.CLEARED));
        assertThatThrownBy(() -> engine.probes("other", "elsewhere", () -> true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.getProbeStates(ComponentProbeScope.of("other"))).isInstanceOf(IllegalArgumentException.class);
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
                any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReplicatingStoreGetsTheSessionsMetersOnceItsPluginsExist() {
        FixSessionsMonitoringManager monitoring = mock(FixSessionsMonitoringManager.class);
        FixSessionPlugin<FixSessionsMonitoringContext, Void> plugin = mock(FixSessionPlugin.class);
        FixSessionsMonitoringContext sessionMeters = mock(FixSessionsMonitoringContext.class);
        when(plugin.isForPluginContext(FixSessionsMonitoringContext.class)).thenReturn(true);
        when(plugin.getPluginContext()).thenReturn(Optional.of(sessionMeters));
        when(monitoring.matchesPluginClass(FixSessionsMonitoringManager.class)).thenReturn(true);
        doReturn(Optional.of(plugin)).when(monitoring).onSessionCreated(any(), any(), any(), any(), any());
        initiatorFixEngineBuilder = initiatorFixEngineBuilder.toBuilder()
                .fixSessionsPlugin(TestingFixSessionMonitoringManagerSettings.builder().instanceId("monitoring").mock(monitoring).build())
                .build();
        withHighAvailability();

        setupInitiatorSessionSettings(b -> b.haSessionSettings(replicated(ReplicationMode.SYNC))
                .fixSessionPluginsInstanceId(FixSessionsMonitoringManager.class, "monitoring").build());
        logonClient();

        assertThat(stores.get(0).sessionMeters).containsExactly(sessionMeters);
    }

    @Test
    void aSyncSessionReplicatesEachMessageAndWaitsBeforeWritingItThenStoresIt() {
        withHighAvailability();

        setupInitiatorSessionSettings(b -> b.haSessionSettings(replicated(ReplicationMode.SYNC)).build());
        logonClient();

        await().untilAsserted(() -> assertThat(stores.get(0).outgoingWrites)
                .startsWith("replicate 1", "await", "outgoing 2")
                .containsOnlyOnce("outgoing 2"));
    }

    @Test
    void aSyncBatchReplicatesAllItsMessagesThenWaitsOnce() {
        withHighAvailability();
        setupInitiatorSessionSettings(b -> b.haSessionSettings(replicated(ReplicationMode.SYNC)).build());
        logonClient();
        FixSession fixSession = getFixSession(ConnectorType.INITIATOR);
        await().until(() -> stores.get(0).outgoingWrites.contains("outgoing 2"));
        stores.get(0).outgoingWrites.clear();

        for (int i = 1; i <= 3; i++) {
            fixSession.bufferize(fixSession.newEncoder(EmailEncoder.class).begin().setSubject("subject-" + i), null);
        }
        fixSession.flush();

        await().untilAsserted(() -> assertThat(stores.get(0).outgoingWrites)
                .containsExactly("replicate 2", "replicate 3", "replicate 4", "await", "message 2", "message 3", "message 4"));
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
                .noneMatch(write -> write.startsWith("replicate") || write.equals("await")));
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

        verify(ha, never()).replicatingStore(any(), any(), any());
        verify(ha).notReplicated(argThat(settings -> settings.getFixSessionId().equals(fixInitiatorSession.getFixSessionId())));
    }

    @Test
    void aReplicationTurnedOffThroughTheAdminApiReachesTheHighAvailabilityAsTheSessionRestarts() {
        withHighAvailability();
        setupInitiatorSessionSettings(b -> b.haSessionSettings(replicated(ReplicationMode.SYNC)).build());
        logonClient();
        AdminApi admin = (AdminApi) initiatorFixEngine;
        FixSessionSettings sync = admin.getManagedFixSessionsSettings().get(0);

        admin.updateFixSessionSettings(sync.getFixSessionId(), sync.toBuilder().haSessionSettings(replicated(ReplicationMode.NONE)).build());

        await().untilAsserted(() -> verify(ha).notReplicated(argThat(settings -> settings.getFixSessionId().equals(sync.getFixSessionId()))));
        verify(ha).replicatingStore(any(), any(), any());
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
        private final List<FixSessionsMonitoringContext> sessionMeters = new CopyOnWriteArrayList<>();

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
        public void replicateSent(MessageType messageType, long outgoingSeqNum, ByteBuffer message) {
            outgoingWrites.add("replicate " + outgoingSeqNum);
        }

        @Override
        public void awaitReplicated() {
            outgoingWrites.add("await");
        }

        @Override
        public void sessionMeters(FixSessionsMonitoringContext meters) {
            sessionMeters.add(meters);
        }

        private interface OutgoingWrites {

            void storeMessageSent(long outgoingSeqNum, ByteBuffer message);

            void storeNextOutgoingSeqNum(long nextOutgoingSeqNum);
        }
    }
}
