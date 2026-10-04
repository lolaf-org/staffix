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
package org.lolaf.staffix.admin.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.admin.AdminApiExporterSettings;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpAdminApiTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private final HttpAdminApi exporter = new HttpAdminApi(HttpAdminApiSettings.builder().bindAddress("127.0.0.1").port(0).build());

    private static AdminApi adminApi() {
        AdminApi adminApi = mock(AdminApi.class);
        when(adminApi.getInstanceId()).thenReturn("alpha-engine");
        return adminApi;
    }

    private int get(InetSocketAddress address) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + address.getPort() + "/"))
                .timeout(Duration.ofSeconds(5))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @AfterEach
    void tearDown() {
        exporter.shutdown(Deadline.of(Duration.ofSeconds(1)));
    }

    @Test
    void servesOnExportAndStopsOnShutdown() throws Exception {
        exporter.export(adminApi());
        InetSocketAddress address = exporter.getAddress();

        assertThat(address.getPort()).isPositive();
        assertThat(get(address)).isEqualTo(404);

        exporter.shutdown(Deadline.of(Duration.ofSeconds(1)));

        assertThat(exporter.getAddress()).isNull();
        assertThatThrownBy(() -> get(address)).isInstanceOf(ConnectException.class);
    }

    @Test
    void servesHttpsWithAnSslContext() throws Exception {
        HttpAdminApi https = new HttpAdminApi(HttpAdminApiSettings.builder()
                .bindAddress("127.0.0.1")
                .port(0)
                .sslContext(TestTls.server())
                .build());
        https.export(adminApi());
        try {
            HttpClient trustingClient = HttpClient.newBuilder().sslContext(TestTls.trusting()).build();
            URI openApi = URI.create("https://127.0.0.1:" + https.getAddress().getPort() + HttpAdminServer.OPENAPI_PATH);

            HttpResponse<Void> response = trustingClient.send(HttpRequest.newBuilder(openApi).build(),
                    HttpResponse.BodyHandlers.discarding());

            assertThat(response.statusCode()).isEqualTo(200);
        } finally {
            https.shutdown(Deadline.of(Duration.ofSeconds(1)));
        }
    }

    @Test
    void aPortInUseLeavesTheEngineRunningWithoutTheApi() throws IOException {
        try (ServerSocket taken = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            HttpAdminApi clashing = new HttpAdminApi(HttpAdminApiSettings.builder()
                    .bindAddress("127.0.0.1")
                    .port(taken.getLocalPort())
                    .build());

            clashing.export(adminApi());

            assertThat(clashing.getAddress()).isNull();
        }
    }

    @Test
    void theFactoryIsFoundThroughTheSpi() {
        AdminApiExporterSettings settings = HttpAdminApiSettings.builder().port(0).build();

        assertThat(settings.instance()).isInstanceOf(HttpAdminApi.class);
    }
}
