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
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.fix44.encoders.group.NoLinesOfTextEncoder;
import org.lolaf.staffix.fix44.fields.EmailType;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/**
 * Exercises FIXT.1.1 transport with a FIX 4.4 <em>combined</em> application dictionary, i.e. one that still declares the
 * session-layer messages (Logon, Heartbeat, ...) and their fields. This is the same shape as the JVM warmup dictionary
 * and used to break: the application dictionary Logon shadowed the FIXT transport Logon and the FIXT-only
 * DefaultApplVerID (1137) field collided, in the index-addressed received-field collection, with an application
 * dictionary field, so the acceptor spuriously rejected the logon with "EncryptMethod (98) not found".
 */
class TestFixtWithFix44Dictionary extends AbstractFixTests {

    @Override
    FixSessionSettings.FixSessionSettingsBuilder getAcceptorFixSessionSettings() {
        return super.getAcceptorFixSessionSettings()
                .fixSessionId(FixSessionId.ofFIXT11("test", FixApplVerID.FIX44, "TARGET44_TEST", "SENDER44_TEST"));
    }

    @Override
    FixSessionSettings.FixSessionSettingsBuilder getInitiatorFixSessionSettings() {
        return super.getInitiatorFixSessionSettings()
                .fixSessionId(FixSessionId.ofFIXT11("test", FixApplVerID.FIX44, "SENDER44_TEST", "TARGET44_TEST"));
    }

    @Test
    void testSimpleLogon() {
        logonClient();
    }

    @Test
    void testInitiatorSendMessage() {
        logonClient();

        fixInitiatorSession.send(encodeFixtTestMessage(fixInitiatorSession), null);

        await().untilAsserted(() -> verify(getDecoder(testMessageType, ConnectorType.ACCEPTOR)).onBegin(anyLong(), any()));
        await().untilAsserted(() -> verify(getDecoder(testMessageType, ConnectorType.ACCEPTOR)).onDecoded(any(), anyBoolean(), anyBoolean()));
    }

    @Test
    void testAcceptorSendMessage() {
        logonClient();

        fixAcceptorSession.send(encodeFixtTestMessage(fixAcceptorSession), null);

        await().untilAsserted(() -> verify(getDecoder(testMessageType, ConnectorType.INITIATOR)).onBegin(anyLong(), any()));
        await().untilAsserted(() -> verify(getDecoder(testMessageType, ConnectorType.INITIATOR)).onDecoded(any(), anyBoolean(), anyBoolean()));
    }

    @Test
    void testLogout() {
        logonClient();

        fixInitiatorSession.logoutPermanently("test logout");

        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogout(any(FixSession.class), any(String.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogout(any(FixSession.class), any(String.class), any(DecodedFixMessage.class)));
    }

    private FixMessageEncoder<?> encodeFixtTestMessage(FixSession fixSession) {
        EmailEncoder emailEncoder = fixSession.newEncoder(EmailEncoder.class);
        emailEncoder.begin().setEmailType(EmailType.EmailTypeValues.NEW).setEmailThreadID("test thread id").setSubject("test subject");
        NoLinesOfTextEncoder lotEncoder = emailEncoder.addNoLinesOfText(2);
        for (int j = 0; j < 2; j++) {
            lotEncoder.setText("test text");
            lotEncoder.setEncodedTextLen("encoded text".length());
            lotEncoder.setEncodedText("encoded text");
        }
        return emailEncoder;
    }
}
