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
package org.lolaf.staffix.impl;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.fix44.fields.Subject;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.impl.session.FixSessionImpl;
import org.lolaf.staffix.tests.FixMessageFields;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TestFixSessionAdminApiCalls extends AbstractFixTests {


    private static final String PASTED_EMAIL = "8=FIX.4.4|9=192|35=C|34=6|49=WHOEVER|56=WHOMEVER|"
            + "52=20241027-21:48:15.516767|94=0|164=admin thread|147=admin subject|33=1|58=sent by the admin API|10=020|";

    @Test
    void aMessageHandedOverAsAStringIsSentWithTheSessionsOwnFrame() {
        logonClient();
        long nextOutgoingSeqNum = getFixMessagesStore(ConnectorType.ACCEPTOR).getOutgoingSeqNum();

        getFixSessionImpl(ConnectorType.ACCEPTOR).adminSendFixMessage(PASTED_EMAIL, '|', false);

        assertMessageReceived(decodedInitiatorMessages, Subject.get(), "admin subject");
        await().untilAsserted(() -> assertThat(sentEmail()).isPresent());
        String sent = sentEmail().orElseThrow();

        assertThat(FixMessageFields.valuesOf(sent, CoreFields.MESSAGE_SEQ_NUM))
                .as("the session's own sequence number, not the 34=6 of the pasted message: %s", sent)
                .containsExactly(String.valueOf(nextOutgoingSeqNum));
        assertThat(FixMessageFields.valuesOf(sent, CoreFields.SENDING_TIME))
                .as("a SendingTime of its own, not the 2024 one: %s", sent)
                .noneMatch(sendingTime -> sendingTime.startsWith("2024"));
        assertThat(FixMessageFields.valuesOf(sent, CoreFields.SENDER_COMP_ID))
                .as("this session's comp ids, not the ones the message was copied from: %s", sent)
                .containsExactly("TARGET44_TEST");
        assertThat(FixMessageFields.valuesOf(sent, 164))
                .as("the body is the one that was handed over")
                .containsExactly("admin thread");

        // the peer answering nothing is the proof that BodyLength(9) and CheckSum(10) were computed again: a message
        // whose frame did not add up would have been disregarded as garbled, or rejected
        assertThat(getLogger(ConnectorType.INITIATOR).getOutgoingMessages())
                .as("the initiator rejected nothing")
                .noneMatch(message -> FixMessageFields.hasFieldWithValue(message, CoreFields.MESSAGE_TYPE, CoreMessageType.REJECT));
    }

    /**
     * The same message going out again: it says so on the wire with PossDupFlag(43)=Y and carries the time it was first
     * sent in OrigSendingTime(122), while its SendingTime(52) is the one this session stamps now.
     */
    @Test
    void aMessageSentAsAPossibleDuplicateSaysSoOnTheWire() {
        logonClient();

        getFixSessionImpl(ConnectorType.ACCEPTOR).adminSendFixMessage(PASTED_EMAIL, '|', true);

        await().untilAsserted(() -> assertThat(sentEmail()).isPresent());
        String sent = sentEmail().orElseThrow();
        assertThat(FixMessageFields.valuesOf(sent, CoreFields.POSS_DUP_FLAG))
                .as("PossDupFlag(43): %s", sent)
                .containsExactly("Y");
        assertThat(FixMessageFields.valuesOf(sent, CoreFields.ORIG_SENDING_TIME))
                .as("OrigSendingTime(122), the SendingTime the message was written with: %s", sent)
                .allMatch(origSendingTime -> origSendingTime.startsWith("20241027-21:48:15"));
        assertThat(FixMessageFields.valuesOf(sent, CoreFields.SENDING_TIME))
                .as("SendingTime(52), stamped now: %s", sent)
                .noneMatch(sendingTime -> sendingTime.startsWith("2024"));
    }

    @Test
    void aMessageTheDictionaryDoesNotDescribeIsRefusedRatherThanSent() {
        logonClient();

        // Price(44) is not a field an Email(35=C) carries
        assertThatThrownBy(() -> getFixSessionImpl(ConnectorType.ACCEPTOR)
                .adminSendFixMessage(PASTED_EMAIL.replace("94=0|", "94=0|44=1.25|"), '|', false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tag 44");

        assertThat(getLogger(ConnectorType.ACCEPTOR).getOutgoingMessages())
                .as("nothing went out")
                .noneMatch(message -> FixMessageFields.hasFieldWithValue(message, CoreFields.MESSAGE_TYPE, MessageTypes.Email.code()));
    }

    @Test
    void aMessageOnASessionThatIsNotLoggedInIsRefused() {
        startFixAcceptor();

        assertThatThrownBy(() -> getFixSessionImpl(ConnectorType.ACCEPTOR).adminSendFixMessage(PASTED_EMAIL, '|', false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not logged in");
    }

    private Optional<String> sentEmail() {
        return getLogger(ConnectorType.ACCEPTOR).getOutgoingMessages().stream()
                .filter(message -> FixMessageFields.hasFieldWithValue(message, CoreFields.MESSAGE_TYPE, MessageTypes.Email.code()))
                .findFirst();
    }

    @Test
    void testLogoutMethodAllowsSessionToBeLoggedInAgain() {

        connectFixInitiatorAndAcceptor();

        fixInitiatorSession.logon();

        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogon(any(), any()));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(), any()));

        clearInvocations(fixInitiatorApplication);
        clearInvocations(fixAcceptorApplication);

        fixAcceptorSession.logout("test logout");

        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogout(any(), eq("test logout"), any()));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogout(any(), eq("test logout"), any()));

        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogon(any(), any()));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(), any()));
    }

    @Test
    void testLogonRejectedIfAcceptorDoesNotAcceptCurrentlyConnections() {

        startFixAcceptor();

        fixAcceptorSession.logoutPermanently(null);

        connectFixInitiatorAndAcceptor();
        rejectedLogonEndsDialling();

        fixInitiatorSession.logon();

        awaitSingleRejectedLogon("Logon rejected, session not setup to accept login requests for now");
    }

    @Test
    void testLogonRejectedIfAcceptorDoesNotAcceptCurrentlyLogon() {

        startFixAcceptor();

        fixAcceptorSession.disconnect("permanent disconnect");

        connectFixInitiatorAndAcceptor();

        fixInitiatorSession.logon();

        // at least once, not exactly once: an acceptor that refuses the connection outright never settles the
        // initiator, which dials again every connectionRetry and is dropped again. Pinning the run to one attempt as
        // the rejection tests do is not on either - there is no rejection callback to end the dialling from, and
        // ending it on the disconnection would race the logon that re-arms it. What the scenario is about is that
        // the connection ends with nothing of the session layer having run, which the negative checks below state
        await().untilAsserted(() -> verify(fixInitiatorApplication, atLeastOnce()).onDisconnected(any()));
        verify(fixInitiatorApplication, never()).onLogon(any(), any());
        verify(fixInitiatorApplication, never()).onLogout(any(), any(), any());
        verify(fixAcceptorApplication, never()).validateLogon(any(), any(), any());
        verify(fixAcceptorApplication, never()).onLogon(any(), any());
    }

    /**
     * Moving a sequence number is a write to the session's message store, and a stopped session has released it - a
     * file store has unmapped the memory the write would have gone to. So the call is refused rather than served.
     */
    @Test
    void testSettingSequenceNumbersOnAStoppedSessionIsRefused() {
        logonClient();
        FixSessionImpl initiatorSession = getFixSessionImpl(ConnectorType.INITIATOR);

        fixInitiator.stop();

        assertThatThrownBy(() -> initiatorSession.adminSetOutgoingSeqNum(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resources have been released");
        assertThatThrownBy(() -> initiatorSession.adminSetIncomingSeqNum(10))
                .isInstanceOf(IllegalStateException.class);
    }

}