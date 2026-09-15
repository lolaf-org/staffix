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
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.fix44.fields.ClOrdID;
import org.lolaf.staffix.fix44.fields.PossResend;
import org.lolaf.staffix.fix44.fields.TestReqID;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Scenario 19 - Test PossResend handling (applicable to all FIX systems). Mandatory.
 * <p>
 * Section 4.9 of the FIX Session Layer specification splits the responsibilities sharply: the session layer neither
 * initiates an application resend nor detects duplicate application messages, and a message carrying PossResend(97) is
 * processed as a brand new message consuming its own MsgSeqNum(34). Its single obligation is to <em>pass PossResend to
 * the application layer</em>, which the engine does through
 * {@link FixMessageDecoder#onDecoded(FixSession, boolean, boolean)}.
 * <p>
 * Both the deduplication and the warning the scenario asks for therefore live in the application, i.e. in this test's
 * own {@link DedupingNewOrderSingleDecoder} — the engine's user, not the engine. What is asserted of the engine is that
 * it delivers the flag and that it keeps treating the message as an ordinary one.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario19 extends AbstractScenario {

    private final DedupingNewOrderSingleDecoder newOrderSingleDecoder = new DedupingNewOrderSingleDecoder();

    @Override
    List<FixMessageDecoder> acceptorApplicationDecoders() {
        // PossResend is about application messages, and the deduplication needs a message-specific id to key on, which
        // NewOrderSingle(35=D) has in ClOrdID(11)
        return List.of(newOrderSingleDecoder);
    }

    private void sendOrder(RawFixSocketClient.Session session, long seqNum, String clOrdId, boolean possResend) throws Exception {
        RawFixSocketClient.Session.Message order = session.message(MessageTypes.NewOrderSingle, seqNum).set(ClOrdID.get(), clOrdId);
        if (possResend) {
            // the builder appends this after SendingTime(52) rather than in dictionary header order, which is fine
            // here: nothing in this scenario needs a given field order and out of order validation is off by default
            order.set(PossResend.get(), "Y");
        }
        session.send(order);
    }

    private void assertSessionProcessedItNormally(RawFixSocketClient.Session session, long expectedNextNumIn) throws Exception {
        // the message consumed its sequence number: the session layer treated it as new and did not deduplicate
        await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(expectedNextNumIn));
        // PossResend is not a session level error, so nothing was rejected and the connection was left alone
        verify(fixAcceptorApplication, never())
                .onMessageReject(any(FixSession.class), any(), anyInt(), anyLong(), anyInt(), any());
        assertThat(session.isClosedByPeer(Duration.ofMillis(500))).isFalse();
        assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
    }

    @Test
    void A_receive_message_with_possresend_y_already_seen() throws Exception {
        // Condition/Stimulus: Receive message with PossResend(97)=Y and application layer check of message-specific ID
        //   indicates that it has already been seen on this session.
        // Expected Behavior:
        //   1. Ignore the message.
        //   2. Generate a warning condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            await().untilAsserted(() -> assertThat(fixAcceptorSession).isNotNull());

            // the order is seen once, normally, which is what makes the retransmission below a duplicate
            sendOrder(session, 2, "Scenario19A", false);
            await().untilAsserted(() -> assertThat(newOrderSingleDecoder.processed).containsExactly("Scenario19A"));
            await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(3));

            // the peer's application retransmits it: same ClOrdID, PossResend(97)=Y, and a new MsgSeqNum(34) as the
            // specification requires of an application layer resend
            sendOrder(session, 3, "Scenario19A", true);

            // 1. the application recognises the id and ignores the message
            await().untilAsserted(() -> assertThat(newOrderSingleDecoder.ignored).containsExactly("Scenario19A"));
            assertThat(newOrderSingleDecoder.processed).containsExactly("Scenario19A");

            // 2. and raises its warning
            await().untilAsserted(() -> assertThat(acceptorLogger.getEvents())
                    .anyMatch(event -> event.contains("PossResend") && event.contains("Scenario19A")));

            // ignored by the application, yet fully processed by the session
            assertSessionProcessedItNormally(session, 4);

            session.send(session.message(MessageTypes.TestRequest, 4).set(TestReqID.get(), "Scenario19A-live"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario19A-live", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);
        }
    }

    @Test
    void B_receive_message_with_possresend_y_not_yet_seen() throws Exception {
        // Condition/Stimulus: Receive message with PossResend(97)=Y and application layer check of message-specific ID
        //   indicates that it has NOT yet been seen on this session.
        // Expected Behavior: Accept and process the message normally.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            await().untilAsserted(() -> assertThat(fixAcceptorSession).isNotNull());

            // PossResend(97)=Y on an id the session has never seen: the flag alone makes nothing a duplicate
            sendOrder(session, 2, "Scenario19B", true);

            await().untilAsserted(() -> assertThat(newOrderSingleDecoder.processed).containsExactly("Scenario19B"));
            assertThat(newOrderSingleDecoder.ignored).isEmpty();
            // nothing to warn about, which also rules out a decoder warning on the flag alone and passing A by luck
            assertThat(acceptorLogger.getEvents()).noneMatch(event -> event.contains("PossResend"));

            assertSessionProcessedItNormally(session, 3);

            session.send(session.message(MessageTypes.TestRequest, 3).set(TestReqID.get(), "Scenario19B-live"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario19B-live", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);
        }
    }

    /**
     * Stands in for an application layer that deduplicates on a message-specific id: it keys on ClOrdID(11) and, when
     * the engine tells it the message came in with PossResend(97)=Y, ignores an id it has already processed on this
     * session. It deliberately does not map tag 97 itself — the point of the scenario is that the engine hands the flag
     * over.
     */
    private static final class DedupingNewOrderSingleDecoder implements FixMessageDecoder {

        private final List<String> processed = new CopyOnWriteArrayList<>();
        private final List<String> ignored = new CopyOnWriteArrayList<>();
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
            if (possResend && processed.contains(clOrdId)) {
                ignored.add(clOrdId);
                fixSession.logEvent("PossResend duplicate ignored for ClOrdID " + clOrdId);
                return;
            }
            processed.add(clOrdId);
        }
    }
}
