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
import org.lolaf.staffix.api.grpc.GrpcSender;
import org.lolaf.staffix.api.grpc.GrpcSenderSettings;
import org.lolaf.staffix.grpc.testkit.AbstractGrpcSenderTest;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class OkHttpGrpcSenderTest extends AbstractGrpcSenderTest {

    @Override
    protected GrpcSender createSender(GrpcSenderSettings settings) {
        return new OkHttpGrpcSender(settings);
    }

    /**
     * Peculiar to this client: gRPC has no {@code h2c} upgrade, so a cleartext call must be HTTP/2 from
     * the first byte. A client that fell back to HTTP/1.1 would still reach the stub and still look like
     * it worked, so this asserts the protocol rather than the outcome.
     */
    @Test
    void speaksHttp2FromTheFirstByteOnCleartext() throws IOException {
        sender.send(PAYLOAD, PAYLOAD.length, reply);

        assertThat(server.lastReceived().getVersion())
                .as("cleartext gRPC is HTTP/2 with prior knowledge, never an upgrade")
                .isEqualTo("HTTP/2");
    }
}
