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
import org.lolaf.staffix.api.admin.FixAcceptorSessions;
import org.lolaf.staffix.api.admin.FixInitiatorTargets;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JmxAdminApiTest {

    private static final String DOMAIN = "org.lolaf.staffix.test";
    private static final FixSessionId INITIATOR_SESSION = FixSessionId.of("init1", FixRegularVersion.VERSION_44, "S1", "T1");
    private static final FixSessionId BACKUP_SESSION = FixSessionId.of("backup1", FixRegularVersion.VERSION_44, "S1_DR", "T1_DR");
    private static final FixSessionId ACCEPTOR_SESSION = FixSessionId.of("acc1", FixRegularVersion.VERSION_44, "S2", "T2");
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

    private static FixInitiatorTargets initiatorTargets() {
        InetSocketAddress address = new InetSocketAddress("localhost", 1);
        return FixInitiatorTargets.builder()
                .instanceId("initiator")
                .activeFixSessionId(INITIATOR_SESSION)
                .target(FixInitiatorTarget.builder().fixSessionId(INITIATOR_SESSION).connectAddress(address).build())
                .target(FixInitiatorTarget.builder().fixSessionId(BACKUP_SESSION).connectAddress(address).build())
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
