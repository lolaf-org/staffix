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
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.api.version.SemVer;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.tests.TestingFixMessagesStoreSettings;
import org.lolaf.staffix.tests.TestingFixSessionMessagesStore;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestFixEngineSessionsSettingsWrites {

    private static final String STORE_ID = "test-store";
    private static final FixApplicationSessionSettingDescriptor ACCOUNT =
            FixApplicationSessionSettingDescriptor.of("writes.account", "The account orders are booked to");

    private final ControllableSettingsStore store = new ControllableSettingsStore(STORE_ID);
    private FixEngine fixEngine;
    private AdminApi adminApi;

    private static FixSessionSettings session(String name, String target) {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(name, FixRegularVersion.VERSION_44, "SENDER", target))
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .desiredSessionState(FixSessionState.LOGGED_OUT)
                .fixApplicationSessionSetting(ACCOUNT, "ACC-1")
                .build();
    }

    @BeforeEach
    void startEngineWithAnAcceptorOnTheStore() {
        FixApplication application = mock(FixApplication.class);
        when(application.getFixApiVersion()).thenReturn(FixApiVersion.of("test app", SemVer.of(1, 0, 0), "test vendor"));
        when(application.getRequiredFixSessionSettings()).thenReturn(List.of(ACCOUNT));
        fixEngine = FixEngineBuilder.builder()
                .fixMessagesStore(TestingFixMessagesStoreSettings.builder()
                        .testingFixSessionMessagesStore(new TestingFixSessionMessagesStore())
                        .build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .application(InstanceProvider.DEFAULT_INSTANCE_ID, application)
                        .build())
                .fixSessionsSettingsStore(new ControllableSettingsStore.Settings(store))
                .build()
                .instance();
        fixEngine.start();
        fixEngine.newAcceptor(FixAcceptorBuilder.builder()
                .instanceId("acceptor")
                .bindAddress(new InetSocketAddress("localhost", 0))
                .targetFixSessionsSettingsStoreInstancesIds(List.of(STORE_ID))
                .build()).start();
        adminApi = (AdminApi) fixEngine;
    }

    @AfterEach
    void stopEngine() {
        fixEngine.stop(Deadline.unlimited());
    }

    @Test
    void anAddedSessionIsServedAndFoundInItsStore() {
        FixSessionSettings alpha = session("alpha", "ALPHA");

        adminApi.addFixSessionSettings(STORE_ID, alpha);

        assertThat(adminApi.getIncomingSeqNum(alpha.getFixSessionId())).isPositive();
        assertThat(adminApi.findFixSessionsSettingsStore(alpha.getFixSessionId(), FixSession.FixSessionType.ACCEPTOR))
                .contains(STORE_ID);
        assertThat(adminApi.isFixSessionsSettingsStorePersistent(STORE_ID)).isFalse();
        assertThat(adminApi.getFixApplicationSessionSettingDescriptors(alpha.getFixSessionId())).containsExactly(ACCOUNT);
    }

    @Test
    void aNameAlreadyTakenIsRefused() {
        adminApi.addFixSessionSettings(STORE_ID, session("alpha", "ALPHA"));

        assertThatThrownBy(() -> adminApi.addFixSessionSettings(STORE_ID, session("alpha", "BETA")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has a session default.alpha");
    }

    @Test
    void invalidSettingsOrAnUnknownStoreAreRefusedBeforeAnythingChanges() {
        FixSessionSettings invalid = session("alpha", "ALPHA").toBuilder()
                .validationSettings(FixSessionSettings.ValidationSettings.builder().maxMessageSize(0).build())
                .build();

        assertThatThrownBy(() -> adminApi.addFixSessionSettings(STORE_ID, invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adminApi.addFixSessionSettings("missing", session("alpha", "ALPHA")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
        assertThat(store.getSettings()).isEmpty();
    }

    @Test
    void anUpdateKeepingTheIdReplacesTheSettingsInPlace() {
        FixSessionSettings alpha = session("alpha", "ALPHA");
        adminApi.addFixSessionSettings(STORE_ID, alpha);
        FixSessionSettings loggedIn = alpha.toBuilder().desiredSessionState(FixSessionState.LOGGED_IN).build();

        adminApi.updateFixSessionSettings(alpha.getFixSessionId(), loggedIn);

        assertThat(store.getSettings()).containsExactly(loggedIn);
    }

    @Test
    void anUpdateChangingTheCompIdsReplacesTheSession() {
        FixSessionSettings alpha = session("alpha", "ALPHA");
        adminApi.addFixSessionSettings(STORE_ID, alpha);
        FixSessionSettings retargeted = session("alpha", "ALPHA2");

        adminApi.updateFixSessionSettings(alpha.getFixSessionId(), retargeted);

        assertThat(store.getSettings()).containsExactly(retargeted);
        assertThat(adminApi.getIncomingSeqNum(retargeted.getFixSessionId())).isPositive();
        assertThatThrownBy(() -> adminApi.getIncomingSeqNum(alpha.getFixSessionId())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUpdateCannotTakeAnotherSessionsName() {
        FixSessionSettings alpha = session("alpha", "ALPHA");
        adminApi.addFixSessionSettings(STORE_ID, alpha);
        adminApi.addFixSessionSettings(STORE_ID, session("beta", "BETA"));

        assertThatThrownBy(() -> adminApi.updateFixSessionSettings(alpha.getFixSessionId(), session("beta", "ALPHA")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.getSettings()).contains(alpha);
    }

    @Test
    void aRemovedSessionIsNoLongerServed() {
        FixSessionSettings alpha = session("alpha", "ALPHA");
        adminApi.addFixSessionSettings(STORE_ID, alpha);

        adminApi.removeFixSessionSettings(alpha.getFixSessionId(), FixSession.FixSessionType.ACCEPTOR);

        assertThat(store.getSettings()).isEmpty();
        assertThatThrownBy(() -> adminApi.getIncomingSeqNum(alpha.getFixSessionId())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesToASessionNoStoreHoldsAreRefused() {
        FixSessionSettings alpha = session("alpha", "ALPHA");

        assertThatThrownBy(() -> adminApi.updateFixSessionSettings(alpha.getFixSessionId(), alpha))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No store holds");
        assertThatThrownBy(() -> adminApi.removeFixSessionSettings(alpha.getFixSessionId(), FixSession.FixSessionType.ACCEPTOR))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
