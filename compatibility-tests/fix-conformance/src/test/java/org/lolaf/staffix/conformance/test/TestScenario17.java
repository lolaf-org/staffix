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
import org.lolaf.staffix.api.codec.SessionRejectReasonCodes;
import org.lolaf.staffix.fix44.fields.*;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;

/**
 * Scenario 17 - Support encryption (applicable to all FIX systems). Optional.
 * <p>
 * The scenario's ten cases fall into two groups, and this engine answers only the first.
 * <p>
 * Cases a and b are the EncryptMethod(98) negotiation and need no cryptography at all. They are implemented: the only
 * method this engine supports is 0, no FIX level encryption, which it echoes on its Logon acknowledgement, and a peer
 * asking for any of methods 1 to 6 is rejected and logged out rather than let in. That last part is the point of
 * implementing them - before this, a Logon declaring PGP/DES was accepted and its body then read as cleartext, leaving
 * the peer believing encryption had been negotiated.
 * <p>
 * Cases c to j are the wire level cryptography: Signature(89) and SignatureLength(93) verification, and
 * SecureData(91)/SecureDataLen(90) sections to be decrypted before parsing. They stay disabled, individually and with
 * their reason, because the methods EncryptMethod(98) offers for them - DES, PGP/DES, PEM/DES-MD5 - have been obsolete
 * for decades, DES itself being broken since the nineties. Transport security here is TLS, configured through betty's
 * SSL settings, and adding a second and far weaker mechanism beside it would make the engine worse rather than more
 * conformant. Scenario 14 case N is disabled for the same reason.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario17 extends AbstractScenario {

    static final String UNSUPPORTED_CRYPTOGRAPHY =
            "FIX level encryption is not supported: the EncryptMethod(98) methods this case needs (DES, PGP/DES, "
                    + "PEM/DES-MD5) are obsolete and transport security is provided by TLS instead. See this class' javadoc.";
    private static final String NO_FIX_LEVEL_ENCRYPTION = "0";
    /**
     * PGP/DES, one of the methods this engine deliberately does not support.
     */
    private static final String PGP_DES = "4";

    @Test
    void A_receive_logon_with_valid_supported_encryptmethod() throws Exception {
        // Condition/Stimulus: Receive Logon(35=A) request message with valid, supported EncryptMethod(98).
        // Expected Behavior:
        //   1. Accept the message.
        //   2. Perform the appropriate decryption and encryption method readiness.
        //   3. Respond with Logon(35=A) acknowledgement message with the same EncryptMethod(98).
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorConnect()) {
            session.send(session.message(MessageTypes.Logon, 1)
                    .set(EncryptMethod.get(), NO_FIX_LEVEL_ENCRYPTION).set(HeartBtInt.get(), "5"));

            // 1. and 3. the Logon is acknowledged, and the acknowledgement carries the same EncryptMethod
            assertThatFixMessage(session.readMessageOfType(MessageTypes.Logon, DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Logon)
                    .containsFieldWithValue(EncryptMethod.get(), NO_FIX_LEVEL_ENCRYPTION);

            // 2. readiness, such as it is for a session that will exchange cleartext: the session is established and
            // goes on processing messages
            await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(2));
            assertThat(fixAcceptorSession.isLoggedIn()).isTrue();

            session.send(session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario17A"));
            assertThatFixMessage(session.readMessageWithField(TestReqID.get(), "Scenario17A", DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Heartbeat);
        }
    }

    @Test
    void B_receive_logon_with_invalid_or_unsupported_encryptmethod() throws Exception {
        // Condition/Stimulus: Receive Logon(35=A) message with invalid or unsupported EncryptMethod(98).
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 7 (Decryption problem).
        //   2. Increment NextNumIn.
        //   3. Send Logout(35=5) request message referencing invalid or unsupported EncryptMethod(98) value.
        //   4. (Optional) Wait for Logout(35=5) acknowledgement message response (Note: could have decrypt problems) or
        //      wait LogoutAckThreshold seconds, whichever comes first.
        //   5. Disconnect.
        //   6. Generate an error condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorConnect()) {
            session.send(session.message(MessageTypes.Logon, 1)
                    .set(EncryptMethod.get(), PGP_DES).set(HeartBtInt.get(), "5"));

            // 1. rejected as a decryption problem, naming the field that could not be honoured
            assertThatFixMessage(session.readMessageOfType(MessageTypes.Reject, DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Reject)
                    .containsFieldWithValue(RefSeqNum.get(), "1")
                    .containsFieldWithValue(RefTagID.get(), "98")
                    .containsFieldWithValue(SessionRejectReason.get(),
                            String.valueOf(SessionRejectReasonCodes.DECRYPTION_PROBLEM.getCode()));

            // 2. the rejected Logon still consumed its sequence number
            await().untilAsserted(() -> assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(2));

            // 3. and a Logout follows, quoting the value that was refused
            assertThatFixMessage(session.readMessageOfType(MessageTypes.Logout, DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Logout)
                    .containsFieldWithValueStartingWith(Text.get(), "Invalid or unsupported EncryptMethod(98)=" + PGP_DES);

            // 4. and 5. acknowledging that Logout brings the disconnection forward, rather than waiting out
            // logInOrOutResponseTimeout
            session.send(session.message(MessageTypes.Logout, 2));
            assertThat(session.isClosedByPeer(DEFAULT_TIMEOUT)).isTrue();

            // 6. the refusal is on the record
            assertThat(acceptorLogger.getEvents())
                    .anyMatch(event -> event.contains("Rejecting logon") && event.contains("EncryptMethod(98)=" + PGP_DES));
        }
    }

    @Test
    @Disabled(UNSUPPORTED_CRYPTOGRAPHY)
    void C_receive_message_with_valid_signaturelength_and_signature() {
        // Condition/Stimulus: Receive message with valid SignatureLength(93) and Signature(89) values.
        // Expected Behavior: Accept the message.
    }

    @Test
    @Disabled(UNSUPPORTED_CRYPTOGRAPHY)
    void D_receive_message_with_invalid_signaturelength() {
        // Condition/Stimulus: Receive message with invalid SignatureLength(93) value.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 8 (Signature problem).
        //   2. Increment inbound MsgSeqNum(34).
        //   3. Generate an error condition in test output.
    }

    @Test
    @Disabled(UNSUPPORTED_CRYPTOGRAPHY)
    void E_receive_message_with_invalid_signature() {
        // Condition/Stimulus: Receive message with invalid Signature(89) value.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 8 (Signature problem).
        //   2. Increment inbound MsgSeqNum(34).
        //   3. Generate an error condition in test output.
        //   Or consider decryption error or message out of order, ignore message (do not increment inbound
        //   MsgSeqNum(34)) and continue accepting messages.
    }

    @Test
    @Disabled(UNSUPPORTED_CRYPTOGRAPHY)
    void F_receive_message_with_valid_securedatalen_and_decryptable_securedata() {
        // Condition/Stimulus: Receive message with a valid SecureDataLen(90) value and a SecureData(91) value that can
        //   be decrypted into valid, parsable cleartext.
        // Expected Behavior: Accept the message.
    }

    @Test
    @Disabled(UNSUPPORTED_CRYPTOGRAPHY)
    void G_receive_message_with_invalid_securedatalen() {
        // Condition/Stimulus: Receive message with invalid SecureDataLen(90) value.
        // Expected Behavior:
        //   1. Consider garbled and ignore message.
        //   2. Generate an error condition in test output.
    }

    @Test
    @Disabled(UNSUPPORTED_CRYPTOGRAPHY)
    void H_receive_message_with_securedata_that_cannot_be_decrypted() {
        // Condition/Stimulus: Receive message with a SecureData(91) value that cannot be decrypted into valid, parsable
        //   cleartext.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 7 (Decryption problem).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
    }

    @Test
    @Disabled(UNSUPPORTED_CRYPTOGRAPHY)
    void I_receive_message_with_must_be_unencrypted_fields_in_encrypted_portion() {
        // Condition/Stimulus: Receive message with one or more fields not present in the unencrypted portion of the
        //   message that "must be unencrypted" according to the spec.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 7 (Decryption problem).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
    }

    @Test
    @Disabled(UNSUPPORTED_CRYPTOGRAPHY)
    void J_receive_message_with_incorrect_leftover_characters_handling() {
        // Condition/Stimulus: Receive message with incorrect handling of "leftover" characters (e.g. when length of
        //   cleartext prior to encryption is not a multiple of 8) according to the specified EncryptMethod(98).
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 7 (Decryption problem).
        //   2. Increment NextNumIn.
        //   3. Generate an error condition in test output.
    }
}
