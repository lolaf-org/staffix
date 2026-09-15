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
import org.lolaf.staffix.fix44.fields.Side;
import org.lolaf.staffix.fix44.fields.Symbol;
import org.lolaf.staffix.fix44.fields.TestReqID;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Scenario 15 - Send application or session layer messages to test normal and abnormal behavior/response (applicable to
 * all FIX systems). Optional.
 * <p>
 * The acceptor is the system under test. Every message is built byte by byte rather than through
 * {@code Session.message()}, whose LinkedHashMap always emits the standard header in canonical order: controlling the
 * field order is the whole point of this scenario.
 * <p>
 * The exclusion the scenario makes - "exclude those which have restrictions regarding order" - covers BeginString(8),
 * BodyLength(9) and MsgType(35), which the session layer pins to the first three positions (section 4.5.2, garbled
 * message processing), and the fields of a repeating group, whose order is fixed by the dictionary. Everything after
 * MsgType(35) is fair game, and that is what is permuted here.
 * <p>
 * Note this scenario asserts acceptance with the default validation settings, where
 * {@code ValidationSettings.validateFieldsOutOfOrder} is disabled. Scenario 14 case G covers the opposite contract,
 * turning that setting on and expecting a Reject.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario15 extends AbstractScenario {

    private final CapturingNewOrderSingleDecoder newOrderSingleDecoder = new CapturingNewOrderSingleDecoder();

    /**
     * Builds a message whose fields, everything after MsgType(35), are exactly the ones given in exactly the order
     * given. BeginString(8) and BodyLength(9) are prepended and CheckSum(10) appended by
     * {@link RawFixSocketClient#fixMessage}, those three being the ones whose position is not ours to choose.
     */
    private static byte[] rawMessage(String msgType, String... fieldsInOrder) {
        String[] fields = new String[fieldsInOrder.length + 1];
        fields[0] = "35=" + msgType;
        System.arraycopy(fieldsInOrder, 0, fields, 1, fieldsInOrder.length);
        return RawFixSocketClient.fixMessage("FIX.4.4", fields);
    }

    private static String sendingTime() {
        return "52=" + RawFixSocketClient.utcTimestamp(Instant.now());
    }

    @Override
    List<FixMessageDecoder> acceptorApplicationDecoders() {
        // an application message is what gives this scenario several body fields to permute, a session message having
        // barely any
        return List.of(newOrderSingleDecoder);
    }

    @Test
    void send_multiple_messages_of_same_type_with_fields_ordered_differently() throws Exception {
        // Condition/Stimulus: Send more than one message of the same type with header and body fields ordered
        //   differently to verify acceptance. (Exclude those which have restrictions regarding order.)
        // Expected Behavior: Message accepted and subsequent messages' MsgSeqNum(34) are accepted.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {

            // three TestRequests of the same type, each laying its standard header out differently and moving the one
            // body field it has, TestReqID(112), around it. Each is accepted if it comes back answered.
            session.send(rawMessage("1", "34=2", "49=SENDER44_TEST", "56=TARGET44_TEST", sendingTime(),
                    "112=Scenario15-canonical"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario15-canonical", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);

            // header fields in a different order among themselves
            session.send(rawMessage("1", "34=3", sendingTime(), "56=TARGET44_TEST", "49=SENDER44_TEST",
                    "112=Scenario15-header-shuffled"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario15-header-shuffled", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);

            // and the body field ahead of the header ones
            session.send(rawMessage("1", "34=4", "112=Scenario15-body-first", "49=SENDER44_TEST", "56=TARGET44_TEST",
                    sendingTime()));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario15-body-first", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);

            // the three were accepted in sequence, so the acceptor now expects 5
            await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(5));

            // same again on an application message, which has several body fields to permute: same message type, same
            // fields, opposite order
            session.send(rawMessage("D", "34=5", "49=SENDER44_TEST", "56=TARGET44_TEST", sendingTime(),
                    "11=Scenario15-order-1", "55=IBM", "54=1", "40=1", "60=" + RawFixSocketClient.utcTimestamp(Instant.now())));
            await().untilAsserted(() -> assertThat(newOrderSingleDecoder.decoded).containsExactly("Scenario15-order-1"));

            session.send(rawMessage("D", "34=6", "60=" + RawFixSocketClient.utcTimestamp(Instant.now()), "40=1",
                    "54=1", "55=IBM", "11=Scenario15-order-2", sendingTime(), "56=TARGET44_TEST", "49=SENDER44_TEST"));
            await().untilAsserted(() -> assertThat(newOrderSingleDecoder.decoded)
                    .containsExactly("Scenario15-order-1", "Scenario15-order-2"));

            // every one of them consumed its MsgSeqNum and none was rejected
            await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(7));
            verify(fixAcceptorApplication, never())
                    .onMessageReject(any(FixSession.class), any(), anyInt(), anyLong(), anyInt(), any());
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }

    /**
     * Records the ClOrdID(11) of every NewOrderSingle(35=D) that made it through decoding, which is how the test sees
     * that a message was accepted. Symbol(55) and Side(54) are mapped so that the permutations below move fields the
     * decoder actually binds, rather than ones it would ignore.
     */
    private static final class CapturingNewOrderSingleDecoder implements FixMessageDecoder {

        private final List<String> decoded = new CopyOnWriteArrayList<>();
        private String clOrdId;

        @Override
        public MessageType getMessageType() {
            return MessageTypes.NewOrderSingle;
        }

        @Override
        public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
            fixFieldsDecoderMapper
                    .mapStringField(ClOrdID.get(), value -> this.clOrdId = value, null)
                    .mapStringField(Symbol.get(), value -> {
                    }, null)
                    .mapCharValuesEnumField(Side.get(), (Side.SideValues value) -> {
                    }, null);
        }

        @Override
        public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
            decoded.add(clOrdId);
        }
    }
}
