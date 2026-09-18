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
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.CancelOnDisconnectType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.impl.session.FixSessionImpl;
import org.lolaf.staffix.codec.serde.CharSerde;
import org.lolaf.staffix.codec.serde.IntSerde;
import org.lolaf.staffix.tests.RawFixSocketClient;
import org.mockito.Mockito;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class TestFixCancelOnDisconnect extends AbstractFixTests {

    private static final String LOGOUT_MESSAGE = "end of day";

    FixSessionSettings.CancelOnDisconnectSettings initiatorCodSettings;
    FixSessionSettings.CancelOnDisconnectSettings acceptorCodSettings;

    private static Character getCodValue(DecodedFixMessage decodedFixMessage, int fieldCode) {
        return decodedFixMessage.getValue(fieldCode, new SerDe<>() {
            @Override
            public void serialize(ByteBuffer out, Character value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Character deserialize(DeserializationContext serdeContext) {
                return CharSerde.deserialize(serdeContext);
            }
        }, ' ');
    }

    private static int getCodWindowValue(DecodedFixMessage decodedFixMessage, int fieldCode) {
        return decodedFixMessage.getValue(fieldCode, new SerDe<>() {
            @Override
            public void serialize(ByteBuffer out, Integer value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Integer deserialize(DeserializationContext serdeContext) {
                return IntSerde.deserialize(serdeContext);
            }
        }, -1);
    }

    private static String msgTypeField(String messageType) {
        return CoreFields.FIELD_SEPARATOR + "35=" + messageType + CoreFields.FIELD_SEPARATOR;
    }

    @BeforeEach
    void setupCod() {
        initiatorCodSettings = FixSessionSettings.CancelOnDisconnectSettings.builder()
                .enabled(true)
                .codTimeoutWindow(Duration.ofMillis(100))
                .codTimeoutWindowScale(TimeUnit.MILLISECONDS)
                .build();

        acceptorCodSettings = FixSessionSettings.CancelOnDisconnectSettings.builder()
                .enabled(true)
                .codTimeoutWindow(Duration.ofMillis(100))
                .codTimeoutWindowScale(TimeUnit.MILLISECONDS)
                .build();
    }

    @Test
    void testUnknownCodTypeAndMinimalTimeoutWindow() {
        initiatorCodSettings = initiatorCodSettings.toBuilder()
                .codTimeoutWindow(Duration.ofMillis(1234))
                .build();
        acceptorCodSettings = initiatorCodSettings.toBuilder()
                .cancelOnDisconnectTypeFieldCodes(Map.of(CancelOnDisconnectType.CANCEL_ON_DISCONNECT_OR_LOGOUT, 'z'))
                .codTimeoutWindow(Duration.ofSeconds(3))
                .build();

        setupInitiatorAndAcceptor(CancelOnDisconnectType.CANCEL_ON_DISCONNECT_OR_LOGOUT);

        logonClient();

        await().untilAsserted(() -> {
            verify(fixInitiatorApplication).onMessageReject(any(FixSession.class), eq("Invalid COD enum value '3', allowed values are: [z]"),
                    anyInt(), eq(1L), eq(initiatorCodSettings.getCancelOnDisconnectTypeFieldCode()), anyString());
            verify(fixInitiatorApplication).onMessageReject(any(FixSession.class), eq("Invalid COD timeout window value '1234', must be minimum 3000 milliseconds"),
                    anyInt(), eq(1L), eq(initiatorCodSettings.getCodTimeoutWindowFieldCode()), anyString());
        });
    }

    @Test
    void testLogonWithCancelOnDisconnectDisabled() {
        initiatorCodSettings = initiatorCodSettings.toBuilder()
                .enabled(false)
                .build();
        acceptorCodSettings = acceptorCodSettings.toBuilder()
                .enabled(false)
                .build();
        setupInitiatorAndAcceptor(null);

        logonClient();

        assertExpectedCodLogonReceived(' ', -1);
    }

    @Test
    void testLogonWithDoNotCancelOnDisconnectOrLogout() {
        setupInitiatorAndAcceptor(CancelOnDisconnectType.DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT);

        logonClient();

        assertExpectedCodLogonReceived(initiatorCodSettings.getCancelOnDisconnectTypeFieldCodes().get(initiatorCodSettings.getCancelOnDisconnectType()),
                initiatorCodSettings.getCodTimeoutWindow().toMillis());

        fixInitiator.stop();

        await().untilAsserted(() -> {
            verify(fixAcceptorApplication).onLogout(any(), Mockito.anyString(), any());
            verify(fixAcceptorApplication).onDisconnected(any());
        });
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(initiatorCodSettings.getCodTimeoutWindow().toMillis()));
        verify(fixAcceptorApplication, never()).onCancelOnDisconnectTriggered(any(), any());
    }

    @Test
    void testLogonWithinTimeframeDoesNotTriggerCODTask() {
        initiatorCodSettings = initiatorCodSettings.toBuilder()
                .codTimeoutWindow(Duration.ofSeconds(3))
                .build();
        setupInitiatorAndAcceptor(CancelOnDisconnectType.CANCEL_ON_DISCONNECT_OR_LOGOUT);

        logonClient();

        assertExpectedCodLogonReceived(initiatorCodSettings.getCancelOnDisconnectTypeFieldCodes().get(initiatorCodSettings.getCancelOnDisconnectType()),
                initiatorCodSettings.getCodTimeoutWindow().toMillis());

        fixInitiator.stop();

        await().untilAsserted(() -> {
            verify(fixAcceptorApplication).onLogout(any(), Mockito.anyString(), any());
            verify(fixAcceptorApplication).onDisconnected(any());
        });
        setupOrResetFixInitiatorApplication();
        setupOrResetFixAcceptorApplication();

        logonClient();

        assertExpectedCodLogonReceived(initiatorCodSettings.getCancelOnDisconnectTypeFieldCodes().get(initiatorCodSettings.getCancelOnDisconnectType()),
                initiatorCodSettings.getCodTimeoutWindow().toMillis());

        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(initiatorCodSettings.getCodTimeoutWindow().toMillis()));
        verify(fixAcceptorApplication, never()).onCancelOnDisconnectTriggered(any(), any());
    }

    @Test
    void testLogonWithDoCancelOnDisconnectOrLogout() {
        setupInitiatorAndAcceptor(CancelOnDisconnectType.CANCEL_ON_DISCONNECT_OR_LOGOUT);

        logonClient();

        assertExpectedCodLogonReceived(initiatorCodSettings.getCancelOnDisconnectTypeFieldCodes().get(initiatorCodSettings.getCancelOnDisconnectType()),
                initiatorCodSettings.getCodTimeoutWindow().toMillis());

        fixInitiator.stop();

        await().untilAsserted(() -> {
            verify(fixAcceptorApplication).onLogout(any(), Mockito.anyString(), any());
            verify(fixAcceptorApplication).onDisconnected(any());
        });
        await().untilAsserted(() -> verify(fixAcceptorApplication)
                .onCancelOnDisconnectTriggered(any(), eq(CancelOnDisconnectType.CANCEL_ON_DISCONNECT_OR_LOGOUT)));
    }

    @Test
    void testLogonWithDoCancelOnLogoutOnly() {
        setupInitiatorAndAcceptor(CancelOnDisconnectType.CANCEL_ON_LOGOUT_ONLY);

        logonClient();

        assertExpectedCodLogonReceived(initiatorCodSettings.getCancelOnDisconnectTypeFieldCodes().get(initiatorCodSettings.getCancelOnDisconnectType()),
                initiatorCodSettings.getCodTimeoutWindow().toMillis());

        fixInitiator.stop();

        await().untilAsserted(() -> {
            verify(fixAcceptorApplication).onLogout(any(), Mockito.anyString(), any());
            verify(fixAcceptorApplication).onDisconnected(any());
        });
        await().untilAsserted(() -> verify(fixAcceptorApplication)
                .onCancelOnDisconnectTriggered(any(), eq(CancelOnDisconnectType.CANCEL_ON_LOGOUT_ONLY)));
    }

    @Test
    void testLogonWithDoCancelOnLogoutOnlyAndHardDisconnectionDoesNotTriggerCod() {
        setupInitiatorAndAcceptor(CancelOnDisconnectType.CANCEL_ON_LOGOUT_ONLY);

        logonClient();

        assertExpectedCodLogonReceived(initiatorCodSettings.getCancelOnDisconnectTypeFieldCodes().get(initiatorCodSettings.getCancelOnDisconnectType()),
                initiatorCodSettings.getCodTimeoutWindow().toMillis());

        turnDownEveryFurtherLogon();
        ((FixSessionImpl) fixInitiatorSession).disconnect(); // hard disconnect will not generate a clean logout message

        await().untilAsserted(() -> {
            verify(fixAcceptorApplication, atLeastOnce()).onLogout(any(), Mockito.anyString(), any());
            verify(fixAcceptorApplication, atLeastOnce()).onDisconnected(any());
        });
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(initiatorCodSettings.getCodTimeoutWindow().toMillis()) * 2);
        verify(fixAcceptorApplication, never()).onCancelOnDisconnectTriggered(any(), eq(CancelOnDisconnectType.CANCEL_ON_LOGOUT_ONLY));
    }

    @Test
    void testLogonWithDoCancelOnDisconnectOnly() {
        setupInitiatorAndAcceptor(CancelOnDisconnectType.CANCEL_ON_DISCONNECT_ONLY);

        logonClient();

        assertExpectedCodLogonReceived(initiatorCodSettings.getCancelOnDisconnectTypeFieldCodes().get(initiatorCodSettings.getCancelOnDisconnectType()),
                initiatorCodSettings.getCodTimeoutWindow().toMillis());

        turnDownEveryFurtherLogon();
        ((FixSessionImpl) fixInitiatorSession).disconnect(); // hard disconnect will not generate a clean logout message

        await().untilAsserted(() -> {
            verify(fixAcceptorApplication, atLeastOnce()).onLogout(any(), Mockito.anyString(), any());
            verify(fixAcceptorApplication, atLeastOnce()).onDisconnected(any());
        });
        await().untilAsserted(() -> verify(fixAcceptorApplication)
                .onCancelOnDisconnectTriggered(any(), eq(CancelOnDisconnectType.CANCEL_ON_DISCONNECT_ONLY)));
    }

    @Test
    void testLogonWithAcceptorOnlyDoCancelOnLogoutOnly() {
        initiatorCodSettings = initiatorCodSettings.toBuilder()
                .enabled(false)
                .build();

        setupInitiatorAndAcceptor(CancelOnDisconnectType.CANCEL_ON_DISCONNECT_OR_LOGOUT);

        logonClient();

        assertExpectedCodLogonReceived(' ', -1);

        fixInitiator.stop();

        await().untilAsserted(() -> {
            verify(fixAcceptorApplication).onLogout(any(), Mockito.anyString(), any());
            verify(fixAcceptorApplication).onDisconnected(any());
        });
        await().untilAsserted(() -> verify(fixAcceptorApplication)
                .onCancelOnDisconnectTriggered(any(), eq(CancelOnDisconnectType.CANCEL_ON_DISCONNECT_OR_LOGOUT)));
    }

    /**
     * A logout this side asked for, that the counterparty never acknowledged: it dropped the connection instead of
     * answering. The session still ended the way this side asked, so it is a logout - and under
     * {@code CANCEL_ON_LOGOUT_ONLY} the orders go.
     *
     * <p>Reported as a remote disconnection, as it used to be, this fires nothing at all and a client that asked to
     * be logged out keeps live orders behind it.
     */
    @Test
    void aLogoutThisSideSentThatWasNeverAcknowledgedTriggersCodOnLogoutOnly() throws Exception {
        setupInitiatorAndAcceptor(CancelOnDisconnectType.CANCEL_ON_LOGOUT_ONLY);

        silentPeerLogsOnThenDropsTheLogout();

        await().untilAsserted(() -> verify(fixAcceptorApplication)
                .onCancelOnDisconnectTriggered(any(), eq(CancelOnDisconnectType.CANCEL_ON_LOGOUT_ONLY)));
    }

    /**
     * The mirror of it, and the one that costs a client money: {@code CANCEL_ON_DISCONNECT_ONLY} exists to leave
     * orders alone when a session logs out on purpose, so an unacknowledged logout must not cancel them.
     */
    @Test
    void aLogoutThisSideSentThatWasNeverAcknowledgedDoesNotTriggerCodOnDisconnectOnly() throws Exception {
        setupInitiatorAndAcceptor(CancelOnDisconnectType.CANCEL_ON_DISCONNECT_ONLY);

        silentPeerLogsOnThenDropsTheLogout();

        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(acceptorCodSettings.getCodTimeoutWindow().toMillis()) * 4);
        verify(fixAcceptorApplication, never())
                .onCancelOnDisconnectTriggered(any(), eq(CancelOnDisconnectType.CANCEL_ON_DISCONNECT_ONLY));
    }

    /**
     * Logs a raw peer on, has the acceptor session log out, and closes the socket on the peer's side without ever
     * answering the Logout - which is what a counterparty that goes away mid-logout looks like from here.
     *
     * <p>Returns once the acceptor has reported the logout, asserting on the way that it reports the message it sent
     * rather than the "Remote disconnection" it used to.
     */
    private void silentPeerLogsOnThenDropsTheLogout() throws Exception {
        startFixAcceptor();

        try (RawFixSocketClient.Session peer = RawFixSocketClient.connect(acceptorPort, FixRegularVersion.VERSION_44,
                "SENDER44_TEST", "TARGET44_TEST", Duration.ofSeconds(5))) {
            peer.send(peer.message(CoreMessageType.LOGON, 1)
                    .set(CoreFields.HEARTBEAT_INTERVAL, "10")
                    .set(CoreFields.ENCRYPT_METHOD, "0")
                    .set(acceptorCodSettings.getCancelOnDisconnectTypeFieldCode(),
                            String.valueOf(acceptorCodSettings.getCancelOnDisconnectTypeFieldCodes()
                                    .get(acceptorCodSettings.getCancelOnDisconnectType())))
                    .set(acceptorCodSettings.getCodTimeoutWindowFieldCode(),
                            String.valueOf(acceptorCodSettings.getCodTimeoutWindow().toMillis())));

            assertThat(peer.readMessage(Duration.ofSeconds(5))).contains(msgTypeField(CoreMessageType.LOGON));
            await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));

            fixAcceptorSession.logout(LOGOUT_MESSAGE);

            // read it so the peer provably saw it, then go away without answering
            assertThat(peer.readMessage(Duration.ofSeconds(5)))
                    .contains(msgTypeField(CoreMessageType.LOGOUT))
                    .contains(LOGOUT_MESSAGE);
        }

        // the logout this side asked for, reported as such: not "Remote disconnection"
        await().untilAsserted(() -> verify(fixAcceptorApplication)
                .onLogout(any(FixSession.class), Mockito.eq(LOGOUT_MESSAGE), Mockito.isNull()));
    }

    /**
     * A hard disconnect leaves the initiator wanting to be logged in, so it dials again after its 100ms connection
     * retry and logs on - and a logon inside the COD window cancels the COD task, which is what
     * {@link #testLogonWithinTimeframeDoesNotTriggerCODTask()} is about. The window here is 100ms too, so whether a
     * hard disconnect fired the COD came down to which of the two timers won. Turning every later logon down keeps
     * the counterparty away without depending on timing: a rejected logon never reaches the session's logon
     * processing, so it cannot cancel anything, and the disconnect is left as the one thing the acceptor judges.
     *
     * <p>The initiator keeps dialling and being turned down, so the acceptor's logout and disconnection callbacks
     * can fire more than once from here on.
     */
    private void turnDownEveryFurtherLogon() {
        doReturn(CompletableFuture.completedFuture(Optional.of("not letting the initiator back in")))
                .when(fixAcceptorApplication).validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class));
    }

    private void setupInitiatorAndAcceptor(CancelOnDisconnectType codType) {
        initiatorCodSettings = initiatorCodSettings.toBuilder().cancelOnDisconnectType(codType).build();

        setupInitiatorSessionSettings(s -> s.cancelOnDisconnectSettings(initiatorCodSettings).build());

        acceptorCodSettings = acceptorCodSettings.toBuilder().cancelOnDisconnectType(codType).build();

        setupAcceptorSessionSettings(s -> s.cancelOnDisconnectSettings(acceptorCodSettings).build());
    }

    private void assertExpectedCodLogonReceived(Character providedCodValue, long providedCodTimeoutWindow) {
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), Mockito.argThat(decodedFixMessage -> {
            char cod = getCodValue(decodedFixMessage, initiatorCodSettings.getCancelOnDisconnectTypeFieldCode());
            int codTimeoutWindow = getCodWindowValue(decodedFixMessage, initiatorCodSettings.getCodTimeoutWindowFieldCode());
            return cod == providedCodValue && codTimeoutWindow == providedCodTimeoutWindow;
        })));
    }
}