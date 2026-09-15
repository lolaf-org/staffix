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
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.fix44.fields.*;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario1B extends AbstractScenario {

    @Test
    void A_establish_transport_layer_connection() {
        fixAcceptor.start();
        fixInitiator.start();

        await().until(() -> fixInitiator.isConnected());
        await().until(() -> fixInitiatorSession.isConnected());
    }

    @Test
    void B_send_logon_request_message() {
        A_establish_transport_layer_connection();

        fixInitiatorSession.logon();

        await().until(() -> fixInitiatorSession.isLoggedIn());
        await().until(() -> fixAcceptorSession.isLoggedIn());
    }

    @Test
    void C_valid_logon_acknowledgement_message_received() {
        // Condition/Stimulus: Valid Logon(35=A) acknowledgement message received.
        // Expected Behavior: accept the acknowledgement and recover the messages missed while disconnected.
        fixAcceptor.start();
        fixInitiator.start();

        // the acceptor already sent 9 messages the initiator never saw: its Logon acknowledgement carries
        // MsgSeqNum(34) 10 and the initiator has to recover 1 to 9
        acceptorMessageStore.setCurrentOutgoingSeqNum(10);

        fixInitiatorSession.logon();

        // the gap is detected and recovered, and the session ends up established on both sides
        await().untilAsserted(() -> verify(fixInitiatorApplication).onResendRequestInitiated(fixInitiatorSession, 1, 9));
        // the acceptor gap fills 1 to 10, so the initiator resumes at 11. Note that the range reported by
        // onResendRequestTerminated is not 1 to 9: the acceptor's own NextExpectedMsgSeqNum(789) processing
        // re-scopes the pending resend request before it completes, so the closed gap is asserted on the sequence
        // number itself rather than on that event.
        await().untilAsserted(() -> assertThat(initiatorMessageStore.getIncomingSeqNum()).isGreaterThanOrEqualTo(11));
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());
        await().untilAsserted(() -> assertThat(fixAcceptorSession.isLoggedIn()).isTrue());

        // The acknowledgement is the first message the initiator receives. Its NextExpectedMsgSeqNum(789) must be
        // what the acceptor expects to receive next, i.e. 2 after the initiator's Logon(34=1), and not the
        // acceptor's own outgoing sequence number: advertising 11 here made the initiator log the session out with
        // "NextExpectedMsgSeqNum is higher than expected" (FIX Session Layer 4.4.1).
        assertThatFixMessage(initiatorLogger.getIncomingMessages().get(0))
                .hasMsgType(MessageTypes.Logon)
                .containsFieldWithValue(MsgSeqNum.get(), "10")
                .containsFieldWithValue(NextExpectedMsgSeqNum.get(), "2");
    }

    @Test
    void D_invalid_logon_acknowledgement_message_received() {
        fixAcceptor.start();
        fixInitiator.start();

        when(fixAcceptorApplication.validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class)))
                .thenReturn(CompletableFuture.completedFuture(Optional.of("test invalid login")));

        fixInitiatorSession.logon();

        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogout(any(FixSession.class), eq("test invalid login"), any(DecodedFixMessage.class)));
    }

    @Test
    void E_receive_any_message_other_than_a_logon_message() throws IOException {
        // Condition/Stimulus: Receive any message other than a Logon(35=A) message.
        // Expected Behavior:
        //   1. Log an error "first message not a logon".
        //   2. (Optional) Send Reject(35=3) message with RefSeqNum(45) identifying message's MsgSeqNum(34) and Text(58).
        //   3. (Optional) Send Logout(35=5) message with Text(58) referencing error condition.
        //   4. Disconnect.
        // Note: the engine additionally tolerates Logout(35=5) and ResendRequest(35=2) as a first message, so this test
        // replies to the initiator's Logon with a Heartbeat(35=0) to exercise the "not a valid first message" path.
        fixInitiator.start();
        ServerSocket srvSocket = new ServerSocket(acceptorPort);
        Socket socket = srvSocket.accept();

        fixInitiatorSession.logon();

        byte[] buffer = new byte[2048];
        int readen = socket.getInputStream().read(buffer);
        String fixMessages = new String(buffer, 0, readen);

        try {
            assertThatFixMessage(fixMessages)
                    .hasMsgType(MessageTypes.Logon)
                    .containsFieldWithValue(MsgSeqNum.get(), "1")
                    .containsFieldWithValue(SenderCompID.get(), "SENDER44_TEST")
                    .containsFieldWithValue(TargetCompID.get(), "TARGET44_TEST");

            socket.getOutputStream().write("8=FIX.4.4\u00019=72\u000135=0\u000134=1\u000149=TARGET44_TEST\u000156=SENDER44_TEST\u000152=20241208-21:04:33.926403\u000110=003\u0001".getBytes());
            socket.getOutputStream().flush();

            await().untilAsserted(() -> verify(initiatorLogger).logEvent(any(), eq("First message received is not logon or logout: %s, disconnecting"), any()));

            // the initiator sends Reject(35=3) then Logout(35=5) and disconnects; read everything until EOF
            fixMessages = new String(socket.getInputStream().readAllBytes());

            // assert each reply on its own rather than searching the whole buffer, so that every field is tied to
            // the message actually carrying it
            List<String> replies = RawFixSocketClient.splitMessages(fixMessages);
            assertThat(replies).hasSize(2);

            assertThatFixMessage(replies.get(0))
                    .hasMsgType(MessageTypes.Reject)
                    .containsFieldWithValue(MsgSeqNum.get(), "2")
                    .containsFieldWithValue(SenderCompID.get(), "SENDER44_TEST")
                    .containsFieldWithValue(TargetCompID.get(), "TARGET44_TEST")
                    .containsFieldWithValue(RefSeqNum.get(), "1")
                    .containsFieldWithValue(RefMsgType.get(), MessageTypes.Heartbeat.code())
                    .containsFieldWithValue(SessionRejectReason.get(), "11")
                    .containsFieldWithValue(Text.get(), "First received message is not logon or logout");

            assertThatFixMessage(replies.get(1))
                    .hasMsgType(MessageTypes.Logout)
                    .containsFieldWithValue(MsgSeqNum.get(), "3")
                    .containsFieldWithValue(SenderCompID.get(), "SENDER44_TEST")
                    .containsFieldWithValue(TargetCompID.get(), "TARGET44_TEST")
                    .containsFieldWithValue(Text.get(), "First received message is not logon or logout");
        } finally {
            socket.close();
            srvSocket.close();
        }
    }
}
