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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.FixInitiatorBuilder;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionId.FixSessionIdBuilder;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class TestFixInitiatorSwitch extends AbstractFixTests {

    private static final FixSessionId BACKUP_INITIATOR = FixSessionId.of("backup", FixRegularVersion.VERSION_44, "SENDER44_DR", "TARGET44_DR");
    private static final FixSessionId BACKUP_ACCEPTOR = FixSessionId.of("backup", FixRegularVersion.VERSION_44, "TARGET44_DR", "SENDER44_DR");

    private static Set<Thread> threadsNamed(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().startsWith(prefix))
                .collect(Collectors.toSet());
    }

    @BeforeEach
    void setupBackupSessions() {
        // one store per session id, as in production: the testing store is a single instance shared by all sessions
        initiatorFixEngineBuilder = initiatorFixEngineBuilder.toBuilder()
                .clearFixMessagesStores()
                .fixMessagesStore(MemoryMessageStoreSettings.builder().build())
                .build();
        acceptorFixEngineBuilder = acceptorFixEngineBuilder.toBuilder()
                .clearFixMessagesStores()
                .fixMessagesStore(MemoryMessageStoreSettings.builder().build())
                .build();
        // test classes run concurrently in one JVM, and IO threads are named after the initiator
        fixInitiatorBuilder = fixInitiatorBuilder.toBuilder()
                .instanceId("switching-initiator")
                .backupTarget(FixInitiatorTarget.builder()
                        .fixSessionId(BACKUP_INITIATOR)
                        .connectAddress(new InetSocketAddress("localhost", acceptorPort))
                        .build())
                .build();
        setupInitiatorSessionsSettings(getInitiatorFixSessionSettings().build());
        setupAcceptorSessionsSettings(getAcceptorFixSessionSettings().build(),
                getAcceptorFixSessionSettings().fixSessionId(BACKUP_ACCEPTOR).build());
    }

    private void startAcceptorWithBothSessions() {
        fixAcceptor.start();
        fixAcceptorSession = ((AdminApi) acceptorFixEngine).getManagedFixSessions().stream()
                .filter(session -> session.getFixSessionId().equals(getAcceptorFixSessionSettings().build().getFixSessionId()))
                .findFirst()
                .orElseThrow();
    }

    private FixSession logonMainTarget() {
        startAcceptorWithBothSessions();
        fixInitiator.start();
        trapCreatedFixSession(ConnectorType.INITIATOR);
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isTrue());
        fixInitiatorSession.logon();
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), any()));
        return fixInitiatorSession;
    }

    private FixSessionsSettingsStore initiatorStore() {
        return initiatorFixEngine.getFixSessionsSettingsStores().get(0);
    }

    private FixSessionSettings mainSettings() {
        return initiatorStore().find(getInitiatorFixSessionSettings().build().getFixSessionId(), FixSession.FixSessionType.INITIATOR).orElseThrow();
    }

    @Test
    void switchingLogsTheActiveSessionOutBeforeDiallingTheBackup() {
        FixSession main = logonMainTarget();

        fixInitiator.switchTo(BACKUP_INITIATOR);

        assertThat(main.isLoggedIn()).as("logged out by the time the switch returns").isFalse();
        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogout(eq(main), any(), notNull()));
        FixSession backup = fixInitiator.getSession();
        assertThat(main).isNotEqualTo(backup);
        assertThat(backup.getFixSessionId()).isEqualTo(BACKUP_INITIATOR);
        assertThat(((AdminApi) initiatorFixEngine).getManagedFixSessions()).containsExactly(backup);
        assertThat(initiatorFixEngine.getFixSessionRegistry().find(main.getFixSessionId())).isEmpty();
        assertThat(initiatorFixEngine.getFixSessionRegistry().find(BACKUP_INITIATOR)).contains(backup);
        assertThat(initiatorFixEngine.getFixSessionRegistry()
                .find(sid -> sid.getGroup().equals(backup.getFixSessionId().getGroup()))).contains(backup);

        await().untilAsserted(() -> assertThat(backup.isConnected()).isTrue());
        backup.logon();

        await().untilAsserted(() -> assertThat(backup.isLoggedIn()).isTrue());
    }

    @Test
    void aBackupRunsOnItsMainTargetsSettingsUnderItsOwnSessionId() {
        logonMainTarget();

        fixInitiator.switchTo(BACKUP_INITIATOR);

        assertThat(fixInitiator.getSession().getFixSessionSettings())
                .isEqualTo(mainSettings().toBuilder().fixSessionId(BACKUP_INITIATOR).build());
    }

    @Test
    void anActiveBackupFollowsAnUpdateOfItsMainTargetsSettings() {
        logonMainTarget();
        fixInitiator.switchTo(BACKUP_INITIATOR);
        await().untilAsserted(() -> assertThat(fixInitiator.getSession().isConnected()).isTrue());

        initiatorStore().update(mainSettings().toBuilder().logInOrOutResponseTimeout(Duration.ofSeconds(11)).build());

        FixSessionSettings running = fixInitiator.getSession().getFixSessionSettings();
        assertThat(running.getFixSessionId()).isEqualTo(BACKUP_INITIATOR);
        assertThat(running.getLogInOrOutResponseTimeout()).isEqualTo(Duration.ofSeconds(11));
    }

    @Test
    void settingsStoredForABackupPreventTheNextStart() {
        logonMainTarget();
        initiatorStore().add(mainSettings().toBuilder().fixSessionId(BACKUP_INITIATOR).build());
        fixInitiator.stop();

        assertThatThrownBy(() -> fixInitiator.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BACKUP_INITIATOR.toString());
    }

    @Test
    void aBackupInAnotherGroupThanItsMainTargetIsRejected() {
        FixSessionId otherGroup = FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionIdBuilder.builder()
                .group("other-group").name("backup").senderCompID("SENDER44_DR2").targetCompID("TARGET44_DR2").build());
        
        FixInitiatorBuilder b = fixInitiatorBuilder.toBuilder()
                .instanceId("cross-group-initiator")
                .backupTarget(FixInitiatorTarget.builder()
                        .fixSessionId(otherGroup)
                        .connectAddress(new InetSocketAddress("localhost", acceptorPort))
                        .build())
                .build();
        assertThatThrownBy(() -> initiatorFixEngine.newInitiator(b))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(otherGroup.toString())
                .hasMessageContaining("other-group");
    }

    @Test
    void removedMainTargetSettingsBlockSwitchesUntilAddedBack() {
        logonMainTarget();
        initiatorStore().update(mainSettings().toBuilder().disconnectOnRemove(false).build());
        FixSession main = fixInitiator.getSession();
        FixSessionSettings settings = mainSettings();

        initiatorStore().remove(settings);

        assertThat(fixInitiator.getSession()).isSameAs(main);
        assertThatThrownBy(() -> fixInitiator.switchTo(BACKUP_INITIATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("removed");

        initiatorStore().add(settings);
        fixInitiator.switchTo(BACKUP_INITIATOR);

        assertThat(fixInitiator.getSession().getFixSessionId()).isEqualTo(BACKUP_INITIATOR);
    }

    @Test
    void anUnknownSessionIsRejected() {
        FixSessionId unknown = FixSessionId.of("unknown", FixRegularVersion.VERSION_44, "NOBODY", "NOWHERE");

        assertThatThrownBy(() -> fixInitiator.switchTo(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(BACKUP_INITIATOR.toString());
    }

    @Test
    void theAdminApiSwitchesTheInitiatorTargetingTheSession() {
        FixSession main = logonMainTarget();
        AdminApi adminApi = (AdminApi) initiatorFixEngine;

        adminApi.switchInitiatorSession(BACKUP_INITIATOR);

        assertThat(main.isLoggedIn()).isFalse();
        assertThat(fixInitiator.getSession().getFixSessionId()).isEqualTo(BACKUP_INITIATOR);
        assertThat(adminApi.getInitiatorsTargets()).singleElement().satisfies(targets -> {
            assertThat(targets.getInstanceId()).isEqualTo(fixInitiatorBuilder.getInstanceId());
            assertThat(targets.getActiveFixSessionId()).isEqualTo(BACKUP_INITIATOR);
            assertThat(targets.getMainTarget().getFixSessionId()).isEqualTo(main.getFixSessionId());
            assertThat(targets.getBackupTargets()).extracting(FixInitiatorTarget::getFixSessionId)
                    .containsExactly(BACKUP_INITIATOR);
        });
    }

    @Test
    void theAdminApiListsEachAcceptorWithItsSessions() {
        startAcceptorWithBothSessions();

        assertThat(((AdminApi) acceptorFixEngine).getAcceptorsSessions()).singleElement().satisfies(acceptor -> {
            assertThat(acceptor.getInstanceId()).isEqualTo(fixAcceptorBuilder.getInstanceId());
            assertThat(acceptor.getFixSessionIds()).containsExactlyInAnyOrder(
                    getAcceptorFixSessionSettings().build().getFixSessionId(), BACKUP_ACCEPTOR);
        });
        assertThat(((AdminApi) initiatorFixEngine).getAcceptorsSessions()).isEmpty();
    }

    @Test
    void theAdminApiRejectsASessionNoInitiatorTargets() {
        FixSessionId unknown = FixSessionId.of("unknown", FixRegularVersion.VERSION_44, "NOBODY", "NOWHERE");

        assertThatThrownBy(() -> ((AdminApi) initiatorFixEngine).switchInitiatorSession(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(unknown.toString());
    }

    @Test
    void switchingToTheActiveSessionChangesNothing() {
        FixSession main = logonMainTarget();

        fixInitiator.switchTo(main.getFixSessionId());

        assertThat(fixInitiator.getSession()).isSameAs(main);
        assertThat(main.isLoggedIn()).isTrue();
    }

    @Test
    void aSwitchWhileNoCounterpartyAnswersDialsTheBackupInstead() {
        fixInitiator.start();
        trapCreatedFixSession(ConnectorType.INITIATOR);

        fixInitiator.switchTo(BACKUP_INITIATOR);
        startAcceptorWithBothSessions();

        FixSession backup = fixInitiator.getSession();
        assertThat(backup.getFixSessionId()).isEqualTo(BACKUP_INITIATOR);
        await().untilAsserted(() -> assertThat(backup.isConnected()).isTrue());
        assertThat(fixInitiatorSession.isConnected()).isFalse();
    }

    @Test
    void aSwitchWhileStoppedPicksTheSessionTheNextStartRuns() {
        fixInitiator.switchTo(BACKUP_INITIATOR);

        assertThat(((AdminApi) initiatorFixEngine).getInitiatorsTargets()).singleElement()
                .satisfies(targets -> assertThat(targets.getActiveFixSessionId()).isEqualTo(BACKUP_INITIATOR));
        startAcceptorWithBothSessions();
        fixInitiator.start();
        trapCreatedFixSession(ConnectorType.INITIATOR);

        assertThat(fixInitiatorSession.getFixSessionId()).isEqualTo(BACKUP_INITIATOR);
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isTrue());
    }

    @Test
    void eachSessionKeepsItsOwnSequenceNumbersAcrossSwitches() {
        FixSession main = logonMainTarget();
        AdminApi adminApi = (AdminApi) initiatorFixEngine;
        for (int i = 0; i < 3; i++) {
            main.send(encodeTestMessage(i), null);
        }
        await().untilAsserted(() -> assertThat(adminApi.getOutgoingSeqNum(main.getFixSessionId())).isEqualTo(5));

        fixInitiator.switchTo(BACKUP_INITIATOR);
        FixSession backup = fixInitiator.getSession();
        await().untilAsserted(() -> assertThat(backup.isConnected()).isTrue());
        backup.logon();
        await().untilAsserted(() -> assertThat(backup.isLoggedIn()).isTrue());
        assertThat(adminApi.getOutgoingSeqNum(BACKUP_INITIATOR)).isEqualTo(2);

        fixInitiator.switchTo(main.getFixSessionId());

        assertThat(adminApi.getOutgoingSeqNum(main.getFixSessionId())).as("where the Logout of the first switch left it").isEqualTo(6);
    }

    @Test
    void switchesAndStopLeaveNoIoThreadBehind() {
        FixSession main = logonMainTarget();

        for (FixSessionId target : List.of(BACKUP_INITIATOR, main.getFixSessionId(), BACKUP_INITIATOR)) {
            Set<Thread> previousTargetThreads = initiatorIoThreads();
            assertThat(previousTargetThreads).isNotEmpty();

            fixInitiator.switchTo(target);

            await().untilAsserted(() -> assertThat(previousTargetThreads).noneMatch(Thread::isAlive));
            assertThat(initiatorIoThreads()).hasSameSizeAs(previousTargetThreads);
        }
        Set<Thread> lastTargetThreads = initiatorIoThreads();
        fixInitiator.stop();

        await().untilAsserted(() -> assertThat(lastTargetThreads).noneMatch(Thread::isAlive));
        assertThat(initiatorIoThreads()).isEmpty();
    }

    @Test
    void aSwitchWhoseSessionFailsToStartStopsTheInitiatorAndStartRetriesIt() {
        FixSession main = logonMainTarget();
        doThrow(new IllegalStateException("backup application down")).when(fixInitiatorApplication)
                .setup(argThat(settings -> settings.getFixSessionId().equals(BACKUP_INITIATOR)), any(), any());

        assertThatThrownBy(() -> fixInitiator.switchTo(BACKUP_INITIATOR)).hasMessage("backup application down");

        assertStoppedWithoutThreads();
        assertThat(main.isLoggedIn()).isFalse();
        assertThat(initiatorFixEngine.getFixSessionRegistry().find(BACKUP_INITIATOR)).isEmpty();
        verify(fixInitiatorApplication).onSessionPreDestroy(argThat(session -> session.getFixSessionId().equals(BACKUP_INITIATOR)));

        setupOrResetFixInitiatorApplication();
        fixInitiator.start();

        assertThat(fixInitiator.getSession().getFixSessionId()).isEqualTo(BACKUP_INITIATOR);
        await().untilAsserted(() -> assertThat(fixInitiator.getSession().isConnected()).isTrue());
    }

    @Test
    void aSwitchWhoseTargetCannotBeResolvedStopsTheInitiator() {
        logonMainTarget();
        initiatorStore().update(mainSettings().toBuilder().restartLiveSessionOnUpdate(false).build());
        initiatorStore().update(mainSettings().toBuilder().fixMessageStoreInstanceId("no-such-store").build());

        assertThatThrownBy(() -> fixInitiator.switchTo(BACKUP_INITIATOR)).hasMessageContaining("no-such-store");

        assertStoppedWithoutThreads();
    }

    @Test
    void aFailedStartReleasesWhatTheInitiatorStarted() {
        startAcceptorWithBothSessions();
        doThrow(new IllegalStateException("main application down")).when(fixInitiatorApplication).setup(any(), any(), any());

        assertThatThrownBy(() -> fixInitiator.start()).hasMessage("main application down");

        assertStoppedWithoutThreads();
        setupOrResetFixInitiatorApplication();
        fixInitiator.start();
        trapCreatedFixSession(ConnectorType.INITIATOR);
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isTrue());
    }

    private void assertStoppedWithoutThreads() {
        assertThat(fixInitiator.isStarted()).isFalse();
        await().untilAsserted(() -> assertThat(initiatorIoThreads()).isEmpty());
        await().untilAsserted(() -> assertThat(threadsNamed("Staffix-client-scheduler-" + fixInitiatorBuilder.getInstanceId())).isEmpty());
    }

    private Set<Thread> initiatorIoThreads() {
        return threadsNamed("IO-worker-" + fixInitiatorBuilder.getInstanceId() + "-");
    }
}
