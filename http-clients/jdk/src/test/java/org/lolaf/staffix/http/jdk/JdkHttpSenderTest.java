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
package org.lolaf.staffix.http.jdk;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.http.HttpSender;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.http.testkit.AbstractHttpSenderTest;
import org.lolaf.staffix.http.testkit.StubHttpServer;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class JdkHttpSenderTest extends AbstractHttpSenderTest {

    @Override
    protected HttpSender createSender(HttpSenderSettings settings) {
        return new JdkHttpSender(settings);
    }

    /**
     * The JDK's {@code HttpResponse} exposes the status code and the headers, and nothing else of the
     * status line, so this client cannot report a reason phrase.
     */
    @Override
    protected boolean reportsReasonPhrase() {
        return false;
    }

    /**
     * Peculiar to this client: it reserves some headers for itself, and which ones varies by JDK
     * version, so a rejected header must be dropped and reported rather than failing the publish.
     */
    @Test
    void dropsAHeaderTheJdkClientReservesForItselfAndSendsTheRest() throws IOException {
        try (HttpSender configured = createSender(settings()
                .header("Host", "somewhere.else")
                .header("User-Agent", "Staffix")
                .build())) {
            configured.send(PAYLOAD, PAYLOAD.length);

            StubHttpServer.RecordedRequest request = server.lastReceived();
            assertThat(request.header("Host")).isNotEqualTo("somewhere.else");
            assertThat(request.header("User-Agent")).isEqualTo("Staffix");
        }
    }
}
