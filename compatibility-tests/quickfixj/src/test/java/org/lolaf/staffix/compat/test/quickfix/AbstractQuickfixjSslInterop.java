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
package org.lolaf.staffix.compat.test.quickfix;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The interoperability scenarios that need TLS: staffix and QuickFIX/J negotiating a mutually authenticated
 * connection with the shared test certificates, in both directions.
 * <p>
 * Deliberately a handful rather than the whole of {@link AbstractQuickfixjInterop}. TLS is a transport concern and
 * the FIX session layer above it is the same code either way, so running every scenario again would double the
 * module for almost nothing. What is here is the logon, traffic each way, a retransmission, and a reconnect - the
 * last because re-establishing the connection is the one place where TLS and session recovery genuinely meet, the
 * peer having to handshake again before the Logon can go out.
 * <p>
 * {@link #testLogonOverTls} is the guard that this is really TLS: staffix asking its session for the certificates
 * the peer presented is what separates a negotiated connection from one where the knobs did nothing and both ends
 * spoke plaintext to each other quite happily.
 */
abstract class AbstractQuickfixjSslInterop extends AbstractQuickfixjHarness {

    @Override
    boolean useSsl() {
        return true;
    }

    @Test
    void testLogonOverTls() {
        logon();

        assertThat(staffixSession.isLoggedIn()).isTrue();
        await().untilAsserted(() -> assertThat(quickfixConnector.isLoggedOn()).isTrue());

        // the peer authenticated itself, which a plaintext connection could not have produced
        assertThat(staffixSession.getRemoteCertificates())
                .as("the peer must have presented the certificate mutual TLS asks of it")
                .isNotEmpty();
    }

    @Test
    void testSendApplicationMessageEachWayOverTls() {
        logon();

        sendEmailFromStaffix(1);
        assertQuickfixReceivedEmail(1);

        sendEmailFromQuickfix(2);
        assertStaffixReceivedEmail(2);
    }

    @Test
    void testRetransmissionOverTls() {
        logon();

        for (int i = 1; i <= MESSAGES_BEFORE_GAP; i++) {
            sendEmailFromStaffix(i);
        }
        assertQuickfixReceivedEmail(MESSAGES_BEFORE_GAP);

        makeQuickfixExpectMissedMessages(MISSED_MESSAGES);
        quickfixReceivedEmails.clear();
        sendEmailFromStaffix(MESSAGES_BEFORE_GAP + 1);

        // the retransmission crosses the TLS record layer like anything else, PossDupFlag(43)=Y and all
        for (int i = MESSAGES_BEFORE_GAP - MISSED_MESSAGES + 1; i <= MESSAGES_BEFORE_GAP; i++) {
            assertQuickfixReceivedEmail(i);
        }
        await().untilAsserted(() -> assertThat(quickfixReceivedEmails)
                .filteredOn(email -> !email.getEmailThreadId().equals(emailThreadId(MESSAGES_BEFORE_GAP + 1)))
                .isNotEmpty()
                .allMatch(ReceivedEmail::isPossDup));
    }

    @Test
    void testReconnectHandshakesAgain() {
        logon();

        sendEmailFromStaffix(1);
        assertQuickfixReceivedEmail(1);

        dropConnection();

        // coming back means a second handshake, not just a second socket: the session is only usable again if it
        // succeeded, and the peer certificate has to be there again on the other side of it
        awaitLoggedBackOn();

        assertThat(staffixSession.getRemoteCertificates())
                .as("the reconnect must have negotiated TLS again rather than falling back to plaintext")
                .isNotEmpty();

        sendEmailFromStaffix(2);
        assertQuickfixReceivedEmail(2);
    }
}
