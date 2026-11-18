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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What happens to a <em>live</em> session when its settings are removed from, or updated in, the store it came from: a
 * removal stops it, and an update never interrupts it unless a reload through the admin API asks for a restart.
 * <p>
 * {@link TestFixEngineReloadSessionsSettings} covers which listener callbacks a reload fires, against a mock listener.
 * This one covers what an acceptor and an initiator actually do with them once a session is logged on.
 */
class TestFixSessionsSettingsStoreReloadLiveSessions extends AbstractFixTests {

    private static final Duration UPDATED_TIMEOUT = Duration.ofSeconds(11);

    private static FixSessionsSettingsStore storeOf(FixEngine engine) {
        return engine.getFixSessionsSettingsStores().get(0);
    }

    private static FixSessionSettings managed(FixEngine engine, FixSession session) {
        FixSessionsSettingsStore store = storeOf(engine);
        Optional<FixSessionSettings> settings = store.find(session.getFixSessionId(),
                session.getFixSessionSettings().getFixSessionType());
        assertThat(settings).as("settings for %s", session.getFixSessionId()).isPresent();
        return settings.get();
    }

    @Test
    void removingALiveAcceptorSessionDisconnectsIt() {
        logonClient();
        assertThat(fixAcceptorSession.isConnected()).isTrue();

        FixSessionSettings settings = managed(acceptorFixEngine, fixAcceptorSession);
        storeOf(acceptorFixEngine).remove(settings);

        assertThat(storeOf(acceptorFixEngine).getSettings()).doesNotContain(settings);
        await().untilAsserted(() -> assertThat(fixAcceptorSession.isConnected()).isFalse());
    }

    /**
     * The new settings are managed at once and take effect the next time the session connects, not now.
     */
    @Test
    void updatingALiveAcceptorSessionLeavesItConnected() {
        logonClient();
        assertThat(fixAcceptorSession.isConnected()).isTrue();

        FixSessionSettings settings = managed(acceptorFixEngine, fixAcceptorSession);
        storeOf(acceptorFixEngine).update(settings.toBuilder().logInOrOutResponseTimeout(UPDATED_TIMEOUT).build());

        assertThat(managed(acceptorFixEngine, fixAcceptorSession).getLogInOrOutResponseTimeout())
                .as("the new settings are managed even though nothing was restarted")
                .isEqualTo(UPDATED_TIMEOUT);
        await().during(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(fixAcceptorSession.isConnected()).isTrue());
    }

    @Test
    void removingALiveInitiatorSessionDisconnectsIt() {
        logonClient();
        assertThat(fixInitiatorSession.isConnected()).isTrue();

        storeOf(initiatorFixEngine).remove(managed(initiatorFixEngine, fixInitiatorSession));

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isFalse());
    }

    @Test
    void updatingALiveInitiatorSessionLeavesItConnected() {
        logonClient();
        assertThat(fixInitiatorSession.isConnected()).isTrue();

        FixSessionSettings settings = managed(initiatorFixEngine, fixInitiatorSession);
        storeOf(initiatorFixEngine).update(settings.toBuilder().logInOrOutResponseTimeout(UPDATED_TIMEOUT).build());

        assertThat(managed(initiatorFixEngine, fixInitiatorSession).getLogInOrOutResponseTimeout())
                .isEqualTo(UPDATED_TIMEOUT);
        await().during(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isTrue());
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void aReloadAskedToRestartRestartsTheLiveSessionsItUpdates(ConnectorType connectorType) {
        ControllableSettingsStore store = reloadableStore(connectorType);
        logonClient();
        FixSession liveSession = getFixSession(connectorType);
        store.setSource(liveSession.getFixSessionSettings().toBuilder().logInOrOutResponseTimeout(UPDATED_TIMEOUT).build());

        reload(connectorType, store, true);

        await().untilAsserted(() -> assertThat(liveSession.isConnected()).isFalse());
        assertThat(managedSessionOf(connectorType).getFixSessionSettings().getLogInOrOutResponseTimeout())
                .isEqualTo(UPDATED_TIMEOUT);
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void aReloadStopsTheLiveSessionsItRemoves(ConnectorType connectorType) {
        ControllableSettingsStore store = reloadableStore(connectorType);
        logonClient();
        FixSession liveSession = getFixSession(connectorType);
        store.setSource();

        reload(connectorType, store, false);

        await().untilAsserted(() -> assertThat(liveSession.isConnected()).isFalse());
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void aReloadNotAskedToRestartLeavesTheLiveSessionsItUpdatesConnected(ConnectorType connectorType) {
        ControllableSettingsStore store = reloadableStore(connectorType);
        logonClient();
        FixSession liveSession = getFixSession(connectorType);
        store.setSource(liveSession.getFixSessionSettings().toBuilder().logInOrOutResponseTimeout(UPDATED_TIMEOUT).build());

        reload(connectorType, store, false);

        assertThat(managed(connectorType.select(initiatorFixEngine, acceptorFixEngine), liveSession)
                .getLogInOrOutResponseTimeout()).isEqualTo(UPDATED_TIMEOUT);
        await().during(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(liveSession.isConnected()).isTrue());
    }

    private ControllableSettingsStore reloadableStore(ConnectorType connectorType) {
        ControllableSettingsStore store = new ControllableSettingsStore(InstanceProvider.DEFAULT_INSTANCE_ID);
        FixSessionSettings settings = connectorType.select(getInitiatorFixSessionSettings(), getAcceptorFixSessionSettings()).build();
        store.add(settings);
        store.setSource(settings);
        if (connectorType == ConnectorType.INITIATOR) {
            setupInitiatorSessionsSettingsStore(settings, new ControllableSettingsStore.Settings(store));
        } else {
            setupAcceptorSessionsSettingsStore(settings, new ControllableSettingsStore.Settings(store));
        }
        return store;
    }

    private void reload(ConnectorType connectorType, ControllableSettingsStore store, boolean restartLiveSessions) {
        ((AdminApi) connectorType.select(initiatorFixEngine, acceptorFixEngine))
                .reloadFixSessionsSettingsStore(store.getInstanceId(), restartLiveSessions);
    }

    private FixSession managedSessionOf(ConnectorType connectorType) {
        return ((AdminApi) connectorType.select(initiatorFixEngine, acceptorFixEngine)).getManagedFixSessions().get(0);
    }
}
