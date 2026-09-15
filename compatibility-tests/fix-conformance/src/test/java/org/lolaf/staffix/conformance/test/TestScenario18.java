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
package org.lolaf.staffix.conformance.test;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.codec.SessionRejectReasonCodes;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.fix44.fields.*;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Scenario 18 - Support third party addressing (applicable to all FIX systems). Optional.
 * <p>
 * Section 6.2 of the FIX Session Layer specification lets one session carry traffic for several firms:
 * OnBehalfOfCompID(115) names the firm a message originates from and DeliverToCompID(128) the firm it is ultimately
 * for, SenderCompID(49) and TargetCompID(56) naming the two ends of the session itself. The scenario's "testing
 * profile" is the set of values a session is allowed to see in those two fields, configured here through
 * {@code ValidationSettings.expectedOnBehalfOfCompIds} / {@code expectedDeliverToCompIds}, both unset by default.
 * <p>
 * A wrong 115 or 128 shares the CompID problem reject reason with a wrong 49 or 56 but is not the same failure: the
 * peer is who it claims to be, it has merely addressed one message wrongly. So the message is rejected and the session
 * stays up, where scenario 2 case K expects a Logout and a disconnect. That distinction is what case B below pins.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario18 extends AbstractScenario {

    private static final String EXPECTED_ON_BEHALF_OF = "FIRM_A";
    private static final String EXPECTED_DELIVER_TO = "FIRM_C";

    private final CapturingNewOrderSingleDecoder newOrderSingleDecoder = new CapturingNewOrderSingleDecoder();

    @Override
    FixSessionSettings.FixSessionSettingsBuilder<?, ?> getAcceptorFixSessionSettings() {
        // the testing profile: the only third party CompIDs this session accepts
        return super.getAcceptorFixSessionSettings()
                .validationSettings(FixSessionSettings.ValidationSettings.builder()
                        .expectedOnBehalfOfCompIds(Set.of(EXPECTED_ON_BEHALF_OF))
                        .expectedDeliverToCompIds(Set.of(EXPECTED_DELIVER_TO))
                        .build());
    }

    @Override
    List<FixMessageDecoder> acceptorApplicationDecoders() {
        // third party routing is about application messages, so acceptance is observed on one of those
        return List.of(newOrderSingleDecoder);
    }

    private void sendRoutedOrder(RawFixSocketClient.Session session, long seqNum, String clOrdId,
                                 String onBehalfOfCompId, String deliverToCompId) throws Exception {
        session.send(session.message(MessageTypes.NewOrderSingle, seqNum)
                .set(ClOrdID.get(), clOrdId)
                .set(OnBehalfOfCompID.get(), onBehalfOfCompId)
                .set(DeliverToCompID.get(), deliverToCompId));
    }

    /**
     * Checks the Reject the acceptor answers a wrongly addressed message with, and that the session survives it.
     */
    private void assertRejectedForCompId(RawFixSocketClient.Session session, String expectedRefSeqNum,
                                         int expectedRefTagId, long expectedNextNumIn) throws Exception {
        assertThatFixMessage(session.readMessageOfType(MessageTypes.Reject, DEFAULT_TIMEOUT))
                .hasMsgType(MessageTypes.Reject)
                .containsFieldWithValue(RefSeqNum.get(), expectedRefSeqNum)
                .containsFieldWithValue(RefTagID.get(), String.valueOf(expectedRefTagId))
                .containsFieldWithValue(SessionRejectReason.get(),
                        String.valueOf(SessionRejectReasonCodes.COMPID_PROBLEM.getCode()));

        // 2. the rejected message still consumed its sequence number
        await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(expectedNextNumIn));

        // unlike a wrong SenderCompID or TargetCompID, a routing error does not end the session. Asserted on
        // onLogoutInitiated rather than on the socket: a logout sends its Logout and then waits out
        // logInOrOutResponseTimeout before disconnecting, so the connection and the session both still look healthy
        // for ten seconds after the engine has decided to drop them.
        verify(fixAcceptorApplication, never()).onLogoutInitiated(any(FixSession.class), any());
        assertThat(session.isClosedByPeer(Duration.ofMillis(300))).isFalse();
        assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
    }

    @Test
    void A_receive_messages_with_expected_onbehalfof_and_deliverto_compids() throws Exception {
        // Condition/Stimulus: Receive messages with OnBehalfOfCompID(115) and DeliverToCompID(128) values expected as
        //   specified in testing profile and with correct usage.
        // Expected Behavior: Accept messages.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            sendRoutedOrder(session, 2, "Scenario18-A", EXPECTED_ON_BEHALF_OF, EXPECTED_DELIVER_TO);

            await().untilAsserted(() -> assertThat(newOrderSingleDecoder.decoded).containsExactly("Scenario18-A"));
            await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(3));

            // nothing came back, an accepted application message drawing no session level answer
            assertThat(session.readMessage(Duration.ofMillis(300))).isEmpty();
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }

    @Test
    void B_receive_messages_with_unexpected_or_incorrect_onbehalfof_or_deliverto_compids() throws Exception {
        // Condition/Stimulus: Receive messages with OnBehalfOfCompID(115) or DeliverToCompID(128) values not specified
        //   in testing profile or incorrect usage.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with RefTagID(371) to identify field with incorrect values, and
        //      SessionRejectReason(373) set to 9 (CompID problem).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {

            // 1. an OnBehalfOfCompID outside the testing profile: rejected naming tag 115
            sendRoutedOrder(session, 2, "Scenario18-B-onbehalfof", "FIRM_UNKNOWN", EXPECTED_DELIVER_TO);
            assertRejectedForCompId(session, "2", 115, 3);

            // the same for a DeliverToCompID outside it, naming tag 128
            sendRoutedOrder(session, 3, "Scenario18-B-deliverto", EXPECTED_ON_BEHALF_OF, "FIRM_UNKNOWN");
            assertRejectedForCompId(session, "3", 128, 4);

            // 3. the error condition reached the application, once per message and naming the offending field
            await().untilAsserted(() -> assertThat(newOrderSingleDecoder.rolledBack).hasSize(2));
            assertThat(newOrderSingleDecoder.rolledBack.get(0)).contains("ON_BEHALF_OF_COMP_ID", "FIRM_UNKNOWN");
            assertThat(newOrderSingleDecoder.rolledBack.get(1)).contains("DELIVER_TO_COMP_ID", "FIRM_UNKNOWN");

            // and neither message was processed
            assertThat(newOrderSingleDecoder.decoded).isEmpty();

            // and the session is still usable, which is what separates this from a wrong SenderCompID or TargetCompID
            session.send(session.message(MessageTypes.TestRequest, 4).set(TestReqID.get(), "Scenario18-live"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario18-live", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);
            await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(5));
        }
    }

    /**
     * Records the ClOrdID(11) of every NewOrderSingle(35=D) that made it through decoding, which is how the test sees
     * that a message was accepted, and the reason of every one that did not, which is the application's own notice of
     * the error condition the scenario asks to be generated.
     */
    private static final class CapturingNewOrderSingleDecoder implements FixMessageDecoder {

        private final List<String> decoded = new CopyOnWriteArrayList<>();
        private final List<String> rolledBack = new CopyOnWriteArrayList<>();
        private String clOrdId;

        @Override
        public MessageType getMessageType() {
            return MessageTypes.NewOrderSingle;
        }

        @Override
        public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
            fixFieldsDecoderMapper.mapStringField(ClOrdID.get(), value -> this.clOrdId = value, null);
        }

        @Override
        public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
            decoded.add(clOrdId);
        }

        @Override
        public void onDecodingFailed(FixSession fixSession, org.lolaf.staffix.api.codec.DecodingException decodingException) {
            rolledBack.add(String.valueOf(decodingException.getMessage()));
        }
    }
}
