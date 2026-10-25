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
package org.lolaf.staffix.admin.jmx;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.admin.FixSessionComponents;
import org.lolaf.staffix.api.admin.FixAcceptorSessions;
import org.lolaf.staffix.api.admin.FixInitiatorTargets;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.ProbeDefinition;
import org.lolaf.staffix.api.alerting.ProbeId;
import org.lolaf.staffix.api.alerting.ProbeScope;
import org.lolaf.staffix.api.alerting.ProbeState;
import org.lolaf.staffix.api.alerting.ProbesListener;
import org.lolaf.staffix.api.alerting.ScopedProbeState;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.ha.HaAdmin;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.ha.LeaderElection;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.monitoring.FixMeterDescriptor;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionProbeScope;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JmxAdminApiTest {

    private static final String DOMAIN = "org.lolaf.staffix.test";
    private static final FixSessionId INITIATOR_SESSION = FixSessionId.of("init1", FixRegularVersion.VERSION_44, "S1", "T1");
    private static final FixSessionId BACKUP_SESSION = FixSessionId.of("backup1", FixRegularVersion.VERSION_44, "S1_DR", "T1_DR");
    private static final FixSessionId ACCEPTOR_SESSION = FixSessionId.of("acc1", FixRegularVersion.VERSION_44, "S2", "T2");
    private static final Instant SINCE = Instant.parse("2026-10-10T10:00:00Z");
    private MBeanServer mBeanServer;
    private RecordingAdminApi adminApi;
    private JmxAdminApi exporter;

    private static FixSession session(FixSessionId fixSessionId, boolean initiator) {
        FixSession session = mock(FixSession.class);
        when(session.getFixSessionId()).thenReturn(fixSessionId);
        FixSessionSettings sessionSettings = mock(FixSessionSettings.class);
        when(sessionSettings.getFixSessionType()).thenReturn(initiator ? FixSession.FixSessionType.INITIATOR : FixSession.FixSessionType.ACCEPTOR);
        when(session.getFixSessionSettings()).thenReturn(sessionSettings);
        return session;
    }

    @BeforeEach
    void setUp() {
        mBeanServer = MBeanServerFactory.newMBeanServer();
        adminApi = new RecordingAdminApi();
        exporter = new JmxAdminApi(JmxAdminApiSettings.builder().jmxDomain(DOMAIN).mBeanServer(mBeanServer).build());
    }

    @Test
    void registersAdminAndSnapshotSessionBeansOnExport() throws Exception {
        adminApi.managed.add(session(INITIATOR_SESSION, true));

        exporter.export(adminApi);

        assertThat(mBeanServer.isRegistered(adminName())).isTrue();
        assertThat(mBeanServer.isRegistered(sessionName("init1", "Initiator"))).isTrue();
        assertThat(adminApi.lifecycleListener).isNotNull();
    }

    @Test
    void registersOneBeanPerGroupForSessionsSharingAnId() throws Exception {
        FixSessionId alphaTrading = FixSessionId.of(FixRegularVersion.VERSION_44,
                FixSessionId.FixSessionIdBuilder.builder().name("trading").group("alpha").senderCompID("US").targetCompID("ALPHA").build());
        FixSessionId betaTrading = FixSessionId.of(FixRegularVersion.VERSION_44,
                FixSessionId.FixSessionIdBuilder.builder().name("trading").group("beta").senderCompID("US").targetCompID("BETA").build());
        adminApi.managed.add(session(alphaTrading, true));
        adminApi.managed.add(session(betaTrading, true));

        exporter.export(adminApi);

        assertThat(mBeanServer.isRegistered(sessionName("alpha", "trading", "Initiator"))).isTrue();
        assertThat(mBeanServer.isRegistered(sessionName("beta", "trading", "Initiator"))).isTrue();
    }

    @Test
    void registersAndUnregistersSessionBeansAsSessionsComeAndGo() throws Exception {
        exporter.export(adminApi);

        adminApi.lifecycleListener.onSessionRegistered(session(ACCEPTOR_SESSION, false));
        assertThat(mBeanServer.isRegistered(sessionName("acc1", "Acceptor"))).isTrue();

        adminApi.lifecycleListener.onSessionUnregistered(session(ACCEPTOR_SESSION, false));
        assertThat(mBeanServer.isRegistered(sessionName("acc1", "Acceptor"))).isFalse();
    }

    @Test
    void sessionMBeanOperationsRouteToTheBoundSession() throws Exception {
        exporter.export(adminApi);
        adminApi.lifecycleListener.onSessionRegistered(session(INITIATOR_SESSION, true));

        FixSessionMXBean proxy = JMX.newMXBeanProxy(mBeanServer, sessionName("init1", "Initiator"), FixSessionMXBean.class);
        proxy.logon();
        proxy.setOutgoingSeqNum(42L);
        proxy.sendFixMessage("35=C|94=0|", '|', true);

        assertThat(adminApi.loggedOn).containsExactly(INITIATOR_SESSION);
        assertThat(adminApi.outgoingSeqNums).containsExactly(42L);
        assertThat(adminApi.sentFixMessages).containsExactly("35=C|94=0||true");
    }

    @Test
    void shutdownUnregistersEveryBeanAndDropsTheListener() throws Exception {
        adminApi.managed.add(session(INITIATOR_SESSION, true));
        exporter.export(adminApi);

        exporter.shutdown(Deadline.of(Duration.ofSeconds(1)));

        assertThat(mBeanServer.isRegistered(adminName())).isFalse();
        assertThat(mBeanServer.isRegistered(sessionName("init1", "Initiator"))).isFalse();
        assertThat(adminApi.lifecycleListener).isNull();
    }

    @Test
    void initiatorsTargetsAreReadableAsSessionIdStrings() throws Exception {
        adminApi.initiatorsTargets.add(initiatorTargets());
        exporter.export(adminApi);

        FixAdminMXBean proxy = JMX.newMXBeanProxy(mBeanServer, adminName(), FixAdminMXBean.class);

        assertThat(proxy.getInitiatorsTargets()).singleElement().satisfies(targets -> {
            assertThat(targets.getInstanceId()).isEqualTo("initiator");
            assertThat(targets.getActiveFixSessionId()).isEqualTo(INITIATOR_SESSION.toString());
            assertThat(targets.getFixSessionIds()).containsExactly(INITIATOR_SESSION.toString(), BACKUP_SESSION.toString());
        });
    }

    @Test
    void switchResolvesTheSessionFromItsStringForm() throws Exception {
        adminApi.initiatorsTargets.add(initiatorTargets());
        exporter.export(adminApi);
        FixAdminMXBean proxy = JMX.newMXBeanProxy(mBeanServer, adminName(), FixAdminMXBean.class);

        proxy.switchInitiatorSession(BACKUP_SESSION.toString());

        assertThat(adminApi.switchedTo).containsExactly(BACKUP_SESSION);
        assertThatThrownBy(() -> proxy.switchInitiatorSession(BACKUP_SESSION.getName()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(BACKUP_SESSION.toString());
    }

    @Test
    void aSessionsProbesAndTheirStatesAreReadable() throws Exception {
        adminApi.probeDefinitions.add(ProbeDefinition.builder().name("order-flow").description("Time since the last order")
                .evaluationInterval(Duration.ofSeconds(1)).label("team", "fix_ops")
                .runbookUrl(URI.create("https://wiki.example.com/order-flow")).build());
        adminApi.probeStates.add(ProbeState.builder().probeName("order-flow").alert(Alert.warning("no order for 4s"))
                .since(SINCE).lastEvaluation(SINCE.plusSeconds(2)).build());
        adminApi.probeStates.add(ProbeState.builder().probeName("session-down").lastEvaluation(SINCE).build());
        adminApi.probeStates.add(ProbeState.builder().probeName("rejects").suspended(true).build());
        exporter.export(adminApi);
        adminApi.lifecycleListener.onSessionRegistered(session(INITIATOR_SESSION, true));

        FixSessionMXBean proxy = JMX.newMXBeanProxy(mBeanServer, sessionName("init1", "Initiator"), FixSessionMXBean.class);

        assertThat(proxy.getProbes()).singleElement().satisfies(probe -> {
            assertThat(probe.getName()).isEqualTo("order-flow");
            assertThat(probe.getDescription()).isEqualTo("Time since the last order");
            assertThat(probe.getEvaluationInterval()).isEqualTo("PT1S");
            assertThat(probe.getReminderInterval()).isNull();
            assertThat(probe.getLabels()).containsExactly(Map.entry("team", "fix_ops"));
            assertThat(probe.getRunbookUrl()).isEqualTo("https://wiki.example.com/order-flow");
            assertThat(probe.isOnlyWhileLoggedIn()).isTrue();
        });
        assertThat(proxy.getProbeStates()).extracting(FixSessionMXBean.ProbeStatus::getProbe,
                        FixSessionMXBean.ProbeStatus::getLevel, FixSessionMXBean.ProbeStatus::getMessage,
                        FixSessionMXBean.ProbeStatus::getSince, FixSessionMXBean.ProbeStatus::getLastEvaluation,
                        FixSessionMXBean.ProbeStatus::isSuspended)
                .containsExactly(
                        tuple("order-flow", "WARNING", "no order for 4s", "2026-10-10T10:00:00Z", "2026-10-10T10:00:02Z", false),
                        tuple("session-down", null, null, null, "2026-10-10T10:00:00Z", false),
                        tuple("rejects", null, null, null, null, true));
    }

    @Test
    void aSessionsAlertIsClearedThroughTheAdminApi() throws Exception {
        adminApi.managed.add(session(INITIATOR_SESSION, true));
        exporter.export(adminApi);
        FixSessionMXBean proxy = JMX.newMXBeanProxy(mBeanServer, sessionName("init1", "Initiator"), FixSessionMXBean.class);

        assertThat(proxy.clearAlert("order-flow")).isTrue();
        assertThat(proxy.clearAlert("session-down")).isFalse();
        assertThat(adminApi.clearedAlerts).hasSize(2).allSatisfy(cleared -> assertThat(cleared).startsWith("init1 "));
    }

    @Test
    void theEnginesActiveAlertsAreReadableWithTheirScope() throws Exception {
        adminApi.activeAlerts.add(new ScopedProbeState(FixSessionProbeScope.of(ACCEPTOR_SESSION), ProbeState.builder().probeName("session-down")
                .alert(Alert.critical("down")).since(SINCE).lastEvaluation(SINCE).build(), URI.create("https://wiki.example.com/session-down")));
        exporter.export(adminApi);

        FixAdminMXBean proxy = JMX.newMXBeanProxy(mBeanServer, adminName(), FixAdminMXBean.class);

        assertThat(proxy.getActiveAlerts()).singleElement().satisfies(alert -> {
            assertThat(alert.getScope()).isEqualTo("session/" + ACCEPTOR_SESSION.getQualifiedName());
            assertThat(alert.getProbe()).isEqualTo("session-down");
            assertThat(alert.getLevel()).isEqualTo("CRITICAL");
            assertThat(alert.getMessage()).isEqualTo("down");
            assertThat(alert.getSince()).isEqualTo("2026-10-10T10:00:00Z");
            assertThat(alert.getRunbookUrl()).isEqualTo("https://wiki.example.com/session-down");
        });
    }

    @Test
    void anEngineWithoutHighAvailabilityHasNoHaBean() throws Exception {
        exporter.export(adminApi);

        assertThat(mBeanServer.isRegistered(haName())).isFalse();
    }

    @Test
    void theHighAvailabilityIsReadableAndUnregisteredOnShutdown() throws Exception {
        HaAdmin ha = mock(HaAdmin.class);
        when(ha.getStatus()).thenReturn(HaStatus.builder().nodeId("1").role(LeaderElection.Role.FOLLOWER).epoch(7)
                .leaderId("2").leaderElection("PrimosLeaderElection").roleSince(SINCE)
                .leader(HaStatus.Leader.builder().leaderId("2").address("/10.0.0.2:17101").connected(true).inSync(true).build())
                .build());
        when(ha.getSessionsStatus()).thenReturn(List.of(HaSessionStatus.builder().fixSessionId(ACCEPTOR_SESSION)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR).replicationMode(ReplicationMode.SYNC)
                .replicationAckTimeout(Duration.ofMillis(200)).inSync(true).nextIncomingSeqNum(12).nextOutgoingSeqNum(34)
                .handedOver(true).build()));
        adminApi.ha = ha;
        exporter.export(adminApi);

        FixHaMXBean proxy = JMX.newMXBeanProxy(mBeanServer, haName(), FixHaMXBean.class);

        FixHaMXBean.Status status = proxy.getStatus();
        assertThat(status.getRole()).isEqualTo("FOLLOWER");
        assertThat(status.getEpoch()).isEqualTo(7);
        assertThat(status.getRoleSince()).isEqualTo("2026-10-10T10:00:00Z");
        assertThat(status.getFollowers()).isEmpty();
        assertThat(status.getLeader().getAddress()).isEqualTo("/10.0.0.2:17101");
        assertThat(status.getLeader().isInSync()).isTrue();
        assertThat(proxy.getSessionsStatus()).singleElement().satisfies(session -> {
            assertThat(session.getFixSessionId()).isEqualTo(ACCEPTOR_SESSION.toString());
            assertThat(session.getReplicationMode()).isEqualTo("SYNC");
            assertThat(session.getReplicationAckTimeout()).isEqualTo("PT0.2S");
            assertThat(session.getNextOutgoingSeqNum()).isEqualTo(34);
            assertThat(session.isHandedOver()).isTrue();
            assertThat(session.getNotReplicatedReason()).isNull();
        });

        exporter.shutdown(Deadline.immediate());
        assertThat(mBeanServer.isRegistered(haName())).isFalse();
    }

    private ObjectName haName() throws Exception {
        return new ObjectName(DOMAIN + ":type=FixHa,instance=" + ObjectName.quote(adminApi.getInstanceId()));
    }

    private static FixInitiatorTargets initiatorTargets() {
        InetSocketAddress address = new InetSocketAddress("localhost", 1);
        return FixInitiatorTargets.builder()
                .instanceId("initiator")
                .activeFixSessionId(INITIATOR_SESSION)
                .mainTarget(FixInitiatorTarget.builder().fixSessionId(INITIATOR_SESSION).connectAddress(address).build())
                .backupTarget(FixInitiatorTarget.builder().fixSessionId(BACKUP_SESSION).connectAddress(address).build())
                .build();
    }

    private ObjectName adminName() throws Exception {
        return new ObjectName(DOMAIN + ":type=FixAdmin,instance=" + ObjectName.quote(adminApi.getInstanceId()));
    }

    private ObjectName sessionName(String sessionId, String role) throws Exception {
        return sessionName(FixSessionId.DEFAULT_GROUP, sessionId, role);
    }

    private ObjectName sessionName(String group, String sessionId, String role) throws Exception {
        return new ObjectName(DOMAIN + ":type=FixSession,instance=" + ObjectName.quote(adminApi.getInstanceId())
                + ",group=" + ObjectName.quote(group) + ",session=" + ObjectName.quote(sessionId) + ",role=" + role);
    }

    private static final class RecordingAdminApi implements AdminApi {

        private final List<FixSession> managed = new ArrayList<>();
        private final List<FixSessionId> loggedOn = new ArrayList<>();
        private final List<Long> outgoingSeqNums = new ArrayList<>();
        private final List<String> sentFixMessages = new ArrayList<>();
        private final List<FixInitiatorTargets> initiatorsTargets = new ArrayList<>();
        private final List<FixSessionId> switchedTo = new ArrayList<>();
        private final List<ProbeDefinition> probeDefinitions = new ArrayList<>();
        private final List<ProbeState> probeStates = new ArrayList<>();
        private final List<ScopedProbeState> activeAlerts = new ArrayList<>();
        private final List<String> clearedAlerts = new ArrayList<>();
        private HaAdmin ha;
        private SessionLifecycleListener lifecycleListener;

        @Override
        public String getInstanceId() {
            return "engine-1";
        }

        @Override
        public void logonSession(FixSessionId fixSessionId) {
            loggedOn.add(fixSessionId);
        }

        @Override
        public void logoutSession(FixSessionId fixSessionId) {
            // nothing to do
        }

        @Override
        public void resetSession(FixSessionId fixSessionId, ResetFixSessionMode resetFixSessionMode) {
            // nothing to do
        }

        @Override
        public void setIncomingSeqNum(FixSessionId fixSessionId, long incomingSeqNum) {
            // nothing to do
        }

        @Override
        public void setOutgoingSeqNum(FixSessionId fixSessionId, long outgoingSeqNum) {
            outgoingSeqNums.add(outgoingSeqNum);
        }

        @Override
        public void sendFixMessage(FixSessionId fixSessionId, String fixMessage, char separator, boolean possDupFlag) {
            sentFixMessages.add(fixMessage + separator + possDupFlag);
        }

        @Override
        public long getIncomingSeqNum(FixSessionId fixSessionId) {
            return 0;
        }

        @Override
        public long getOutgoingSeqNum(FixSessionId fixSessionId) {
            return 0;
        }

        @Override
        public List<FixSessionSettings> getManagedFixSessionsSettings() {
            return List.of();
        }

        @Override
        public List<String> getFixSessionsSettingsStoresInstanceIds() {
            return List.of();
        }

        @Override
        public void reloadFixSessionsSettingsStore(String instanceId) {
            // nothing to do
        }

        @Override
        public boolean isFixSessionsSettingsStorePersistent(String instanceId) {
            return false;
        }

        @Override
        public Optional<String> findFixSessionsSettingsStore(FixSessionId fixSessionId, FixSession.FixSessionType fixSessionType) {
            return Optional.empty();
        }

        @Override
        public void addFixSessionSettings(String storeInstanceId, FixSessionSettings settings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateFixSessionSettings(FixSessionId fixSessionId, FixSessionSettings settings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeFixSessionSettings(FixSessionId fixSessionId, FixSession.FixSessionType fixSessionType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Collection<FixApplicationSessionSettingDescriptor> getFixApplicationSessionSettingDescriptors(FixSessionId fixSessionId) {
            return List.of();
        }

        @Override
        public List<FixSession> getManagedFixSessions() {
            return new ArrayList<>(managed);
        }

        @Override
        public List<FixMessagesLoggerSettings> getFixMessagesLoggersSettings() {
            return List.of();
        }

        @Override
        public List<FixSessionsPluginSettings<?>> getFixSessionsPluginsSettings() {
            return List.of();
        }

        @Override
        public FixSessionComponents getFixSessionComponents() {
            return FixSessionComponents.builder().build();
        }

        @Override
        public List<FixMeterDescriptor> getFixSessionMeters(FixSessionId fixSessionId) {
            return List.of();
        }

        @Override
        public List<ProbeDefinition> getProbeDefinitions(ProbeScope scope) {
            return probeDefinitions;
        }

        @Override
        public List<ProbeState> getProbeStates(ProbeScope scope) {
            return probeStates;
        }

        @Override
        public List<ScopedProbeState> getActiveAlerts() {
            return activeAlerts;
        }

        @Override
        public List<FixMeterDescriptor> getEngineMeters() {
            return List.of();
        }

        @Override
        public Optional<HaAdmin> getHa() {
            return Optional.ofNullable(ha);
        }

        @Override
        public boolean clearAlert(ProbeId probeId) {
            clearedAlerts.add(probeId.getScope().getLabels().get(FixSessionProbeScope.NAME_LABEL) + " " + probeId.getName());
            return !"session-down".equals(probeId.getName());
        }

        @Override
        public void addProbesListener(ProbesListener listener) {
        }

        @Override
        public void removeProbesListener(ProbesListener listener) {
        }

        @Override
        public void switchInitiatorSession(FixSessionId fixSessionId) {
            switchedTo.add(fixSessionId);
        }

        @Override
        public List<FixInitiatorTargets> getInitiatorsTargets() {
            return new ArrayList<>(initiatorsTargets);
        }

        @Override
        public List<FixAcceptorSessions> getAcceptorsSessions() {
            return List.of();
        }

        @Override
        public void registerSessionLifecycleListener(SessionLifecycleListener listener) {
            this.lifecycleListener = listener;
        }

        @Override
        public void unregisterSessionLifecycleListener(SessionLifecycleListener listener) {
            if (this.lifecycleListener == listener) {
                this.lifecycleListener = null;
            }
        }
    }
}
