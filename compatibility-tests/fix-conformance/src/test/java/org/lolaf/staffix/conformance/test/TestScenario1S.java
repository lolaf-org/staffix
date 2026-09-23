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
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.fix44.fields.BeginSeqNo;
import org.lolaf.staffix.fix44.fields.EncryptMethod;
import org.lolaf.staffix.fix44.fields.HeartBtInt;
import org.lolaf.staffix.fix44.fields.Text;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.lolaf.staffix.tests.RawFixSocketClient;
import org.lolaf.staffix.tests.TestingFixMessagesStoreSettings;
import org.lolaf.staffix.tests.TestingFixSessionMessagesStore;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scenario 1S - Receive Logon message (sellside-oriented / session acceptor). Mandatory.
 * <p>
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
class TestScenario1S extends AbstractScenario {

    @Override
    FixSessionSettings.FixSessionSettingsBuilder<?, ?> getAcceptorFixSessionSettings() {
        // after logging out a rejected logon, disconnect quickly instead of waiting the default threshold for a
        // Logout ack the raw client never sends
        return super.getAcceptorFixSessionSettings().logInOrOutResponseTimeout(Duration.ofSeconds(1));
    }

    @Test
    void A_valid_logon_request_message_received() throws Exception {
        // Condition/Stimulus: Valid Logon(35=A) request message received.
        // Expected Behavior:
        //   1. Respond with Logon(35=A) acknowledgement message.
        //   2. If MsgSeqNum(34) > NextNumIn send ResendRequest(35=2).
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorConnect()) {
            // a valid Logon whose MsgSeqNum (3) is higher than the acceptor's NextNumIn (1)
            session.send(session.message(MessageTypes.Logon, 3)
                    .set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));

            // the acceptor both acknowledges the Logon and, because MsgSeqNum(34) is higher than its NextNumIn,
            // requests the missing range 1..2. staffix emits the ResendRequest before the Logon acknowledgement, so
            // the two replies are asserted together rather than in a fixed order.
            String replies = session.readMessage(DEFAULT_TIMEOUT) + session.readMessage(DEFAULT_TIMEOUT);
            assertThatFixMessage(replies)
                    .hasMsgType(MessageTypes.Logon)
                    .hasMsgType(MessageTypes.ResendRequest)
                    .containsFieldWithValue(BeginSeqNo.get(), "1");
            // the replies being on the wire does not mean the session has finished flipping to LOGGED_IN, that happens
            // on the session thread after the acknowledgement is emitted
            await().untilAsserted(() -> assertThat(fixAcceptorSession.isLoggedIn()).isTrue());
        }
    }

    @Test
    void B_logon_message_received_with_duplicate_identity() {
        // Condition/Stimulus: Logon(35=A) message received with duplicate identity (e.g. same IP, port,
        //   SenderCompID(49), TargetCompID(56), etc. as existing connection).
        // Expected Behavior:
        //   1. Generate an error condition in test output.
        //   2. Disconnect without sending a message (Note: sending a Reject or Logout(35=5) would consume a
        //      MsgSeqNum(34)).
        fixAcceptor.start();
        fixInitiator.start();

        await().until(() -> fixInitiator.isConnected());
        await().until(() -> fixInitiatorSession.isConnected());

        fixInitiatorSession.logon();

        await().until(() -> fixInitiatorSession.isLoggedIn());
        await().until(() -> fixAcceptorSession.isLoggedIn());

        // A duplicate identity is another process dialling in, so it gets an engine of its own. Two initiators for one
        // FixSessionId inside a single engine is a configuration error its session registry refuses, and is not what
        // this scenario is about.
        FixEngine duplicateIdentityEngine = fixInitiatorEngineBuilder.toBuilder()
                .clearFixMessagesStores()
                .fixMessagesStore(TestingFixMessagesStoreSettings.builder()
                        .testingFixSessionMessagesStore(new TestingFixSessionMessagesStore())
                        .build())
                .build().instance();
        duplicateIdentityEngine.start();
        FixInitiator fixInitiator2 = duplicateIdentityEngine.newInitiator(fixInitiatorBuilder.toBuilder().instanceId("second").build());
        try {
            fixInitiator2.start();

            await().until(fixInitiator2::isConnected);
            fixInitiatorSession.logon();

            await().untilAsserted(() -> verify(fixSessionEventsListener).onFixSessionRejected(any(), any(FixAcceptor.MultipleLogonException.class)));
            await().untilAsserted(() -> assertThat(fixInitiator2.isConnected()).isFalse());
        } finally {
            // the rejected initiator keeps retrying its connection (connectionRetry); stop it so it does not reconnect
            // to the next test's acceptor on the shared port and corrupt its session state
            fixInitiator2.stop(Deadline.unlimited());
            duplicateIdentityEngine.stop(Deadline.unlimited());
        }
    }

    @Test
    void C_logon_message_received_with_unauthenticated_or_nonconfigured_identity() {
        // Condition/Stimulus: Logon(35=A) message received with unauthenticated/non-configured identity (e.g. invalid
        //   SenderCompID(49), invalid TargetCompID(56), invalid source IP address, etc. vs. system configuration).
        // Expected Behavior:
        //   1. Generate an error condition in test output.
        //   2. Disconnect without sending a message (Note: sending a Reject or Logout(35=5) would consume a
        //      MsgSeqNum(34)).
        fixAcceptor.start();

        FixSessionId unknownSessionID = FixSessionId.of("test", FixRegularVersion.VERSION_44, "UNKNOWN", "UNKNOWN");

        fixInitiatorEngine.stop(Deadline.unlimited());
        fixInitiatorEngine = fixInitiatorEngineBuilder.toBuilder()
                .clearFixSessionsSettingsStores()
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(getInitiatorFixSessionSettings()
                                .fixSessionId(unknownSessionID)
                                .build()).build())
                .build()
                .instance().start();
        fixInitiatorBuilder = fixInitiatorBuilder.toBuilder().fixSessionId(unknownSessionID).build();
        fixInitiator = fixInitiatorEngine.newInitiator(fixInitiatorBuilder);

        fixInitiator.start();

        await().until(() -> fixInitiator.isConnected());
        await().until(() -> fixInitiatorSession.isConnected());

        fixInitiatorSession.logon();

        await().untilAsserted(() -> verify(fixSessionEventsListener).onFixSessionRejected(any(), any(FixAcceptor.UnknownFixSessionException.class)));
        await().untilAsserted(() -> assertThat(fixInitiator.isConnected()).isFalse());
    }

    @Test
    void D_invalid_logon_message() throws Exception {
        // Condition/Stimulus: Invalid Logon(35=A) message.
        // Expected Behavior:
        //   1. Generate an error condition in test output.
        //   2. (Optional) Send Reject(35=3) referencing the Logon.
        //   3. Send Logout(35=5) with Text(58) referencing the error.
        //   4. Disconnect.
        // The application rejects the logon (bad credentials, unexpected parameters, ...), which the acceptor turns
        // into a Logout referencing the reason.
        when(fixAcceptorApplication.validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class)))
                .thenReturn(CompletableFuture.completedFuture(Optional.of("invalid logon for test")));
        fixAcceptor.start();
        try (RawFixSocketClient.Session session = rawInitiatorConnect()) {
            session.send(session.message(MessageTypes.Logon, 1)
                    .set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));

            // a Logout referencing the error is sent, then the connection is dropped
            assertThatFixMessage(session.readMessageOfType(MessageTypes.Logout, DEFAULT_TIMEOUT))
                    .hasMsgType(MessageTypes.Logout)
                    .containsFieldWithValue(Text.get(), "invalid logon for test");
            assertThat(session.isClosedByPeer(DEFAULT_TIMEOUT)).isTrue();
        }
    }
}
