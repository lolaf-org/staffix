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
package org.lolaf.staffix.http.okhttp;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.http.HttpSender;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.http.testkit.AbstractHttpSenderTest;

import java.io.IOException;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

class OkHttpSenderTest extends AbstractHttpSenderTest {

    @Override
    protected HttpSender createSender(HttpSenderSettings settings) {
        return new OkHttpSender(settings);
    }

    /**
     * Peculiar to this client: a stock {@code OkHttpClient} leaves Nagle on and every publish then
     * stalls on the peer's 40 ms delayed-ACK timer. The stall itself cannot be seen from the receiving
     * end, so the socket the client is given is what gets asserted.
     */
    @Test
    void handsOutSocketsWithNagleDisabled() throws IOException {
        try (Socket socket = new NoDelaySocketFactory().createSocket()) {
            assertThat(socket.getTcpNoDelay()).isTrue();
        }
    }
}
