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
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionRegistry;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.fix44.fields.EncryptMethod;
import org.lolaf.staffix.fix44.fields.HeartBtInt;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.FixMessageFields;
import org.lolaf.staffix.tests.TestingFixMessagesStoreSettings;
import org.lolaf.staffix.tests.RawFixSocketClient;
import org.lolaf.staffix.tests.TestingFixSessionMessagesStore;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("java:S2699")
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

    @Test
    void testConfiguredSettingsAreASnapshot() {
        startFixAcceptor();
        Set<FixSessionSettings> before = fixAcceptor.getConfiguredSessionsSettings();
        FixSessionSettings added = getAcceptorFixSessionSettings()
                .fixSessionId(FixSessionId.of("added-after-snapshot", FixRegularVersion.VERSION_44, "SNAP_TARGET", "SNAP_SENDER"))
                .build();

        acceptorFixEngine.getFixSessionsSettingsStores().get(0).add(added);

        assertThat(before).doesNotContain(added);
        assertThat(fixAcceptor.getConfiguredSessionsSettings()).contains(added);
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

        startFixInitiatorAndAcceptor();

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
        FixEngine rogueEngine = initiatorFixEngineBuilder.toBuilder()
                .clearFixMessagesStores()
                .fixMessagesStore(TestingFixMessagesStoreSettings.builder()
                        .testingFixSessionMessagesStore(new TestingFixSessionMessagesStore())
                        .build())
                .build().instance();
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

    /**
     * A connection that never sends its Logon used to stay open for as long as the client liked.
     */
    @Test
    void testConnectionWithoutLogonIsClosedAfterTheLogonTimeout() throws IOException {
        useAcceptorWithLogonTimeout(Duration.ofMillis(500));
        startFixAcceptor();

        try (RawFixSocketClient.Session silent = RawFixSocketClient.connect(acceptorPort, Duration.ofSeconds(5))) {
            assertThat(silent.isClosedByPeer(Duration.ofSeconds(10))).isTrue();
        }
    }

    @Test
    void testLogonTimeoutLeavesALoggedOnSessionConnected() {
        useAcceptorWithLogonTimeout(Duration.ofMillis(500));
        logonClient();

        LockSupport.parkNanos(Duration.ofSeconds(1).toNanos());

        assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
    }

    /**
     * The timeout waits for the Logon, not for the session to be logged in: a validation outlasting it is the
     * application's to bound.
     */
    @Test
    void testLogonTimeoutSparesALogonStillBeingValidated() throws IOException {
        when(fixAcceptorApplication.validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class)))
                .thenAnswer(invocation -> CompletableFuture.supplyAsync(Optional::empty,
                        CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS)));
        useAcceptorWithLogonTimeout(Duration.ofMillis(300));
        startFixAcceptor();
        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();

        try (RawFixSocketClient.Session peer = RawFixSocketClient.connect(acceptorPort, initiator.getFixVersion(),
                initiator.getSenderCompID().getValue(), initiator.getTargetCompID().getValue(), Duration.ofSeconds(5))) {
            peer.send(peer.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));

            String response = peer.readMessageOfType(MessageTypes.Logon, Duration.ofSeconds(10));

            assertThat(FixMessageFields.hasFieldWithValue(response, CoreFields.MESSAGE_TYPE, CoreMessageType.LOGON.code()))
                    .isTrue();
        }
    }

    /**
     * The CompIDs bind a connection to its session before the Logon is complete: one that stops there held the
     * session, refusing its real counterparty, with no timer running.
     */
    @Test
    void testConnectionStoppingBeforeTheEndOfItsLogonIsClosedAfterTheLogonTimeout() throws IOException {
        useAcceptorWithLogonTimeout(Duration.ofMillis(500));
        startFixAcceptor();
        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();

        try (RawFixSocketClient.Session stalled = RawFixSocketClient.connect(acceptorPort, initiator.getFixVersion(),
                initiator.getSenderCompID().getValue(), initiator.getTargetCompID().getValue(), Duration.ofSeconds(5))) {
            byte[] logon = stalled.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5").build();
            int withoutCheckSum = logon.length - "10=000\u0001".length();
            stalled.send(Arrays.copyOf(logon, withoutCheckSum));
            await().untilAsserted(() -> assertThat(fixAcceptorSession.isConnected()).isTrue());

            assertThat(stalled.isClosedByPeer(Duration.ofSeconds(10))).isTrue();
        }
    }

    private void useAcceptorWithLogonTimeout(Duration logonTimeout) {
        fixAcceptor = acceptorFixEngine.newAcceptor(fixAcceptorBuilder.toBuilder()
                .instanceId("logon-timeout")
                .logonTimeout(logonTimeout)
                .build());
    }
}
