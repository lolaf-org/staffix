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
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What happens to a <em>live</em> session when its settings are removed from, or updated in, the store it came from.
 * <p>
 * {@link TestFixEngineReloadSessionsSettings} covers the other half of this - which listener callbacks a reload
 * fires, against a mock listener. This one covers what an acceptor and an initiator actually do with those callbacks
 * once a session is logged on, which is where {@link FixSessionSettings#isDisconnectOnRemove()} and
 * {@link FixSessionSettings#isRestartLiveSessionOnUpdate()} decide the outcome.
 * <p>
 * The store's {@code add}/{@code remove}/{@code update} are driven directly rather than through
 * {@link org.lolaf.staffix.api.admin.AdminApi#reloadFixSessionsSettingsStore(String)}: they are public API in their
 * own right, a reload is only one of their callers, and the behaviour under test belongs to the listener contract
 * rather than to reconciliation.
 */
class TestFixSessionsSettingsStoreReloadLiveSessions extends AbstractFixTests {

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

    /**
     * The default, and today's behaviour: removing an acceptor session's settings takes the live session down.
     */
    @Test
    void removingAnAcceptorSessionDisconnectsItByDefault() {
        logonClient();
        assertThat(fixAcceptorSession.isConnected()).isTrue();

        FixSessionSettings settings = managed(acceptorFixEngine, fixAcceptorSession);
        assertThat(settings.isDisconnectOnRemove()).isTrue();
        storeOf(acceptorFixEngine).remove(settings);

        await().untilAsserted(() -> assertThat(fixAcceptorSession.isConnected()).isFalse());
        assertThat(storeOf(acceptorFixEngine).getSettings()).doesNotContain(settings);
    }

    /**
     * With {@code disconnectOnRemove=false} the settings go but the session is left alone: the engine stops managing
     * it, and it keeps running until it drops for a reason of its own.
     */
    @Test
    void removingAnAcceptorSessionLeavesItConnectedWhenAskedTo() {
        setupAcceptorSessionSettings(builder -> builder.disconnectOnRemove(false).build());
        logonClient();
        assertThat(fixAcceptorSession.isConnected()).isTrue();

        FixSessionSettings settings = managed(acceptorFixEngine, fixAcceptorSession);
        storeOf(acceptorFixEngine).remove(settings);

        assertThat(storeOf(acceptorFixEngine).getSettings()).doesNotContain(settings);
        // nothing should take it down: give the engine room to do the wrong thing before concluding it did not
        await().during(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(fixAcceptorSession.isConnected()).isTrue());
    }

    /**
     * The default, and today's behaviour: an update restarts a live session so the new settings are in force
     * immediately.
     */
    @Test
    void updatingAnAcceptorSessionRestartsItByDefault() {
        logonClient();
        assertThat(fixAcceptorSession.isConnected()).isTrue();

        FixSessionSettings settings = managed(acceptorFixEngine, fixAcceptorSession);
        assertThat(settings.isRestartLiveSessionOnUpdate()).isTrue();
        storeOf(acceptorFixEngine).update(settings.toBuilder()
                .logInOrOutResponseTimeout(Duration.ofSeconds(11))
                .build());

        await().untilAsserted(() -> assertThat(fixAcceptorSession.isConnected()).isFalse());
        assertThat(managed(acceptorFixEngine, fixAcceptorSession).getLogInOrOutResponseTimeout())
                .isEqualTo(Duration.ofSeconds(11));
    }

    /**
     * With {@code restartLiveSessionOnUpdate=false} the new settings are stored and the live session is left up. They
     * take effect the next time the session is created, not now.
     */
    @Test
    void updatingAnAcceptorSessionLeavesItConnectedWhenAskedTo() {
        setupAcceptorSessionSettings(builder -> builder.restartLiveSessionOnUpdate(false).build());
        logonClient();
        assertThat(fixAcceptorSession.isConnected()).isTrue();

        FixSessionSettings settings = managed(acceptorFixEngine, fixAcceptorSession);
        storeOf(acceptorFixEngine).update(settings.toBuilder()
                .logInOrOutResponseTimeout(Duration.ofSeconds(12))
                .build());

        assertThat(managed(acceptorFixEngine, fixAcceptorSession).getLogInOrOutResponseTimeout())
                .as("the new settings are managed even though nothing was restarted")
                .isEqualTo(Duration.ofSeconds(12));
        await().during(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(fixAcceptorSession.isConnected()).isTrue());
    }

    /**
     * An initiator reacts to its own session's settings being removed. Before this was implemented an initiator was
     * not registered as a listener at all, so a reload had no effect on it whatsoever.
     */
    @Test
    void removingAnInitiatorSessionDisconnectsItByDefault() {
        logonClient();
        assertThat(fixInitiatorSession.isConnected()).isTrue();

        FixSessionSettings settings = managed(initiatorFixEngine, fixInitiatorSession);
        assertThat(settings.isDisconnectOnRemove()).isTrue();
        storeOf(initiatorFixEngine).remove(settings);

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isFalse());
    }

    @Test
    void removingAnInitiatorSessionLeavesItConnectedWhenAskedTo() {
        setupInitiatorSessionSettings(builder -> builder.disconnectOnRemove(false).build());
        logonClient();
        assertThat(fixInitiatorSession.isConnected()).isTrue();

        storeOf(initiatorFixEngine).remove(managed(initiatorFixEngine, fixInitiatorSession));

        await().during(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isTrue());
    }

    @Test
    void updatingAnInitiatorSessionRestartsItByDefault() {
        logonClient();
        assertThat(fixInitiatorSession.isConnected()).isTrue();

        FixSessionSettings settings = managed(initiatorFixEngine, fixInitiatorSession);
        storeOf(initiatorFixEngine).update(settings.toBuilder()
                .logInOrOutResponseTimeout(Duration.ofSeconds(13))
                .build());

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isFalse());
        assertThat(managed(initiatorFixEngine, fixInitiatorSession).getLogInOrOutResponseTimeout())
                .isEqualTo(Duration.ofSeconds(13));
    }

    @Test
    void updatingAnInitiatorSessionLeavesItConnectedWhenAskedTo() {
        setupInitiatorSessionSettings(builder -> builder.restartLiveSessionOnUpdate(false).build());
        logonClient();
        assertThat(fixInitiatorSession.isConnected()).isTrue();

        FixSessionSettings settings = managed(initiatorFixEngine, fixInitiatorSession);
        storeOf(initiatorFixEngine).update(settings.toBuilder()
                .logInOrOutResponseTimeout(Duration.ofSeconds(14))
                .build());

        assertThat(managed(initiatorFixEngine, fixInitiatorSession).getLogInOrOutResponseTimeout())
                .isEqualTo(Duration.ofSeconds(14));
        await().during(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isTrue());
    }

    /**
     * The flag that governs an update is read from the settings the running session was started under, not from the
     * ones replacing them: it describes how that session may be treated, and the session being replaced is the one
     * being disturbed.
     */
    @Test
    void theUpdatePolicyIsReadFromTheSettingsTheSessionIsRunningUnder() {
        setupAcceptorSessionSettings(builder -> builder.restartLiveSessionOnUpdate(false).build());
        logonClient();

        FixSessionSettings settings = managed(acceptorFixEngine, fixAcceptorSession);
        // the incoming settings ask for a restart; the running ones say no, and they win
        storeOf(acceptorFixEngine).update(settings.toBuilder()
                .restartLiveSessionOnUpdate(true)
                .logInOrOutResponseTimeout(Duration.ofSeconds(15))
                .build());

        await().during(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(fixAcceptorSession.isConnected()).isTrue());
        assertThat(managed(acceptorFixEngine, fixAcceptorSession).isRestartLiveSessionOnUpdate())
                .as("the new policy is managed and governs the next update")
                .isTrue();
    }
}
