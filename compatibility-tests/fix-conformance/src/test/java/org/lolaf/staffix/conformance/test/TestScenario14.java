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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.codec.BusinessRejectReasonCodes;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.codec.SessionRejectReasonCodes;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.fix44.fields.*;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.conformance.test.TestScenario17.UNSUPPORTED_CRYPTOGRAPHY;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;

/**
 * Scenario 14 - Receive application or session layer message (applicable to all FIX systems). Mandatory.
 * <p>
 * The acceptor is the system under test, and every case is driven over a raw socket because the malformed messages
 * these tests send are ones a real initiator session would never produce.
 * <p>
 * A to D and G to J are driven on a session message, E, I, J and M need an application one because the validation they
 * exercise has no session layer equivalent (no admin message carries an enumerated field or a repeating group), so this
 * scenario registers a {@link NewOrderSingleDecoder} through {@code acceptorApplicationDecoders()}.
 * <p>
 * K, L and N stay disabled, each saying why below: they are the ones that need engine behavior this version does not
 * have, rather than a different way of driving the test.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario14 extends AbstractScenario {

    /**
     * The MsgSeqNum(34) every malformed message of this scenario carries: the raw client logs on as 1, so 2 is the
     * next one the acceptor expects.
     */
    private static final String REJECTED_SEQ_NUM = "2";
    private final NewOrderSingleDecoder newOrderSingleDecoder = new NewOrderSingleDecoder();

    /**
     * Builds the raw bytes of a message carrying {@link #REJECTED_SEQ_NUM}, with the given body fields appended after
     * a well-formed standard header. Used by the cases that need to control the exact field order or to repeat a tag,
     * neither of which the {@code Session.message()} builder allows.
     */
    private static byte[] rawMessage(String msgType, String... bodyFields) {
        String[] header = {"35=" + msgType, "34=" + REJECTED_SEQ_NUM, "49=SENDER44_TEST", "56=TARGET44_TEST",
                "52=" + RawFixSocketClient.utcTimestamp(Instant.now())};
        String[] fields = new String[header.length + bodyFields.length];
        System.arraycopy(header, 0, fields, 0, header.length);
        System.arraycopy(bodyFields, 0, fields, header.length, bodyFields.length);
        return RawFixSocketClient.fixMessage("FIX.4.4", fields);
    }

    @Override
    FixSessionSettings.FixSessionSettingsBuilder<?, ?> getAcceptorFixSessionSettings() {
        // every case of this scenario is about validating an inbound message, so the validations that are off by
        // default for latency reasons have to be turned on here
        return super.getAcceptorFixSessionSettings()
                .validationSettings(FixSessionSettings.ValidationSettings.builder()
                        // B: a TestRequest(35=1) missing its TestReqID(112)
                        .validateRequiredFields(true)
                        // C: a tag that exists in the dictionary but not for the message type carrying it
                        .allowUndefinedTagsForMessage(false)
                        // G: header/body/trailer fields interleaved
                        .validateFieldsOutOfOrder(true)
                        // H: a non repeating group tag sent twice
                        .validateDuplicateTags(true)
                        .build());
    }

    /**
     * Asserts the behavior mandated for the session level cases of this scenario: the acceptor answers the message
     * carrying {@link #REJECTED_SEQ_NUM} with a Reject(35=3) pointing back at it through RefSeqNum(45) and carrying
     * the expected SessionRejectReason(373), it increments NextNumIn, and it keeps the session up.
     */
    private void assertRejectedWith(RawFixSocketClient.Session session, SessionRejectReasonCodes expectedReason) throws Exception {
        String reply = session.readMessageOfType(MessageTypes.Reject, DEFAULT_TIMEOUT);
        assertThatFixMessage(reply)
                .hasMsgType(MessageTypes.Reject)
                .containsFieldWithValue(RefSeqNum.get(), REJECTED_SEQ_NUM)
                .containsFieldWithValue(SessionRejectReason.get(), String.valueOf(expectedReason.getCode()));

        // the rejected message still consumed its sequence number, so the acceptor now expects 3
        await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(3));
        assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
    }

    @Override
    List<FixMessageDecoder> acceptorApplicationDecoders() {
        // the cases that cannot be expressed on a session message need an application one: NewOrderSingle(35=D) has an
        // enumerated field (Side), a repeating group (NoAllocs) and required body fields, which covers E, I, J, L and M
        return List.of(newOrderSingleDecoder);
    }

    @Test
    void A_receive_field_identifier_tag_not_defined_in_specification() throws Exception {
        // Condition/Stimulus: Receive field identifier (tag number) not defined in specification.
        //   Exception: undefined tag used is specified in testing profile as user-defined.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 0 (Invalid tag number).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // 1234 is in no FIX 4.4 dictionary, and below the 5000+ range reserved for user-defined fields, so it
            // cannot be excused as user-defined either
            session.send(session.message(MessageTypes.TestRequest, 2)
                    .set(TestReqID.get(), "Scenario14A")
                    .set(1234, "not-a-field"));

            assertRejectedWith(session, SessionRejectReasonCodes.INVALID_TAG_NUMBER);
        }
    }

    @Test
    void B_receive_message_with_required_field_missing() throws Exception {
        // Condition/Stimulus: Receive message with a required field identifier (tag number) missing.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 1 (Required tag missing).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // TestReqID(112) is required on a TestRequest(35=1)
            session.send(session.message(MessageTypes.TestRequest, 2));

            assertRejectedWith(session, SessionRejectReasonCodes.REQUIRED_TAG_MISSING);
        }
    }

    @Test
    void C_receive_field_defined_in_spec_but_not_for_this_message_type() throws Exception {
        // Condition/Stimulus: Receive message with field identifier (tag number) which is defined in the specification
        //   but not defined for this message type.
        //   Exception: undefined tag used is specified in testing profile as user-defined for this message type.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 2 (Tag not defined for this message type).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // Symbol(55) is a perfectly valid FIX 4.4 field, just not one a TestRequest(35=1) may carry
            session.send(session.message(MessageTypes.TestRequest, 2)
                    .set(TestReqID.get(), "Scenario14C")
                    .set(Symbol.get(), "IBM"));

            assertRejectedWith(session, SessionRejectReasonCodes.TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE);
        }
    }

    @Test
    void D_receive_field_specified_but_no_value() throws Exception {
        // Condition/Stimulus: Receive message with field identifier (tag number) specified but no value
        //   (e.g. "55=<SOH>" vs. "55=IBM<SOH>").
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 4 (Tag specified without a value).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // "112=<SOH>": the tag is present, its value is not
            session.send(rawMessage("1", "112="));

            assertRejectedWith(session, SessionRejectReasonCodes.TAG_SPECIFIED_WITHOUT_A_VALUE);
        }
    }

    @Test
    void E_receive_incorrect_value_out_of_range_for_field() throws Exception {
        // Condition/Stimulus: Receive message with incorrect value (out of range or not part of valid list of
        //   enumerated values) for a particular field identifier (tag number).
        //   Exception: undefined enumeration values used are specified in testing profile as user-defined.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 5 (Value is incorrect (out of range) for
        //      this tag).
        //   2. Increment inbound MsgSeqNum(34).
        //   3. Generate an error condition in test output.
        // Driven with an application message: no session layer field is enum-validated, EncryptMethod(98)=99 on a Logon
        // is accepted as is.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // Side(54) is enumerated, "Z" is none of its values
            session.send(rawMessage("D", "11=Scenario14E", "55=IBM", "54=Z", "40=1", "60=" + RawFixSocketClient.utcTimestamp(Instant.now())));

            assertRejectedWith(session, SessionRejectReasonCodes.VALUE_IS_INCORRECT);
        }
    }

    @Test
    void F_receive_value_in_incorrect_data_format() throws Exception {
        // Condition/Stimulus: Receive message with a value in an incorrect data format (syntax) for a particular field
        //   identifier (tag number).
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 6 (Incorrect data format for value).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // BeginSeqNo(7) of a ResendRequest(35=2) is a sequence number, "not-a-number" cannot be parsed as one
            session.send(rawMessage("2", "7=not-a-number", "16=0"));

            assertRejectedWith(session, SessionRejectReasonCodes.INCORRECT_DATA_FORMAT_FOR_VALUE);
        }
    }

    @Test
    void G_receive_message_header_body_trailer_out_of_order() throws Exception {
        // Condition/Stimulus: Receive a message in which the following is not true: Standard Header fields appear before
        //   Body fields which appear before Standard Trailer fields.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 14 (Tag specified out of required order).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // the body field TestReqID(112) is sent before the header field SendingTime(52) rather than after it
            session.send(RawFixSocketClient.fixMessage("FIX.4.4",
                    "35=1", "34=" + REJECTED_SEQ_NUM, "49=SENDER44_TEST", "56=TARGET44_TEST",
                    "112=Scenario14G",
                    "52=" + RawFixSocketClient.utcTimestamp(Instant.now())));

            assertRejectedWith(session, SessionRejectReasonCodes.TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER);
        }
    }

    @Test
    void H_receive_non_repeating_group_tag_specified_more_than_once() throws Exception {
        // Condition/Stimulus: Receive a message in which a field identifier (tag number) which is not part of a
        //   repeating group is specified more than once.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 13 (Tag appears more than once).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // TestReqID(112) is not part of any repeating group, sending it twice is an error
            session.send(rawMessage("1", "112=Scenario14H", "112=Scenario14H-again"));

            assertRejectedWith(session, SessionRejectReasonCodes.TAG_APPEARS_MORE_THAN_ONCE);
        }
    }

    @Test
    void I_receive_repeating_group_count_field_incorrect() throws Exception {
        // Condition/Stimulus: Receive a message with repeating groups in which the "count" field value for a repeating
        //   group is incorrect.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 16 (Incorrect NumInGroup count for
        //      repeating group).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // NoAllocs(78) announces two entries, only one follows
            session.send(rawMessage("D", "11=Scenario14I", "55=IBM", "54=1", "40=1",
                    "60=" + RawFixSocketClient.utcTimestamp(Instant.now()), "78=2", "79=ACCOUNT-1"));

            assertRejectedWith(session, SessionRejectReasonCodes.INCORRECT_NUM_IN_GROUP_COUNT_FOR_REPEATING_GROUP);
        }
    }

    @Test
    void J_receive_repeating_group_fields_out_of_order() throws Exception {
        // Condition/Stimulus: Receive a message with repeating groups in which the order of repeating group fields does
        //   not match the specification.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 15 (Repeating group fields out of order).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // AllocAccount(79) must be the first field of a NoAllocs(78) entry, here AllocQty(80) opens it
            session.send(rawMessage("D", "11=Scenario14J", "55=IBM", "54=1", "40=1",
                    "60=" + RawFixSocketClient.utcTimestamp(Instant.now()), "78=1", "80=100", "79=ACCOUNT-1"));

            assertRejectedWith(session, SessionRejectReasonCodes.REPEATING_GROUP_FIELDS_OUT_OF_ORDER);
        }
    }

    @Test
    void K_receive_non_data_field_with_embedded_soh() throws Exception {
        // Condition/Stimulus: Receive a message with a field of a data type other than "data" which contains one or more
        //   embedded <SOH> values.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) (Non "data" value includes field delimiter (<SOH>
        //      character)).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
        //   OR
        //   1. Consider garbled and ignore message.
        //   2. Generate an error condition in test output.
        // staffix implements the second behavior, for the reason a wrong BodyLength(9) is garbled rather than rejected:
        // the framing of the message has just been proven untrustworthy, so neither its MsgSeqNum(34) nor anything else
        // it carries can be answered.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // TestReqID(112) is a String, not a "data" field: the embedded SOH leaves "tail" as a token whose tag is
            // not a number at all
            session.send(rawMessage("1", "112=Scenario14K\u0001tail"));

            // the message is disregarded: nothing is answered and the session stays up
            assertThat(session.isClosedByPeer(Duration.ofMillis(500))).isFalse();
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();

            // NextNumIn is left untouched, so the very same MsgSeqNum(34) is still the one expected next: a well formed
            // TestRequest re-using it is accepted and answered
            assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(2);

            session.send(session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario14K-next"));
            String reply = session.readMessageWithField(TestReqID.get(), "Scenario14K-next", DEFAULT_TIMEOUT);
            assertThatFixMessage(reply)
                    .hasMsgType(MessageTypes.Heartbeat)
                    .doesNotHaveMsgType(MessageTypes.Reject)
                    .doesNotHaveMsgType(MessageTypes.Logout);
        }
    }

    @Test
    void L_receive_message_when_application_not_available() throws Exception {
        // Condition/Stimulus: Receive a message when application layer processing or system is not available (optional).
        // Expected Behavior:
        //   1. Send BusinessMessageReject(35=j) with BusinessRejectReason(380) set to 4 (Application not available).
        //   2. Increment NextNumIn.
        //   3. Generate a warning condition in test output.
        // "Application layer processing not available" is simulated by the registered decoder throwing from one of its
        // field setters: any exception other than IllegalFieldValueException coming out of a decoder callback is what
        // the parser turns into this business reject.
        newOrderSingleDecoder.failApplicationProcessing = true;

        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            session.send(rawMessage("D", "11=Scenario14L", "55=IBM", "54=1", "40=1",
                    "60=" + RawFixSocketClient.utcTimestamp(Instant.now())));

            String reply = session.readMessageOfType(MessageTypes.BusinessMessageReject, DEFAULT_TIMEOUT);
            assertThatFixMessage(reply)
                    .hasMsgType(MessageTypes.BusinessMessageReject)
                    .containsFieldWithValue(RefSeqNum.get(), REJECTED_SEQ_NUM)
                    .containsFieldWithValue(BusinessRejectReason.get(),
                            String.valueOf(BusinessRejectReasonCodes.APPLICATION_NOT_AVAILABLE.getCode()));

            await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(3));
        }
    }

    @Test
    void M_receive_message_with_conditionally_required_field_missing() throws Exception {
        // Condition/Stimulus: Receive a message in which a conditionally required field is missing.
        // Expected Behavior:
        //   1. Send BusinessMessageReject(35=j) with BusinessRejectReason(380) set to 5 (Conditionally Required Field
        //      Missing).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // ClOrdID(11) is required on a NewOrderSingle(35=D). A missing body field of an application message is
            // answered at the business level, not with a session Reject(35=3)
            session.send(rawMessage("D", "55=IBM", "54=1", "40=1", "60=" + RawFixSocketClient.utcTimestamp(Instant.now())));

            String reply = session.readMessageOfType(MessageTypes.BusinessMessageReject, DEFAULT_TIMEOUT);
            assertThatFixMessage(reply)
                    .hasMsgType(MessageTypes.BusinessMessageReject)
                    .containsFieldWithValue(RefSeqNum.get(), REJECTED_SEQ_NUM)
                    .containsFieldWithValue(RefMsgType.get(), MessageTypes.NewOrderSingle.code())
                    .containsFieldWithValue(BusinessRejectReason.get(),
                            String.valueOf(BusinessRejectReasonCodes.CONDITIONALLY_REQUIRED_FIELD_MISSING.getCode()));

            await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(3));
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        }
    }

    @Disabled(UNSUPPORTED_CRYPTOGRAPHY)
    @Test
    void N_receive_field_in_both_cleartext_and_encrypted_with_different_values() {
        // Condition/Stimulus: Receive a message in which a field identifier (tag number) appears in both cleartext and
        //   encrypted section but has different values.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 7 (Decryption problem).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
    }

    /**
     * Decodes just enough of a NewOrderSingle(35=D) for the validations this scenario exercises to run: a field is only
     * validated if the decoder maps it.
     */
    private static final class NewOrderSingleDecoder implements FixMessageDecoder {

        /**
         * Set by L to make a field setter throw, standing in for application layer processing being unavailable.
         */
        boolean failApplicationProcessing;

        @Override
        public MessageType getMessageType() {
            return MessageTypes.NewOrderSingle;
        }

        @Override
        public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
            fixFieldsDecoderMapper
                    .mapStringField(ClOrdID.get(), value -> {
                        if (failApplicationProcessing) {
                            throw new IllegalStateException("application layer is not available");
                        }
                    }, null)
                    .mapStringField(Symbol.get(), value -> {
                    }, null)
                    .mapCharValuesEnumField(Side.get(), (Side.SideValues value) -> {
                    }, null)
                    .forGroup(NoAllocs.get())
                    .mapStringField(AllocAccount.get(), value -> {
                    }, null);
        }
    }
}
