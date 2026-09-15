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
package org.lolaf.staffix.http.jetty;

import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.http.HttpSender;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.api.http.HttpVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That {@link HttpVersion} on {@link JettyHttpSender} reaches the wire.
 *
 * <p>The assertion is made by a server that speaks <strong>only</strong> cleartext HTTP/2 with prior
 * knowledge. A client that sent HTTP/1.1 cannot be answered by it at all, so a successful publish is
 * itself the proof that HTTP/2 was spoken - which is worth more than reading a version back off a
 * response object, since that is what the client believes rather than what it put on the wire.
 *
 * <p>The default is asserted from the same end: it must fail here, because {@link HttpVersion#HTTP_1_1}
 * is meant to be a pin rather than a preference.
 */
class JettyHttpSenderHttpVersionTest {

    private static final byte[] PAYLOAD = "payload".getBytes(StandardCharsets.UTF_8);

    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.setProtocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE));
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse.Builder().code(200).build();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    @Test
    void publishesOverHttp2WhenAskedTo() throws Exception {
        try (HttpSender sender = new JettyHttpSender(settings(), null, HttpVersion.HTTP_2)) {
            assertThat(sender.send(PAYLOAD, 0, PAYLOAD.length, null)).isEqualTo(200);
        }
    }

    @Test
    void doesNotSpeakHttp2ByDefault() throws Exception {
        try (HttpSender sender = new JettyHttpSender(settings())) {
            assertThatThrownBy(() -> sender.send(PAYLOAD, 0, PAYLOAD.length, null))
                    .isInstanceOf(IOException.class);
        }
    }

    private HttpSenderSettings settings() {
        return HttpSenderSettings.builder()
                .endpointUrl("http://127.0.0.1:" + server.getPort())
                // Short, so the negative case fails fast rather than sitting on the default timeout.
                .requestTimeout(Duration.ofSeconds(2))
                .build();
    }
}
