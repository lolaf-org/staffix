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
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.fix50sp2.encoders.EmailEncoder;
import org.lolaf.staffix.fix50sp2.encoders.group.NoLinesOfTextEncoder;
import org.lolaf.staffix.fix50sp2.fields.EmailType;
import org.lolaf.staffix.fix50sp2.msg.MessageTypes;
import org.lolaf.staffix.tests.RawFixSocketClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class TestFixt extends AbstractFixTests {

    @Override
    FixSessionSettings.FixSessionSettingsBuilder getAcceptorFixSessionSettings() {
        return super.getAcceptorFixSessionSettings()
                .fixSessionId(FixSessionId.ofFIXT11("SENDER50", FixApplVerID.FIX50SP2, "SENDER50_TEST", "TARGET50_TEST"));
    }

    @Override
    FixSessionSettings.FixSessionSettingsBuilder getInitiatorFixSessionSettings() {
        return super.getInitiatorFixSessionSettings()
                .fixSessionId(FixSessionId.ofFIXT11("TARGET50", FixApplVerID.FIX50SP2, "TARGET50_TEST", "SENDER50_TEST"));
    }

    @Override
    List<MessageType> getDecoders(ConnectorType connectorType) {
        return List.of(MessageTypes.Email);
    }

    @Test
    void testSimpleLogon() {
        logonClient();
    }

    @Test
    void testUnsupportedApplVerIdIsRejected() throws Exception {
        startFixAcceptor();

        byte[] logon = RawFixSocketClient.fixMessage("FIXT.1.1",
                "35=A", "34=1", "49=TARGET50_TEST", "56=SENDER50_TEST",
                "52=20260620-12:00:00.000", "98=0", "108=30", "1137=6");

        String reply = RawFixSocketClient.exchange(acceptorPort, logon, Duration.ofSeconds(5));

        assertThat(reply)
                .contains(CoreFields.FIELD_SEPARATOR + "35=3" + CoreFields.FIELD_SEPARATOR)        // session level Reject
                .contains(CoreFields.FIELD_SEPARATOR + "373=18" + CoreFields.FIELD_SEPARATOR)      // INVALID_UNSUPPORTED_APPL_VER
                .contains(CoreFields.FIELD_SEPARATOR + "371=1137" + CoreFields.FIELD_SEPARATOR)    // RefTagID = DefaultApplVerID
                .contains(CoreFields.FIELD_SEPARATOR + "372=A" + CoreFields.FIELD_SEPARATOR);      // RefMsgType = Logon
    }

    @Test
    void testLogout() {
        logonClient();

        fixInitiatorSession.logoutPermanently("test logout");

        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogout(any(FixSession.class), eq("test logout"), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogout(any(FixSession.class), eq("test logout"), any(DecodedFixMessage.class)));
    }

    @Test
    void testLogoutFromAcceptor() {
        logonClient();

        fixAcceptor.stop();

        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogout(any(FixSession.class), eq("FIX server stop"), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogout(any(FixSession.class), eq("FIX server stop"), any(DecodedFixMessage.class)));
    }

    @Test
    void testInitiatorSendMessage() {
        logonClient();

        fixInitiatorSession.send(encodeFixtTestMessage(fixInitiatorSession), null);

        await().untilAsserted(() -> verify(getDecoder(MessageTypes.Email, ConnectorType.ACCEPTOR)).onBegin(anyLong(), any()));
        await().untilAsserted(() -> verify(getDecoder(MessageTypes.Email, ConnectorType.ACCEPTOR)).onDecoded(any(), anyBoolean(), anyBoolean()));
    }

    @Test
    void testAcceptorSendMessage() {
        logonClient();

        fixAcceptorSession.send(encodeFixtTestMessage(fixAcceptorSession), null);

        await().untilAsserted(() -> verify(getDecoder(MessageTypes.Email, ConnectorType.INITIATOR)).onBegin(anyLong(), any()));
        await().untilAsserted(() -> verify(getDecoder(MessageTypes.Email, ConnectorType.INITIATOR)).onDecoded(any(), anyBoolean(), anyBoolean()));
    }

    private FixMessageEncoder<?> encodeFixtTestMessage(FixSession fixSession) {
        EmailEncoder emailEncoder = fixSession.newEncoder(EmailEncoder.class);
        emailEncoder.begin().setEmailType(EmailType.EmailTypeValues.NEW).setEmailThreadID("test thread id").setSubject("test subject");
        NoLinesOfTextEncoder lotEncoder = emailEncoder.addNoLinesOfText(2);
        for (int j = 0; j < 2; j++) {
            lotEncoder.setText("test text");
            // EncodedTextLen is defined in both the FIXT transport and the application dictionary; the application
            // dictionary singleton wins in the FIXT fields registry so encode/decode agree on the same field instance.
            lotEncoder.setEncodedTextLen("encoded text".length());
            lotEncoder.setEncodedText("encoded text");
        }
        return emailEncoder;
    }

}