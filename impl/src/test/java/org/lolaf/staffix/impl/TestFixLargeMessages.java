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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.betty.api.settings.SSLSettings;
import org.lolaf.betty.api.settings.ServerSSLSettings;
import org.lolaf.staffix.api.FixAcceptorBuilder;
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.fix44.encoders.group.NoLinesOfTextEncoder;
import org.lolaf.staffix.fix44.fields.Subject;
import org.lolaf.staffix.tests.SSLUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Messages larger than betty's 8 KB read buffer, which it grows up to {@code IOSettings.maxReadBufferSize}.
 */
class TestFixLargeMessages extends AbstractFixTests {

    private static final String LARGE_SUBJECT = "large-message";
    private static final int LINES_OF_TEXT = 64;
    private static final String LINE_OF_TEXT = "x".repeat(1000);

    @ParameterizedTest(name = "tls={0}")
    @ValueSource(booleans = {false, true})
    void aMessageLargerThanTheReadBufferIsReceivedWhole(boolean tls) {
        useConnectors(tls, fixAcceptorBuilder);
        logonClient();

        sendLargeEmail();
        sendEmail("after-the-large-one");

        assertMessageReceived(decodedAcceptorMessages, Subject.get(), LARGE_SUBJECT);
        assertMessageReceived(decodedAcceptorMessages, Subject.get(), "after-the-large-one");
    }

    @ParameterizedTest(name = "tls={0}")
    @ValueSource(booleans = {false, true})
    void aMessagePastTheMaximumDisconnectsTheSessionWithAnEvent(boolean tls) {
        useConnectors(tls, fixAcceptorBuilder.toBuilder()
                .ioSettings(IOSettings.builder().maxReadBufferSize(32 * 1024).build())
                .build());
        logonClient();

        sendLargeEmail();

        await().untilAsserted(() -> assertThat(acceptorLogger.getEvents())
                .anyMatch(event -> event.contains("Message larger than the read buffer maximum of 32768 bytes")));
        await().untilAsserted(() -> assertThat(fixAcceptorSession.isConnected()).isFalse());
    }

    private void useConnectors(boolean tls, FixAcceptorBuilder acceptorBuilder) {
        if (!tls) {
            fixAcceptor = acceptorFixEngine.newAcceptor(acceptorBuilder.toBuilder().instanceId("plain").build());
            return;
        }
        fixInitiator = initiatorFixEngine.newInitiator(fixInitiatorBuilder.toBuilder()
                .instanceId("tls")
                .sslSettings(SSLSettings.builder().sslContext(SSLUtils.getClientSSLContext()).build())
                .build());
        fixAcceptor = acceptorFixEngine.newAcceptor(acceptorBuilder.toBuilder()
                .instanceId("tls")
                .serverSSLSettings(ServerSSLSettings.builder().sslContext(SSLUtils.getServerSSLContext()).build())
                .build());
    }

    /**
     * About 64 KB in fields under the parser's own limit on a single field value.
     */
    private void sendLargeEmail() {
        EmailEncoder encoder = fixInitiatorSession.newEncoder(EmailEncoder.class);
        encoder.begin().setSubject(LARGE_SUBJECT);
        NoLinesOfTextEncoder lines = encoder.addNoLinesOfText(LINES_OF_TEXT);
        for (int i = 0; i < LINES_OF_TEXT; i++) {
            lines.setText(LINE_OF_TEXT);
        }
        fixInitiatorSession.send(encoder, null);
    }

    private void sendEmail(String subject) {
        EmailEncoder encoder = fixInitiatorSession.newEncoder(EmailEncoder.class);
        encoder.begin().setSubject(subject);
        fixInitiatorSession.send(encoder, null);
    }
}
