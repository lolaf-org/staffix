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
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.fix44.fields.TestReqID;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Scenario 3 - Receive Message Standard Trailer (applicable to all FIX systems). Mandatory.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario3 extends AbstractScenario {

    /**
     * Reads the CheckSum(10) value the message carries, which the builder computed over the emitted bytes.
     */
    private static int checksumOf(byte[] message) {
        String text = new String(message, StandardCharsets.US_ASCII);
        return Integer.parseInt(text.substring(text.lastIndexOf("10=") + 3, text.length() - 1));
    }

    /**
     * Rebuilds the raw bytes of {@code message} with its CheckSum(10) field replaced by {@code rawChecksumField}
     * (given without the trailing SOH), leaving every other byte untouched.
     */
    private static byte[] withRawChecksumField(byte[] message, String rawChecksumField) {
        String text = new String(message, StandardCharsets.US_ASCII);
        String withoutChecksum = text.substring(0, text.lastIndexOf(CoreFields.FIELD_SEPARATOR + "10=") + 1);
        return (withoutChecksum + rawChecksumField + CoreFields.FIELD_SEPARATOR).getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    FixSessionSettings.FixSessionSettingsBuilder<?, ?> getAcceptorFixSessionSettings() {
        return super.getAcceptorFixSessionSettings()
                .validationSettings(FixSessionSettings.ValidationSettings.builder()
                        // B and E rely on the CheckSum(10) being verified, C on the framing rules of section 4.5.2
                        .validateChecksum(true)
                        .detectGarbledMessages(true)
                        .build());
    }

    /**
     * Asserts the expected behavior shared by the garbled trailer cases (B, C and E): a garbled message carrying
     * MsgSeqNum(34) 2 has just been sent and must be disregarded without being answered, without incrementing
     * NextNumIn and without dropping the session, while a warning is logged.
     */
    private void assertGarbledMessageIgnored(RawFixSocketClient.Session session, String testReqId) throws Exception {
        // a warning is logged for the garbled message ...
        await().untilAsserted(() -> verify(acceptorLogger, atLeastOnce())
                .logEvent(any(), eq("Garbled message received, ignoring it: %s"), any()));

        // ... it is not answered and the session keeps accepting messages
        assertThat(session.isClosedByPeer(Duration.ofMillis(500))).isFalse();
        assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
        verify(fixAcceptorApplication, never()).onTestRequest(any(FixSession.class), eq(testReqId), any(UTCTime.class));

        // ... and NextNumIn is left untouched, so the very same MsgSeqNum(34) is still the one expected next: a
        // well-formed TestRequest re-using it is accepted and answered, instead of being logged out as too low
        assertThat(acceptorMessageStore.getIncomingSeqNum()).isEqualTo(2);

        session.send(session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), testReqId + "-next"));
        String reply = session.readMessageWithField(TestReqID.get(), testReqId + "-next", DEFAULT_TIMEOUT);
        assertThatFixMessage(reply)
                .hasMsgType(MessageTypes.Heartbeat)
                .containsFieldWithValue(TestReqID.get(), testReqId + "-next")
                .doesNotHaveMsgType(MessageTypes.Reject)
                .doesNotHaveMsgType(MessageTypes.Logout);
    }

    @Test
    void A_valid_checksum() throws Exception {
        // Condition/Stimulus: Valid CheckSum(10).
        // Expected Behavior: Accept message.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // the builder computes the CheckSum(10) over the emitted bytes: the message is accepted and answered
            session.send(session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario3A"));

            String reply = session.readMessageWithField(TestReqID.get(), "Scenario3A", DEFAULT_TIMEOUT);
            assertThatFixMessage(reply)
                    .hasMsgType(MessageTypes.Heartbeat)
                    .containsFieldWithValue(TestReqID.get(), "Scenario3A")
                    .doesNotHaveMsgType(MessageTypes.Reject);
            verify(fixAcceptorApplication).onTestRequest(any(FixSession.class), eq("Scenario3A"), any(UTCTime.class));
        }
    }

    @Test
    void B_invalid_checksum() throws Exception {
        // Condition/Stimulus: Invalid CheckSum(10).
        // Expected Behavior:
        //   1. Consider garbled and ignore message.
        //   2. Do not increment NextNumIn.
        //   3. Continue accepting messages.
        //   4. Generate a warning condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // a well-formed in-sequence TestRequest whose CheckSum(10) is replaced by a wrong, correctly formatted one.
            // Derived from the right one rather than hardcoded: the correct value moves with SendingTime(52), it only
            // ever takes 63 of the 256 possible values, and a fixed guess collides with it on 1 run in 63 - on which
            // the message is simply valid, is accepted, and this test waits 30 seconds for a warning that never comes.
            byte[] message = session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario3B").build();
            session.send(withRawChecksumField(message, String.format("10=%03d", (checksumOf(message) + 1) % 256)));

            assertGarbledMessageIgnored(session, "Scenario3B");
        }
    }

    @Test
    void C_garbled_message() throws Exception {
        // Condition/Stimulus: Garbled message.
        // Expected Behavior:
        //   1. Consider garbled and ignore message.
        //   2. Do not increment NextNumIn.
        //   3. Continue accepting messages.
        //   4. Generate a warning condition in test output.
        // Section 4.5.2 makes a message garbled when "BeginString(8) is not the first tag in a message": MsgSeqNum(34)
        // is sent ahead of it here. This is the half of that rule scenario 2-D does not cover, which exercises the
        // other half (a BeginString that is not a defined FIX session profile identifier).
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            byte[] wellFormed = session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario3C").build();
            String text = new String(wellFormed, StandardCharsets.US_ASCII);
            // move MsgSeqNum(34) in front of BeginString(8), keeping every other byte as it was
            String seqNumField = "34=2" + CoreFields.FIELD_SEPARATOR;
            String garbled = seqNumField + text.replace(seqNumField, "");
            session.send(garbled.getBytes(StandardCharsets.US_ASCII));

            assertGarbledMessageIgnored(session, "Scenario3C");
        }
    }

    @Test
    void D_checksum_is_last_field_length_3_delimited_by_soh() throws Exception {
        // Condition/Stimulus: CheckSum(10) is last field of message, value has length of 3, and is delimited by <SOH>.
        // Expected Behavior: Accept message.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            byte[] message = session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario3D").build();

            // assert the stimulus itself: the message ends with a 3 character, SOH delimited CheckSum(10) field
            String text = new String(message, StandardCharsets.US_ASCII);
            assertThat(text).matches("(?s).*" + CoreFields.FIELD_SEPARATOR + "10=\\d{3}" + CoreFields.FIELD_SEPARATOR + "$");

            session.send(message);

            String reply = session.readMessageWithField(TestReqID.get(), "Scenario3D", DEFAULT_TIMEOUT);
            assertThatFixMessage(reply)
                    .hasMsgType(MessageTypes.Heartbeat)
                    .containsFieldWithValue(TestReqID.get(), "Scenario3D")
                    .doesNotHaveMsgType(MessageTypes.Reject);
            verify(fixAcceptorApplication).onTestRequest(any(FixSession.class), eq("Scenario3D"), any(UTCTime.class));
        }
    }

    @Test
    void E_checksum_not_last_field_or_not_length_3_or_not_delimited_by_soh() throws Exception {
        // Condition/Stimulus: CheckSum(10) is not the last field of message, value does not have length of 3, or is
        //   not delimited by <SOH>.
        // Expected Behavior:
        //   1. Consider garbled and ignore message.
        //   2. Do not increment NextNumIn.
        //   3. Continue accepting messages.
        //   4. Generate a warning condition in test output.
        // Exercises the "value does not have length of 3" limb, the one the engine checks explicitly: the CheckSum is
        // sent unpadded. The "not the last field" limb is caught by the BodyLength(9) count instead, and a CheckSum
        // not delimited by SOH simply never completes a message, so neither has a dedicated check.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            byte[] message = session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario3E").build();
            // resend the very same checksum value with an extra leading zero: it still parses to the value the
            // acceptor computes, so its length of four is the only thing wrong with the message
            session.send(withRawChecksumField(message, String.format("10=0%03d", checksumOf(message))));

            assertGarbledMessageIgnored(session, "Scenario3E");
        }
    }
}
