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

import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.*;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.fix44.encoders.MarketDataRequestRejectEncoder;
import org.lolaf.staffix.fix44.fields.Subject;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TestFixMessagesSending extends AbstractFixTests {

    /**
     * A tag inside the user defined range, see {@link FixField#isUserDefined(int)}. Named rather than repeated so
     * that the two ends of the exchange below - the encoder adding it and the decoder mapping it - cannot drift.
     */
    private static final int USER_DEFINED_TAG = 5001;

    @Override
    void setupMessageDecoders(ConnectorType connectorType) {
        super.setupMessageDecoders(connectorType);
        Map<MessageType, FixMessageDecoder> targetMap = connectorType.select(initiatorMessageDecoders, acceptorMessageDecoders);
        FixMessageDecoder mdr = spy(new TestMarketDataRequestRejectDecoder());
        targetMap.put(mdr.getMessageType(), mdr);

        when(getFixApplication(connectorType).setup(any(), any(), any()))
                .thenReturn(List.of(mdr, getDecoder(testMessageType, connectorType)));
    }

    @Test
    void testSendMessageWithUserDefinedField() {
        setupInitiatorSessionSettings(s ->
                s.validationSettings(FixSessionSettings.ValidationSettings.builder()
                        .allowUserDefinedFields(true)
                        .build()).build());

        logonClient();

        MarketDataRequestRejectEncoder e = fixAcceptorSession.newEncoder(MarketDataRequestRejectEncoder.class).begin()
                .addInt(FixField.of(USER_DEFINED_TAG, FieldType.INT, FieldLocation.TRAILER), 1234);

        fixAcceptorSession.send(e, null);

        TestMarketDataRequestRejectDecoder decoder = (TestMarketDataRequestRejectDecoder) getDecoder(MessageTypes.MarketDataRequestReject, ConnectorType.INITIATOR);

        await().untilAsserted(() -> verify(decoder).onDecoded(any(), eq(false), eq(false)));
        assertThat(decoder.userDefinedFieldReceived).isEqualTo(1234);
        verify(fixInitiatorApplication, never()).onMessageReject(any(), any(), anyInt(), anyLong(), anyInt(), any());

    }

    /**
     * A message sent on a session that has never been connected is not lost: it consumes an outgoing sequence number
     * and is persisted, so once the counterparty finally logs on it notices the gap, asks for a resend, and receives
     * the message flagged {@code PossDupFlag(43)=Y}.
     * <p>
     * The sending side is started on its own, with nothing to connect to, so that {@code send} takes the
     * {@code NO_CONNECTED_SESSION} path rather than writing to a socket - {@code ioSession} is assigned on TCP
     * connection, not on logon, so merely suppressing the Logon would not be enough. The session is reached through
     * {@link FixSessionRegistry} of its engine, which it enters when its control starts managing it, well before any
     * connection.
     */
    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testSendOffLineSessionMessage(ConnectorType connectorType) {
        // LOGGED_OUT so that the initiator does not log on by itself the moment the connection comes up: this test
        // drives the logon, after the offline message has been sent.
        setupInitiatorSessionSettings(s -> s.desiredSessionState(FixSessionState.LOGGED_OUT).build());

        // Start only the sending side, so its session exists and is registered but has never been connected.
        Startable<?> sender = getConnector(connectorType);
        sender.start();
        trapCreatedFixSession(connectorType);

        FixSession createdSession = getFixSession(connectorType);
        FixSessionId offLineSessionId = createdSession.getFixSessionId();
        FixSessionRegistry registry = connectorType.select(initiatorFixEngine, acceptorFixEngine).getFixSessionRegistry();
        FixSession offLineSession = registry.find(offLineSessionId)
                .orElseThrow(() -> new AssertionError("session " + offLineSessionId + " is not in the FixSessionRegistry"));
        assertThat(offLineSession).isSameAs(createdSession);
        assertThat(offLineSession.isConnected()).isFalse();

        // The sending side is the one asked to resend, once the counterparty spots the gap.
        when(getFixApplication(connectorType).onResendRequest(any(FixSession.class), any(MessageType.class), any(DecodedFixMessage.class)))
                .thenReturn(true);

        EmailEncoder encoder = offLineSession.newEncoder(EmailEncoder.class);
        encoder.begin().setSubject("offline-message");
        offLineSession.send(encoder, null);

        // Now bring the counterparty up and let the session log on: the gap triggers the resend.
        logonClient();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        assertPossDupMessageReceived(messagesReceiverList, Subject.get(), "offline-message");
    }

    /**
     * A broadcast asking for every configured session, not just the connected ones, reaches sessions that have never
     * been connected: each stores the message under its own outgoing sequence number, and each counterparty gets it
     * back as a resend once it logs on.
     * <p>
     * Only an acceptor can do this, having more than one session to broadcast to, so the test gives it two - the
     * second added to its settings store after start up - and stands up an initiator for each.
     */
    @Test
    void testSendOffLineBroadcastedMessage() {
        FixSessionId secondAcceptorSessionId = FixSessionId.of("second", FixRegularVersion.VERSION_44,
                "TARGET44_TEST_2", "SENDER44_TEST_2");
        FixSessionId secondInitiatorSessionId = FixSessionId.of("second", FixRegularVersion.VERSION_44,
                "SENDER44_TEST_2", "TARGET44_TEST_2");

        startFixAcceptor();
        acceptorFixEngine.getFixSessionsSettingsStores().get(0)
                .add(getAcceptorFixSessionSettings().fixSessionId(secondAcceptorSessionId).build());

        // two sessions, neither ever connected: this is what makes the broadcast an offline one
        assertThat(fixAcceptor.getSessions()).hasSize(2).allMatch(s -> !s.isConnected());

        // the acceptor is the one asked to resend, once each counterparty spots the gap
        when(fixAcceptorApplication.onResendRequest(any(FixSession.class), any(MessageType.class), any(DecodedFixMessage.class)))
                .thenReturn(true);

        EmailEncoder encoder = fixAcceptorSession.newEncoder(EmailEncoder.class);
        encoder.begin().setSubject("offline-broadcast");
        // connectedSessionsOnly = false, so the configured-but-unconnected sessions are included
        fixAcceptor.broadcast(encoder, null, null, false);

        // The first pair first: logonClient traps the session through the initiator application mock and wants exactly
        // one onSessionCreated on it, so the second initiator - which shares that mock - may only come up afterwards.
        logonClient();

        // the second initiator needs an engine of its own: one engine may not hold two sessions with the same id, and
        // its settings store carries the second session alone
        FixEngine secondInitiatorEngine = initiatorFixEngineBuilder.toBuilder()
                .clearFixSessionsSettingsStores()
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(getInitiatorFixSessionSettings().fixSessionId(secondInitiatorSessionId).build())
                        .build())
                .build().instance();
        try {
            secondInitiatorEngine.start();
            FixInitiator secondInitiator = secondInitiatorEngine.newInitiator(fixInitiatorBuilder.toBuilder()
                    .instanceId("second-initiator")
                    .fixSessionId(secondInitiatorSessionId)
                    .build());
            secondInitiator.start();
            await().untilAsserted(() -> assertThat(secondInitiator.getSession().isConnected()).isTrue());
            secondInitiator.getSession().logon();
            await().untilAsserted(() -> assertThat(secondInitiator.getSession().isLoggedIn()).isTrue());

            // both initiator sessions decode into the same list, so the broadcast landing on both is a count of two
            await().untilAsserted(() -> assertThat(getDecodedFixMessages(ConnectorType.INITIATOR).stream()
                    .filter(TestingDecodedFixMessage::isPossDuplicate)
                    .filter(m -> "offline-broadcast".equals(m.getString(Subject.get(), "")))
                    .count()).isEqualTo(2L));
        } finally {
            secondInitiatorEngine.stop(Deadline.unlimited());
        }
    }

    @Test
    void testSendBroadcastedMessage() {
        connectFixInitiatorAndAcceptor();
        logonClient();

        EmailEncoder encoder = getFixSession(ConnectorType.ACCEPTOR).newEncoder(EmailEncoder.class);
        encoder.begin().setSubject("test-subject-1");

        fixAcceptor.broadcast(encoder, null, null, true);

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(ConnectorType.INITIATOR);
        assertMessageReceived(messagesReceiverList, Subject.get(), "test-subject-1");

        encoder.begin().setSubject("test-subject-2");
        fixAcceptor.broadcast(encoder, null,
                s -> s.getFixSessionId().getFixVersion().equals(FixRegularVersion.VERSION_44), true);

        assertMessageReceived(messagesReceiverList, Subject.get(), "test-subject-1");

        encoder.begin().setSubject("test-subject-3");
        fixAcceptor.broadcast(encoder, null,
                s -> s.getFixSessionId().getFixVersion().equals(FixRegularVersion.VERSION_43), true);

        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));

        assertNoMessageReceived(messagesReceiverList, Subject.get(), "test-subject-3");
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testSendMessage(ConnectorType connectorType) {
        connectFixInitiatorAndAcceptor();
        logonClient();

        FixSession fixSession = getFixSession(connectorType);
        EmailEncoder encoder = fixSession.newEncoder(EmailEncoder.class);
        encoder.begin().setSubject("test-subject");
        fixSession.send(encoder, null);

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        assertMessageReceived(messagesReceiverList, Subject.get(), "test-subject");
    }

    /**
     * Sending on a session whose connection is down is not an error: the message is numbered and stored, and the
     * peer gets it on the next connection through the gap it leaves behind. It only works because the store belongs
     * to the session rather than to the connection - stopped between two connections, it would be being written to
     * while closed, and a store that releases native memory on stop (the file one releases its mapping) would take
     * the process down rather than fail.
     */
    @Test
    void aMessageSentWhileDisconnectedIsStoredAndOutlivesTheReconnection() {
        connectFixInitiatorAndAcceptor();
        logonClient();

        fixInitiatorSession.disconnect("sending while down");
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isFalse());

        long seqNumOfOfflineMessage = getFixMessagesStore(ConnectorType.INITIATOR).getOutgoingSeqNum();
        EmailEncoder encoder = fixInitiatorSession.newEncoder(EmailEncoder.class);
        encoder.begin().setSubject("sent-while-disconnected");
        fixInitiatorSession.send(encoder, null);

        await().untilAsserted(() -> assertThat(getFixMessagesStore(ConnectorType.INITIATOR).getOutgoingSeqNum())
                .as("the offline message was numbered and stored").isGreaterThan(seqNumOfOfflineMessage));
        assertThat(storedMessage(seqNumOfOfflineMessage)).contains("sent-while-disconnected");

        fixInitiatorSession.logon();
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());

        // and the store carried the session across the gap rather than being torn down with the connection: it is
        // the same one, still numbering messages, on the other side of the reconnection
        EmailEncoder afterReconnect = fixInitiatorSession.newEncoder(EmailEncoder.class);
        afterReconnect.begin().setSubject("sent-after-reconnect");
        fixInitiatorSession.send(afterReconnect, null);
        assertMessageReceived(decodedAcceptorMessages, Subject.get(), "sent-after-reconnect");
    }

    private String storedMessage(long seqNum) {
        StringBuilder found = new StringBuilder();
        getFixMessagesStore(ConnectorType.INITIATOR).find(seqNum, seqNum, (storedSeqNum, message) -> {
            byte[] bytes = new byte[message.remaining()];
            message.duplicate().get(bytes);
            found.append(new String(bytes, StandardCharsets.US_ASCII));
            return true;
        });
        return found.toString();
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testSendMessageFromIOThread(ConnectorType connectorType) {
        connectFixInitiatorAndAcceptor();
        logonClient();

        FixSession fixSession = getFixSession(connectorType);
        fixSession.processTask(() -> {
            EmailEncoder encoder = fixSession.newEncoder(EmailEncoder.class);
            encoder.begin().setSubject("test-subject");
            fixSession.send(encoder, null);

        });

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        assertMessageReceived(messagesReceiverList, Subject.get(), "test-subject");
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testSendMessageWithCallback(ConnectorType connectorType) {

        connectFixInitiatorAndAcceptor();
        logonClient();

        FixSession fixSession = getFixSession(connectorType);
        EmailEncoder encoder = fixSession.newEncoder(EmailEncoder.class);
        encoder.begin().setSubject("test-subject");

        FixSession.MessageSendOperationCallback callback = mock(FixSession.MessageSendOperationCallback.class);

        fixSession.send(encoder, null, callback, "param1", "param2");

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        assertMessageReceived(messagesReceiverList, Subject.get(), "test-subject");
        // awaited rather than verified outright: what has just been awaited is the message arriving at the RECEIVER,
        // while the callback fires on the SENDER's side, and the two are different threads either side of a socket
        await().untilAsserted(() -> verify(callback).onMessageCallback(null, "param1", "param2"));
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testBufferedSendMessageUnderRingBufferSize(ConnectorType connectorType) {
        connectFixInitiatorAndAcceptor();
        logonClient();
        FixSession fixSession = getFixSession(connectorType);

        for (int i = 1; i <= IOSettings.DEFAULT_TASKS_RING_BUFFER_SIZE / 2; i++) {
            EmailEncoder encoder = fixSession.newEncoder(EmailEncoder.class);
            encoder.begin().setSubject("test-subject-" + i);
            fixSession.bufferize(encoder, null);
        }
        fixSession.flush();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        assertMessageReceived(messagesReceiverList, Subject.get(), "test-subject-1");
        assertMessageReceived(messagesReceiverList, Subject.get(), "test-subject-" + (IOSettings.DEFAULT_TASKS_RING_BUFFER_SIZE / 2));
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testBufferedSendMessageOverRingBufferSize(ConnectorType connectorType) {
        connectFixInitiatorAndAcceptor();
        logonClient();
        FixSession fixSession = getFixSession(connectorType);

        for (int i = 1; i <= IOSettings.DEFAULT_TASKS_RING_BUFFER_SIZE * 2; i++) {
            EmailEncoder encoder = fixSession.newEncoder(EmailEncoder.class);
            encoder.begin().setSubject("test-subject-" + i);
            fixSession.bufferize(encoder, null);
        }
        fixSession.flush();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        assertMessageReceived(messagesReceiverList, Subject.get(), "test-subject-1");
        assertMessageReceived(messagesReceiverList, Subject.get(), "test-subject-" + (IOSettings.DEFAULT_TASKS_RING_BUFFER_SIZE * 2));
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testBufferedMessageWithCallback(ConnectorType connectorType) {
        connectFixInitiatorAndAcceptor();
        logonClient();
        FixSession fixSession = getFixSession(connectorType);

        FixSession.MessageSendOperationCallback callback1 = mock(FixSession.MessageSendOperationCallback.class);

        for (int i = 0; i < 32; i++) {
            EmailEncoder encoder = fixSession.newEncoder(EmailEncoder.class);
            encoder.begin().setSubject("test-subject-" + i);
            fixSession.bufferize(encoder, null, callback1, "param1", "param2");
        }
        FixSession.MessageSendOperationCallback callback2 = mock(FixSession.MessageSendOperationCallback.class);
        EmailEncoder encoder = fixSession.newEncoder(EmailEncoder.class);
        encoder.begin().setSubject("test-subject");
        fixSession.bufferize(encoder, null, callback2, "param11", "param22");
        fixSession.flush();

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        assertMessageReceived(messagesReceiverList, Subject.get(), "test-subject-0");
        assertMessageReceived(messagesReceiverList, Subject.get(), "test-subject-31");
        assertMessageReceived(messagesReceiverList, Subject.get(), "test-subject");

        verify(callback1, times(32)).onMessageCallback(null, "param1", "param2");
        verify(callback2).onMessageCallback(null, "param11", "param22");
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void testMultiThreadedWrites(ConnectorType connectorType) {
        connectFixInitiatorAndAcceptor();
        logonClient();
        FixSession fixSession = getFixSession(connectorType);

        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                for (int j = 0; j < 200; j++) {
                    fixSession.send(fixSession.newEncoder(EmailEncoder.class).begin().setSubject("test-subject"), null);
                }
            }).start();
        }

        List<TestingDecodedFixMessage> messagesReceiverList = getDecodedFixMessages(connectorType.inverse());
        await().untilAsserted(() -> assertThat(messagesReceiverList).size().isEqualTo(2000));
    }

    @Setter
    @Getter
    private static class TestMarketDataRequestRejectDecoder implements FixMessageDecoder {

        int userDefinedFieldReceived;

        @Override
        public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
            FixField userField = fieldsRegistry.addUserDefinedField(USER_DEFINED_TAG, FieldType.INT, FieldLocation.BODY);
            fixFieldsDecoderMapper.withFieldsAutoResetDisabled()
                    .mapIntField(userField, this::setUserDefinedFieldReceived, 0);
        }

        @Override
        public MessageType getMessageType() {
            return MessageTypes.MarketDataRequestReject;
        }

    }

}