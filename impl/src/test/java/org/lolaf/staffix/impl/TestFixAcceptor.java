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
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionRegistry;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

class TestFixAcceptor extends AbstractFixTests {

    /**
     * A stopped acceptor must leave nothing behind in its engine's {@link FixSessionRegistry}, which its sessions
     * joined when the acceptor started managing them. It used to, so anything resolving a session through the registry
     * - and any later acceptor configured with the same {@link FixSessionId} - was handed the dead instance of a
     * previous run.
     */
    @Test
    void testStoppedAcceptorLeavesNothingInTheSessionRegistry() {
        startFixAcceptor();
        FixSessionRegistry registry = acceptorFixEngine.getFixSessionRegistry();
        FixSessionId fixSessionId = fixAcceptorSession.getFixSessionId();
        assertThat(registry.find(fixSessionId)).contains(fixAcceptorSession);

        fixAcceptor.stop(Deadline.unlimited());

        assertThat(registry.find(fixSessionId)).isEmpty();
    }

    /**
     * A session the store gains after start up must show up in {@link FixAcceptor#getConfiguredSessionsSettings()},
     * as one present at start up does. It did not: {@code onAddedSession} stood the session up without recording its
     * settings, while {@code onRemovedSession} took them back out, so the two were only ever in step for sessions
     * that were there from the beginning.
     */
    @Test
    void testSessionAddedAfterStartUpIsReportedInConfiguredSettings() {
        startFixAcceptor();
        FixSessionSettings added = getAcceptorFixSessionSettings()
                .fixSessionId(FixSessionId.of("added-at-runtime", FixRegularVersion.VERSION_44, "LATE_TARGET", "LATE_SENDER"))
                .build();

        acceptorFixEngine.getFixSessionsSettingsStores().get(0).add(added);

        assertThat(fixAcceptor.getConfiguredSessionsSettings()).contains(added);
        assertThat(fixAcceptor.getSessions())
                .anyMatch(s -> s.getFixSessionId().equals(added.getFixSessionId()));
    }

    /**
     * The registry is reachable from a {@link org.lolaf.staffix.api.session.FixSession}, which is what every
     * {@link org.lolaf.staffix.api.application.FixApplication} callback is handed, and it is the registry of that
     * session's own engine - not the peer's, even though both ends run in this JVM.
     */
    @Test
    void testSessionExposesItsOwnEngineRegistry() {
        logonClient();

        assertThat(fixAcceptorSession.getFixSessionRegistry())
                .isSameAs(acceptorFixEngine.getFixSessionRegistry());
        assertThat(fixInitiatorSession.getFixSessionRegistry())
                .isSameAs(initiatorFixEngine.getFixSessionRegistry());

        // each side sees itself and not its counterparty, the two engines being separate
        assertThat(fixAcceptorSession.getFixSessionRegistry().find(fixAcceptorSession.getFixSessionId()))
                .contains(fixAcceptorSession);
        assertThat(fixAcceptorSession.getFixSessionRegistry().find(fixInitiatorSession.getFixSessionId()))
                .isEmpty();
    }

    @Test
    void testConnectRejectWithNonAllowedIpAddress() throws UnknownHostException {
        InetAddress allowedIp = InetAddress.getByName("127.0.0.2");
        setupAcceptorSessionSettings(builder -> builder
                .allowedAddress(allowedIp).build());

        connectFixInitiatorAndAcceptor();

        fixInitiatorSession.logon();

        // atLeastOnce, not once: the rejected initiator keeps reconnecting every connectionRetry (100ms here) and each
        // attempt is rejected in turn, so the count grows for as long as the test runs - it was seen reaching 266.
        // Awaiting an exact count is a race that can never recover once the second rejection has landed.
        await().untilAsserted(() -> verify(fixSessionEventsListener, atLeastOnce()).onFixSessionRejected(
                any(FixSessionId.class),
                assertArg(t ->
                        assertThat(t.getClass()).isEqualTo(FixAcceptor.RejectedIpException.class))));
    }

    @Test
    void testConnectAcceptWithAllowedIpAddress() throws UnknownHostException {
        InetAddress allowedIp = InetAddress.getByName("127.0.0.1");
        setupAcceptorSessionSettings(builder -> builder
                .allowedAddress(allowedIp).build());

        logonClient();
    }

    @Test
    void testConnectRejectedWithMultipleLogon() {
        logonClient();

        // The second client gets an engine of its own, which is what it is in reality - another process dialling the
        // same session. Two initiators for one FixSessionId inside a single engine is a configuration error the
        // registry now refuses, and would leave FixEngineImpl.findControl with two controls to choose between.
        FixEngine rogueEngine = initiatorFixEngineBuilder.toBuilder().build().instance();
        rogueEngine.start();
        FixInitiator fixInitiator2 = rogueEngine.newInitiator(fixInitiatorBuilder.toBuilder()
                .instanceId("clone")
                .build());

        fixInitiator2.start();
        fixInitiator2.getSession().logon();

        try {
            await().untilAsserted(() -> verify(fixSessionEventsListener).onFixSessionRejected(
                    any(FixSessionId.class),
                    assertArg(t ->
                            assertThat(t.getClass()).isEqualTo(FixAcceptor.MultipleLogonException.class))));
        } finally {
            // Not covered by the shutdown in AbstractFixTests, which only knows the two engines it built.
            rogueEngine.stop(Deadline.unlimited());
        }
    }
}