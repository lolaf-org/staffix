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
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.fix44.fields.*;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

/**
 * Scenario 2 - Receive Message Standard Header (applicable to all FIX systems). Mandatory.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario2 extends AbstractScenario {

    /**
     * Asserts the expected behavior shared by the garbled message cases (D, M and T): a garbled message carrying
     * MsgSeqNum(34) 2 has just been sent and must be disregarded without being answered, without incrementing
     * NextNumIn and without dropping the session, while a warning is logged.
     */
    private void assertGarbledMessageIgnored(RawFixSocketClient.Session session, String testReqId) throws Exception {
        // a warning is logged for the garbled message ...
        await().untilAsserted(() -> verify(acceptorLogger).logEvent(any(), eq("Garbled message received, ignoring it: %s"), any()));

        // ... it is not answered: no Reject(35=3) and no Logout(35=5) is sent back, and the session stays up
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

    @Override
    FixSessionSettings.FixSessionSettingsBuilder<?, ?> getAcceptorFixSessionSettings() {
        // enable SendingTime(52) accuracy validation (generous threshold: current-time messages used by the other
        // scenarios stay well within it, while O sends a deliberately stale SendingTime) and CompID validation (all
        // scenarios use the expected SENDER44_TEST/TARGET44_TEST comp ids, K sends a mismatched one).
        return super.getAcceptorFixSessionSettings()
                // when the acceptor logs out after a reject (K/O), disconnect quickly instead of waiting the default
                // 10s for a Logout ack from the raw client (which never acks).
                .logInOrOutResponseTimeout(Duration.ofSeconds(1))
                .validationSettings(FixSessionSettings.ValidationSettings.builder()
                        .maxSendingTime(Duration.ofSeconds(30))
                        .validateCompId(true)
                        .validateBeginString(true)
                        // D/M/T send messages breaching the session layer framing rules, which must be disregarded
                        // rather than rejected
                        .detectGarbledMessages(true)
                        .build());
    }

    @Test
    void A_msgseqnum_received_as_expected() {
        // Condition/Stimulus: MsgSeqNum(34) received as expected.
        // Expected Behavior: Accept MsgSeqNum(34) for the message.
        logonClient();

        // Send an in-sequence (admin) message from the initiator; the acceptor is the system under test.
        fixInitiatorSession.testRequest("Scenario2A");

        // The message is accepted and processed: the acceptor answers the in-sequence TestRequest ...
        await().untilAsserted(() -> verify(fixAcceptorApplication).onTestRequest(any(FixSession.class), eq("Scenario2A"), any(UTCTime.class)));

        // ... and does not treat it as a gap (no ResendRequest) nor drop the session.
        verify(fixAcceptorApplication, never()).onResendRequestInitiated(any(FixSession.class), anyLong(), anyLong());
        assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
    }

    @Test
    void B_msgseqnum_higher_than_expected() {
        // Condition/Stimulus: MsgSeqNum(34) higher than expected.
        // Expected Behavior: Respond with ResendRequest(35=2) message.
        // Allow the initiator to honour the acceptor's resend request so the gap is actually filled.
        when(fixInitiatorApplication.onResendRequest(any(FixSession.class), any(), any())).thenReturn(true);

        logonClient();

        // Create a gap: skip a few of the initiator's outgoing sequence numbers so that the next message
        // the acceptor receives carries a MsgSeqNum(34) higher than expected.
        long nextOutgoingSeqNum = initiatorMessageStore.getOutgoingSeqNum();
        initiatorMessageStore.setCurrentOutgoingSeqNum(nextOutgoingSeqNum + 3);

        fixInitiatorSession.testRequest("Scenario2B");

        // The acceptor detects the gap and responds with a ResendRequest(35=2) for the missing range.
        await().untilAsserted(() -> verify(fixAcceptorApplication).onResendRequestInitiated(any(FixSession.class), anyLong(), anyLong()));

        // The peer replays the missing range (gap fill + resent message) and the session resynchronizes.
        await().untilAsserted(() -> verify(fixAcceptorApplication).onResendRequestTerminated(any(FixSession.class), anyLong(), anyLong()));
        assertThat(fixAcceptorSession.isLoggedIn()).isTrue();
    }

    @Test
    void C_msgseqnum_lower_than_expected_without_possdupflag() throws Exception {
        // Condition/Stimulus: MsgSeqNum(34) lower than expected without PossDupFlag(43) set to Y.
        //   Exception: SequenceReset(35=4).
        // Expected Behavior:
        //   1. Whenever possible it is recommended that FIX engine attempt to send a Logout(35=5) message with a text
        //      message of "MsgSeqNum too low, expecting X but received Y".
        //   2. (Optional) Wait for Logout(35=5) message response (Note: likely will have inaccurate MsgSeqNum(34)) or
        //      wait 2 seconds, whichever comes first.
        //   3. Disconnect.
        //   4. Generate an error condition in test output.
        // Driven over a raw socket so we can send an arbitrary (too low) MsgSeqNum(34) with no PossDupFlag(43), which a
        // real initiator session would never do.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {

            // Send a TestRequest re-using MsgSeqNum 1 (lower than the expected 2) without PossDupFlag(43)=Y.
            session.send(session.message(MessageTypes.TestRequest, 1).set(TestReqID.get(), "Scenario2C"));

            // The acceptor responds with a Logout(35=5) carrying the mandated "MsgSeqNum too low" text ...
            String logoutReply = session.readMessageOfType(MessageTypes.Logout, DEFAULT_TIMEOUT);
            assertThatFixMessage(logoutReply)
                    .hasMsgType(MessageTypes.Logout)
                    .containsFieldWithValue(Text.get(), "MsgSeqNum too low, expecting 2 but received 1");

            // ... and then disconnects.
            assertThat(session.isClosedByPeer(DEFAULT_TIMEOUT)).isTrue();
        }

        verify(fixAcceptorApplication).onPreLogout(any(FixSession.class), startsWith("MsgSeqNum too low"), eq(true));
    }

    @Test
    void D_garbled_message_received() throws Exception {
        // Condition/Stimulus: Garbled message received.
        // Expected Behavior:
        //   1. Consider garbled and ignore message (do not increment NextNumIn) and continue accepting messages.
        //   2. Generate a warning condition in test output.
        // Session Layer 4.5.2 defines a message as garbled when BeginString(8) "is not one of the defined FIX session
        // profile identifiers": FIX.9.9 is no FIX version the engine knows about. Note the difference with scenario I,
        // where the BeginString is a real FIX version, only not the one agreed for the session: that one is answered
        // with a Logout, a garbled message is answered with nothing at all.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            session.send(RawFixSocketClient.fixMessage("FIX.9.9",
                    "35=1", "34=2", "49=SENDER44_TEST", "56=TARGET44_TEST",
                    "52=" + RawFixSocketClient.utcTimestamp(Instant.now()), "112=Scenario2D"));

            assertGarbledMessageIgnored(session, "Scenario2D");
        }
    }

    @Test
    void E_possdupflag_y_origsendingtime_lte_sendingtime_and_msgseqnum_lower_than_expected() throws Exception {
        // Condition/Stimulus: PossDupFlag(43) set to Y; OrigSendingTime(122) specified is less than or equal to
        //   SendingTime(52) and MsgSeqNum(34) lower than expected.
        //   Note: OrigSendingTime(122) should be earlier than SendingTime(52) unless the message is being resent within
        //   the same second during which it was sent.
        // Expected Behavior:
        //   1. Check to see if MsgSeqNum(34) has already been received.
        //   2. If already received then ignore the message, otherwise accept and process the message.
        // Driven over a raw socket so we can craft a PossDupFlag(43)=Y retransmission with a chosen (too low) MsgSeqNum.
        fixAcceptor.start();
        Instant now = Instant.now();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // Retransmission (PossDupFlag=Y, OrigSendingTime <= SendingTime) of an already received MsgSeqNum (1 < 2):
            // it must be ignored, not answered and not fatal.
            session.send(session.message(MessageTypes.TestRequest, 1).set(PossDupFlag.get(), "Y")
                    .origSendingTime(now.minusSeconds(1)).sendingTime(now).set(TestReqID.get(), "Scenario2E-dup"));

            // In-sequence TestRequest (MsgSeqNum 2) proving the session is still alive and sequence processing
            // continued past the ignored duplicate: the acceptor answers with a Heartbeat echoing the TestReqID.
            session.send(session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario2E-live"));

            String reply = session.readMessageWithField(TestReqID.get(), "Scenario2E-live", DEFAULT_TIMEOUT);
            // The reply is a Heartbeat(35=0) answering the in-sequence TestRequest, not a Logout(35=5).
            assertThatFixMessage(reply)
                    .hasMsgType(MessageTypes.Heartbeat)
                    .containsFieldWithValue(TestReqID.get(), "Scenario2E-live")
                    .doesNotHaveMsgType(MessageTypes.Logout);
            assertThat(session.isClosedByPeer(Duration.ofSeconds(1))).isFalse();
        }

        // The duplicate was ignored (never processed) and the session was never logged out.
        verify(fixAcceptorApplication).onTestRequest(any(FixSession.class), eq("Scenario2E-live"), any(UTCTime.class));
        verify(fixAcceptorApplication, never()).onTestRequest(any(FixSession.class), eq("Scenario2E-dup"), any(UTCTime.class));
        verify(fixAcceptorApplication, never()).onPreLogout(any(FixSession.class), startsWith("MsgSeqNum too low"), eq(true));
    }

    @Test
    void F_possdupflag_y_origsendingtime_gt_sendingtime_and_msgseqnum_as_expected() throws Exception {
        // Condition/Stimulus: PossDupFlag(43) set to Y; OrigSendingTime(122) specified is greater than SendingTime(52)
        //   and MsgSeqNum(34) as expected.
        //   Note: OrigSendingTime(122) should be earlier than SendingTime(52) unless the message is being resent within
        //   the same second during which it was sent.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 10 (SendingTime accuracy problem).
        //   2. Increment NextNumIn.
        //   3. Optional:
        //        - Send Logout(35=5) message referencing inaccurate SendingTime(52) value.
        //        - (Optional) Wait for Logout(35=5) message response (Note: likely will have inaccurate SendingTime(52))
        //          or wait 2 seconds, whichever comes first.
        //        - Disconnect.
        //        - Generate an error condition in test output.
        fixAcceptor.start();
        Instant now = Instant.now();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // In-sequence (MsgSeqNum 2) PossDup message whose OrigSendingTime(122) is AFTER SendingTime(52).
            session.send(session.message(MessageTypes.TestRequest, 2).set(PossDupFlag.get(), "Y")
                    .sendingTime(now).origSendingTime(now.plusSeconds(60)).set(TestReqID.get(), "Scenario2F"));

            // Reject(35=3) with SessionRejectReason(373)=10 (SendingTime accuracy problem).
            String reject = session.readMessageOfType(MessageTypes.Reject, DEFAULT_TIMEOUT);
            assertThatFixMessage(reject)
                    .hasMsgType(MessageTypes.Reject)
                    .containsFieldWithValue(SessionRejectReason.get(), "10")
                    .containsFieldWithValue(Text.get(), "OrigSendingTime is after SendingTime");
        }
    }

    @Test
    void G_possdupflag_y_and_origsendingtime_not_specified() throws Exception {
        // Condition/Stimulus: PossDupFlag(43) set to Y and OrigSendingTime(122) not specified.
        //   Note: Always set OrigSendingTime(122) to the time when the message was originally sent - not the present
        //   SendingTime(52) and set PossDupFlag(43)=Y when responding to a ResendRequest(35=2).
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 1 (Required tag missing).
        //   2. Increment NextNumIn.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // In-sequence (MsgSeqNum 2) PossDup message with no OrigSendingTime(122).
            session.send(session.message(MessageTypes.TestRequest, 2).set(PossDupFlag.get(), "Y").set(TestReqID.get(), "Scenario2G"));

            // Reject(35=3) with SessionRejectReason(373)=1 (Required tag missing).
            String reject = session.readMessageOfType(MessageTypes.Reject, DEFAULT_TIMEOUT);
            assertThatFixMessage(reject)
                    .hasMsgType(MessageTypes.Reject)
                    .containsFieldWithValue(Text.get(), "OrigSendingTime is required when PossDupFlag is Y")
                    .containsFieldWithValue(SessionRejectReason.get(), "1"); // Required tag missing

            // NextNumIn was incremented (to 3): a following in-sequence TestRequest is accepted and answered.
            session.send(session.message(MessageTypes.TestRequest, 3).set(TestReqID.get(), "Scenario2G-next"));
            String heartbeat = session.readMessageWithField(TestReqID.get(), "Scenario2G-next", DEFAULT_TIMEOUT);
            assertThatFixMessage(heartbeat)
                    .hasMsgType(MessageTypes.Heartbeat)
                    .containsFieldWithValue(TestReqID.get(), "Scenario2G-next");
        }
    }

    @Test
    void H_beginstring_received_as_expected() throws Exception {
        // Condition/Stimulus: BeginString(8) value received as expected and specified in testing profile and matches
        //   BeginString(8) on outbound messages.
        // Expected Behavior: Accept BeginString(8) for the message.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // The Logon and this message carry BeginString(8)=FIX.4.4, matching the acceptor's configured version: accepted.
            session.send(session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario2H"));
            String reply = session.readMessageWithField(TestReqID.get(), "Scenario2H", DEFAULT_TIMEOUT);
            assertThatFixMessage(reply)
                    .hasMsgType(MessageTypes.Heartbeat)
                    .containsFieldWithValue(TestReqID.get(), "Scenario2H")
                    .doesNotHaveMsgType(MessageTypes.Reject);
        }
    }

    @Test
    void I_beginstring_received_did_not_match_expected() throws Exception {
        // Condition/Stimulus: BeginString(8) value received did not match value expected and specified in testing
        //   profile or does not match BeginString(8) on outbound messages.
        // Expected Behavior:
        //   1. Send Logout(35=5) message referencing incorrect BeginString(8) value.
        //   2. (Optional) Wait for Logout(35=5) message response (Note: likely will have incorrect BeginString(8)) or
        //      wait LogoutAckThreshold seconds, whichever comes first.
        //   3. Disconnect.
        //   4. Generate an error condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // In-sequence TestRequest but carrying BeginString(8)=FIX.4.2, not the session's FIX.4.4 (raw hatch since
            // the builder always uses the connection's version).
            session.send(RawFixSocketClient.fixMessage("FIX.4.2",
                    "35=1", "34=2", "49=SENDER44_TEST", "56=TARGET44_TEST",
                    "52=" + RawFixSocketClient.utcTimestamp(Instant.now()), "112=Scenario2I"));

            String logout = session.readMessageOfType(MessageTypes.Logout, DEFAULT_TIMEOUT);
            assertThatFixMessage(logout).hasMsgType(MessageTypes.Logout);
            assertThat(session.isClosedByPeer(DEFAULT_TIMEOUT)).isTrue();
        }
    }

    @Test
    void J_sendercompid_and_targetcompid_received_as_expected() throws Exception {
        // Condition/Stimulus: SenderCompID(49) and TargetCompID(56) values received as expected and specified in
        //   testing profile.
        // Expected Behavior: Accept SenderCompID(49) and TargetCompID(56) for the message.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // In-sequence TestRequest with the expected comp ids: accepted and answered with a Heartbeat.
            session.send(session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario2J"));
            String reply = session.readMessageWithField(TestReqID.get(), "Scenario2J", DEFAULT_TIMEOUT);
            assertThatFixMessage(reply)
                    .hasMsgType(MessageTypes.Heartbeat)
                    .containsFieldWithValue(TestReqID.get(), "Scenario2J")
                    .doesNotHaveMsgType(MessageTypes.Reject);
        }
    }

    @Test
    void K_sendercompid_and_targetcompid_did_not_match_expected() throws Exception {
        // Condition/Stimulus: SenderCompID(49) and TargetCompID(56) values received did not match values expected and
        //   specified in testing profile.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with RefTagID(371) to identify field with mismatched value, and
        //      SessionRejectReason(373) set to 9 (CompID problem).
        //   2. Increment NextNumIn.
        //   3. Send Logout(35=5) message referencing incorrect SenderCompID(49) or TargetCompID(56) value.
        //   4. (Optional) Wait for Logout(35=5) message response (Note: likely will have incorrect SenderCompID(49) or
        //      TargetCompID(56)) or wait 2 seconds, whichever comes first.
        //   5. Disconnect.
        //   6. Generate an error condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // In-sequence TestRequest with a mismatched SenderCompID(49).
            session.send(session.message(MessageTypes.TestRequest, 2).senderCompId("WRONGSENDER").set(TestReqID.get(), "Scenario2K"));

            // Reject(35=3) with RefTagID(371)=49 and SessionRejectReason(373)=9 (CompID problem) ...
            String reject = session.readMessageOfType(MessageTypes.Reject, DEFAULT_TIMEOUT);
            assertThatFixMessage(reject)
                    .hasMsgType(MessageTypes.Reject)
                    .containsFieldWithValue(SessionRejectReason.get(), "9")
                    .containsFieldWithValue(RefTagID.get(), "49")
                    .containsFieldWithValueStartingWith(Text.get(), "Wrong SENDER_COMP_ID");

            // ... followed by a Logout(35=5) and disconnect.
            String logout = session.readMessageOfType(MessageTypes.Logout, DEFAULT_TIMEOUT);
            assertThatFixMessage(logout).hasMsgType(MessageTypes.Logout);
            assertThat(session.isClosedByPeer(DEFAULT_TIMEOUT)).isTrue();
        }
    }

    @Test
    void L_bodylength_received_is_correct() throws Exception {
        // Condition/Stimulus: BodyLength(9) value received is correct.
        // Expected Behavior: Accept BodyLength(9) for the message.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // The message carries a correct BodyLength(9): accepted and answered with a Heartbeat.
            session.send(session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario2L"));
            String reply = session.readMessageWithField(TestReqID.get(), "Scenario2L", DEFAULT_TIMEOUT);
            assertThatFixMessage(reply)
                    .hasMsgType(MessageTypes.Heartbeat)
                    .containsFieldWithValue(TestReqID.get(), "Scenario2L")
                    .doesNotHaveMsgType(MessageTypes.Reject);
        }
    }

    @Test
    void M_bodylength_received_is_not_correct() throws Exception {
        // Condition/Stimulus: BodyLength(9) value received is not correct.
        // Expected Behavior:
        //   1. Consider garbled and ignore message (do not increment NextNumIn) and continue accepting messages.
        //   2. Generate a warning condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // an in-sequence TestRequest advertising a BodyLength(9) shorter than the body actually sent. The
            // CheckSum(10) still covers the emitted bytes, so BodyLength is the only thing wrong with the message.
            session.send(session.message(MessageTypes.TestRequest, 2).declaredBodyLength(1).set(TestReqID.get(), "Scenario2M"));

            assertGarbledMessageIgnored(session, "Scenario2M");
        }
    }

    @Test
    void N_sendingtime_specified_in_utc_and_within_threshold() throws Exception {
        // Condition/Stimulus: SendingTime(52) value received is specified in UTC (Universal Time Coordinated, also known
        //   as GMT) and is within SendingTimeThreshold seconds of a synchronized time source.
        // Expected Behavior: Accept SendingTime(52) for the message.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // In-sequence TestRequest with a current UTC SendingTime (within the accuracy threshold): accepted and
            // answered with a Heartbeat echoing the TestReqID.
            session.send(session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario2N"));
            String reply = session.readMessageWithField(TestReqID.get(), "Scenario2N", DEFAULT_TIMEOUT);
            assertThatFixMessage(reply)
                    .hasMsgType(MessageTypes.Heartbeat)
                    .containsFieldWithValue(TestReqID.get(), "Scenario2N")
                    .doesNotHaveMsgType(MessageTypes.Reject);
        }
    }

    @Test
    void O_sendingtime_not_utc_or_not_within_threshold() throws Exception {
        // Condition/Stimulus: SendingTime(52) value received is either not specified in UTC (Universal Time Coordinated
        //   / GMT) or is not within SendingTimeThreshold seconds of a synchronized time source.
        //   Rationale: Verify system clocks on both sides are in sync and that SendingTime(52) is current time.
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 10 (SendingTime accuracy problem).
        //   2. Increment NextNumIn.
        //   3. Send Logout(35=5) message referencing inaccurate SendingTime(52) value.
        //   4. (Optional) Wait for Logout(35=5) message response (Note: likely will have inaccurate SendingTime(52)) or
        //      wait 2 seconds, whichever comes first.
        //   5. Disconnect.
        //   6. Generate an error condition in test output.
        // Exercises the "not within threshold" path (a stale SendingTime); the acceptor is configured with a 30s
        // SendingTime accuracy threshold in getAcceptorFixSessionSettings().
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // In-sequence TestRequest whose SendingTime is two minutes old, well beyond the 30s accuracy threshold.
            session.send(session.message(MessageTypes.TestRequest, 2).sendingTime(Instant.now().minusSeconds(120)).set(TestReqID.get(), "Scenario2O"));

            // Reject(35=3) with SessionRejectReason(373)=10 (SendingTime accuracy problem) ...
            String reject = session.readMessageOfType(MessageTypes.Reject, DEFAULT_TIMEOUT);
            assertThatFixMessage(reject)
                    .hasMsgType(MessageTypes.Reject)
                    .containsFieldWithValue(SessionRejectReason.get(), "10")
                    .containsFieldWithValueStartingWith(Text.get(), "Sending time accuracy problem");

            // ... followed by a Logout(35=5) and disconnect.
            String logout = session.readMessageOfType(MessageTypes.Logout, DEFAULT_TIMEOUT);
            assertThatFixMessage(logout).hasMsgType(MessageTypes.Logout);
            assertThat(session.isClosedByPeer(DEFAULT_TIMEOUT)).isTrue();
        }
    }

    @Test
    void P_msgtype_received_is_valid() throws Exception {
        // Condition/Stimulus: MsgType(35) value received is valid (defined in spec or classified as user-defined).
        // Expected Behavior: Accept MsgType(35) for the message.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // A valid, supported MsgType (TestRequest) is accepted and processed (answered with a Heartbeat).
            session.send(session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario2P"));
            String reply = session.readMessageWithField(TestReqID.get(), "Scenario2P", DEFAULT_TIMEOUT);
            assertThatFixMessage(reply)
                    .hasMsgType(MessageTypes.Heartbeat)
                    .containsFieldWithValue(TestReqID.get(), "Scenario2P")
                    .doesNotHaveMsgType(MessageTypes.Reject)
                    .doesNotHaveMsgType(MessageTypes.BusinessMessageReject);
        }
    }

    @Test
    void Q_msgtype_received_is_not_valid() throws Exception {
        // Condition/Stimulus: MsgType(35) value received is not valid (defined in spec or classified as user-defined).
        // Expected Behavior:
        //   1. Send Reject(35=3) message with SessionRejectReason(373) set to 11 (Invalid MsgType).
        //   2. Increment NextNumIn.
        //   3. Generate a warning condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // ZZ is no MsgType of the FIX.4.4 dictionary, and not a user defined one either since those start with
            // 'U': it is not a valid MsgType at all, so the session layer rejects it rather than handing it over to
            // the application for a BusinessMessageReject(35=j) as scenario R does.
            session.send(session.message("ZZ", 2).set(TestReqID.get(), "Scenario2Q"));

            // Reject(35=3) with SessionRejectReason(373)=11 (Invalid MsgType), not a BusinessMessageReject(35=j).
            String reject = session.readMessageOfType(MessageTypes.Reject, DEFAULT_TIMEOUT);
            assertThatFixMessage(reject)
                    .hasMsgType(MessageTypes.Reject)
                    .containsFieldWithValue(SessionRejectReason.get(), "11")
                    .containsFieldWithValue(RefMsgType.get(), "ZZ")
                    .containsFieldWithValue(Text.get(), "Invalid MsgType")
                    .doesNotHaveMsgType(MessageTypes.BusinessMessageReject);

            // NextNumIn was incremented (to 3): a following in-sequence TestRequest is accepted and answered.
            session.send(session.message(MessageTypes.TestRequest, 3).set(TestReqID.get(), "Scenario2Q-next"));
            String heartbeat = session.readMessageWithField(TestReqID.get(), "Scenario2Q-next", DEFAULT_TIMEOUT);
            assertThatFixMessage(heartbeat)
                    .hasMsgType(MessageTypes.Heartbeat)
                    .containsFieldWithValue(TestReqID.get(), "Scenario2Q-next");
        }
    }

    @Test
    void R_msgtype_valid_but_not_supported_or_registered() throws Exception {
        // Condition/Stimulus: MsgType(35) value received is valid (defined in spec or classified as user-defined) but
        //   not supported or registered in testing profile.
        // Expected Behavior:
        //   1. Send BusinessMessageReject(35=j) with BusinessRejectReason(380) set to 3 (Unsupported Message Type).
        //   2. Increment NextNumIn.
        //   3. Generate a warning condition in test output.
        // The application opts to reject unmapped message types.
        when(fixAcceptorApplication.onNoDecoderSetupForMessage(any(FixSession.class), any())).thenReturn(true);

        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // NewOrderSingle(35=D): a valid FIX.4.4 message type for which the acceptor has no decoder registered.
            session.send(session.message(MessageTypes.NewOrderSingle, 2).set(ClOrdID.get(), "order-1").set(Symbol.get(), "SYM")
                    .set(Side.get(), "1").set(TransactTime.get(), RawFixSocketClient.utcTimestamp(Instant.now()))
                    .set(OrderQty.get(), "100").set(OrdType.get(), "1"));

            // BusinessMessageReject(35=j) with BusinessRejectReason(380)=3 (Unsupported Message Type).
            String reject = session.readMessageOfType(MessageTypes.BusinessMessageReject, DEFAULT_TIMEOUT);
            assertThatFixMessage(reject)
                    .hasMsgType(MessageTypes.BusinessMessageReject)
                    .containsFieldWithValue(BusinessRejectReason.get(), "3")
                    .containsFieldWithValue(RefMsgType.get(), "D");
        }
    }

    @Test
    void S_beginstring_bodylength_msgtype_are_first_three_fields() throws Exception {
        // Condition/Stimulus: BeginString(8), BodyLength(9), and MsgType(35) are first three fields of message.
        // Expected Behavior: Accept the message.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // The message has BeginString(8), BodyLength(9) and MsgType(35) as its first three fields: accepted.
            session.send(session.message(MessageTypes.TestRequest, 2).set(TestReqID.get(), "Scenario2S"));
            String reply = session.readMessageWithField(TestReqID.get(), "Scenario2S", DEFAULT_TIMEOUT);
            assertThatFixMessage(reply)
                    .hasMsgType(MessageTypes.Heartbeat)
                    .containsFieldWithValue(TestReqID.get(), "Scenario2S")
                    .doesNotHaveMsgType(MessageTypes.Reject);
        }
    }

    @Test
    void T_beginstring_bodylength_msgtype_are_not_first_three_fields() throws Exception {
        // Condition/Stimulus: BeginString(8), BodyLength(9), and MsgType(35) are not the first three fields of message.
        // Expected Behavior:
        //   1. Consider garbled and ignore message.
        //   2. Do not increment NextNumIn.
        //   3. Continue accepting messages.
        //   4. Generate a warning condition in test output.
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorLogon()) {
            // MsgSeqNum(34) is sent ahead of MsgType(35), so MsgType is the fourth field instead of the third.
            // BeginString(8) and BodyLength(9) keep their position and the BodyLength(9) and CheckSum(10) values
            // sent are correct for the bytes on the wire: the field ordering is the only thing wrong.
            // Note that the engine has a second, independent reason to consider this message garbled: it only starts
            // counting the body length at MsgType(35), so a field sent ahead of it is left out of the count and the
            // BodyLength ends up mismatching too. This test therefore stays green even with detectGarbledMessages
            // off; the field ordering breach is what it asserts when the setting is on, as the logged reason shows.
            session.send(RawFixSocketClient.fixMessage("FIX.4.4",
                    "34=2", "35=1", "49=SENDER44_TEST", "56=TARGET44_TEST",
                    "52=" + RawFixSocketClient.utcTimestamp(Instant.now()), "112=Scenario2T"));

            assertGarbledMessageIgnored(session, "Scenario2T");
        }
    }
}
