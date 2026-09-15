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
import org.lolaf.betty.api.settings.SSLSettings;
import org.lolaf.betty.api.settings.ServerSSLSettings;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.lolaf.staffix.tests.SSLUtils;

import javax.net.ssl.SSLHandshakeException;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TestFixSSL extends AbstractFixTests {

    @Test
    void testSSLConnection() {
        fixInitiator = initiatorFixEngine.newInitiator(fixInitiatorBuilder.toBuilder()
                .instanceId("ssl")
                .sslSettings(SSLSettings.builder().sslContext(SSLUtils.getClientSSLContext()).build())
                .build());

        fixAcceptor = acceptorFixEngine.newAcceptor(fixAcceptorBuilder.toBuilder()
                .instanceId("ssl")
                .serverSSLSettings(ServerSSLSettings.builder().sslContext(SSLUtils.getServerSSLContext()).build())
                .build());

        connectFixInitiatorAndAcceptor();

        fixInitiatorSession.logon();

        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
    }

    @Test
    void testSSLConnectionWithInitiatorCertificate() {
        fixInitiator = initiatorFixEngine.newInitiator(fixInitiatorBuilder.toBuilder()
                .instanceId("ssl")
                .sslSettings(SSLSettings.builder().sslContext(SSLUtils.getClientSSLContext()).build())
                .build());

        fixAcceptor = acceptorFixEngine.newAcceptor(fixAcceptorBuilder.toBuilder()
                .instanceId("ssl")
                .serverSSLSettings(ServerSSLSettings.builder()
                        .sslContext(SSLUtils.getServerSSLContext())
                        .needClientAuth(true)
                        .wantClientAuth(true)
                        .build())
                .build());


        connectFixInitiatorAndAcceptor();

        fixInitiatorSession.logon();

        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));

        assertThat(fixAcceptor.getConnectedSessions().iterator().next().getRemoteCertificates()).isNotEmpty();
    }

    @Test
    void testInitiatorConnectRejectedWithRequiredCertificateButNoneProvided() {
        fixInitiator = initiatorFixEngine.newInitiator(fixInitiatorBuilder.toBuilder()
                .instanceId("ssl-initiator")
                .sslSettings(SSLSettings.builder().sslContext(SSLUtils.getVoidSSLContext()).build())
                .build());

        fixAcceptor = acceptorFixEngine.newAcceptor(fixAcceptorBuilder.toBuilder()
                .instanceId("ssl-acceptor")
                .serverSSLSettings(ServerSSLSettings.builder()
                        .sslContext(SSLUtils.getServerSSLContext())
                        .needClientAuth(true)
                        .wantClientAuth(true)
                        .build())
                .build());

        fixAcceptor.start();
        fixInitiator.start();

        await().untilAsserted(() -> verify(fixSessionEventsListener)
                .onFailedSSLHandshake(any(InetSocketAddress.class), any(SSLHandshakeException.class)));
    }

    @Test
    void testConnectRejectedWithNonAllowedCertificate() {
        acceptorFixEngine.stop(Deadline.unlimited());
        acceptorFixEngine = acceptorFixEngineBuilder.toBuilder()
                .clearFixSessionsSettingsStores()
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(FixSessionSettings.builder()
                                .fixSessionId(FixSessionId.of("test", FixRegularVersion.VERSION_44, "TARGET44_TEST", "SENDER44_TEST"))
                                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                                .allowedCertificate(mock(Certificate.class))
                                .build()).build())

                .build()
                .instance().start();

        fixInitiator = initiatorFixEngine.newInitiator(fixInitiatorBuilder.toBuilder()
                .instanceId("ssl")
                .sslSettings(SSLSettings.builder().sslContext(SSLUtils.getClientSSLContext()).build())
                .build());

        fixAcceptor = acceptorFixEngine.newAcceptor(fixAcceptorBuilder.toBuilder()
                .instanceId("ssl")
                .serverSSLSettings(ServerSSLSettings.builder()
                        .sslContext(SSLUtils.getServerSSLContext())
                        .needClientAuth(true)
                        .wantClientAuth(true)
                        .build())
                .build());

        fixAcceptor.start();
        fixInitiator.start();

        fixInitiator.getSession().logon();

        await().untilAsserted(() -> verify(fixSessionEventsListener).onFixSessionRejected(any(FixSessionId.class), assertArg(t ->
                assertThat(t.getClass()).isEqualTo(FixAcceptor.RejectedCertificateException.class))));
    }

}