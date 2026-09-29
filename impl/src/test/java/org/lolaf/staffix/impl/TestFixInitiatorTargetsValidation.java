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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixAcceptorBuilder;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.FixInitiatorBuilder;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.api.version.SemVer;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.lolaf.staffix.tests.TestingFixMessagesStoreSettings;
import org.lolaf.staffix.tests.TestingFixSessionMessagesStore;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestFixInitiatorTargetsValidation {

    private static final FixSessionId MAIN = FixSessionId.of("main", FixRegularVersion.VERSION_44, "CLIENT", "BROKER");
    private static final FixSessionId BACKUP = FixSessionId.of("backup", FixRegularVersion.VERSION_44, "CLIENT_DR", "BROKER_DR");
    private static final FixSessionId UNKNOWN = FixSessionId.of("unknown", FixRegularVersion.VERSION_44, "NOBODY", "NOWHERE");
    private static final FixSessionId SHARED_WITH_ACCEPTOR = FixSessionId.of("shared", FixRegularVersion.VERSION_44, "SHARED_A", "SHARED_B");
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("localhost", 1);

    private FixEngine fixEngine;

    private static FixSessionSettings settings(FixSessionId fixSessionId, FixSession.FixSessionType fixSessionType) {
        return FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(fixSessionType)
                .desiredSessionState(FixSessionState.LOGGED_OUT)
                .build();
    }

    private static FixInitiatorTarget target(FixSessionId fixSessionId) {
        return FixInitiatorTarget.builder().fixSessionId(fixSessionId).connectAddress(ADDRESS).build();
    }

    private static FixInitiatorBuilder.FixInitiatorBuilderBuilder<?, ?> initiator(String instanceId) {
        return FixInitiatorBuilder.builder()
                .instanceId(instanceId)
                .mainTarget(target(MAIN));
    }

    @BeforeEach
    void startEngine() {
        FixApplication application = mock(FixApplication.class);
        when(application.getFixApiVersion()).thenReturn(FixApiVersion.of("test app", SemVer.of(1, 0, 0), "test vendor"));
        fixEngine = FixEngineBuilder.builder()
                .fixMessagesStore(TestingFixMessagesStoreSettings.builder()
                        .testingFixSessionMessagesStore(new TestingFixSessionMessagesStore())
                        .build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .application(InstanceProvider.DEFAULT_INSTANCE_ID, application)
                        .build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(settings(MAIN, FixSession.FixSessionType.INITIATOR))
                        .fixSessionSetting(settings(BACKUP, FixSession.FixSessionType.INITIATOR))
                        .fixSessionSetting(settings(SHARED_WITH_ACCEPTOR, FixSession.FixSessionType.INITIATOR))
                        .fixSessionSetting(settings(SHARED_WITH_ACCEPTOR, FixSession.FixSessionType.ACCEPTOR))
                        .build())
                .build()
                .instance();
        fixEngine.start();
    }

    @AfterEach
    void stopEngine() {
        fixEngine.stop(Deadline.unlimited());
    }

    @Test
    void theTargetsAreTheMainTargetFollowedByTheBackupTargets() {
        assertThat(fixEngine.newInitiator(initiator("initiator").backupTarget(target(BACKUP)).build()).getFixSessionIds())
                .containsExactly(MAIN, BACKUP);
    }

    @Test
    void aBackupMissingFromTheStoresIsRejected() {
        assertThatThrownBy(() -> fixEngine.newInitiator(initiator("initiator").backupTarget(target(UNKNOWN)).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(UNKNOWN.toString());
    }

    @Test
    void aBackupTargetRepeatingTheMainTargetIsRejected() {
        assertThatThrownBy(() -> fixEngine.newInitiator(initiator("initiator").backupTarget(target(MAIN)).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than once");
    }

    @Test
    void aBackupWithoutAddressIsRejected() {
        FixInitiatorTarget noAddress = FixInitiatorTarget.builder().fixSessionId(BACKUP).build();

        assertThatThrownBy(() -> fixEngine.newInitiator(initiator("initiator").backupTarget(noAddress).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no connect address");
    }

    @Test
    void aMainTargetWithoutAddressIsRejected() {
        FixInitiatorBuilder noAddress = FixInitiatorBuilder.builder()
                .mainTarget(FixInitiatorTarget.builder().fixSessionId(MAIN).build())
                .build();

        assertThatThrownBy(() -> fixEngine.newInitiator(noAddress))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no connect address");
    }

    @Test
    void aBackupAlreadyTargetedByAnotherInitiatorIsRejected() {
        fixEngine.newInitiator(FixInitiatorBuilder.builder()
                .instanceId("first")
                .mainTarget(target(BACKUP))
                .build());

        assertThatThrownBy(() -> fixEngine.newInitiator(initiator("second").backupTarget(target(BACKUP)).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("initiator 'first'");
    }

    @Test
    void aMainTargetAlreadyBackingAnotherInitiatorIsRejected() {
        fixEngine.newInitiator(initiator("first").backupTarget(target(BACKUP)).build());

        assertThatThrownBy(() -> fixEngine.newInitiator(FixInitiatorBuilder.builder()
                .instanceId("second")
                .mainTarget(target(BACKUP))
                .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backup of initiator 'first'");
    }

    @Test
    void twoInitiatorsMayStillShareAMainTarget() {
        fixEngine.newInitiator(initiator("first").build());

        assertThat(fixEngine.newInitiator(initiator("second").build()).getFixSessionIds()).containsExactly(MAIN);
    }

    @Test
    void aBackupManagedByAnAcceptorIsRejected() {
        fixEngine.newAcceptor(FixAcceptorBuilder.builder()
                .bindAddress(new InetSocketAddress("localhost", 0))
                .build()).start();

        assertThatThrownBy(() -> fixEngine.newInitiator(initiator("initiator").backupTarget(target(SHARED_WITH_ACCEPTOR)).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("acceptor");
    }
}
