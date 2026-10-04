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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.admin.AdminApi.ResetFixSessionMode;
import org.lolaf.staffix.api.admin.FixInitiatorTargets;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionId.FixSessionIdBuilder;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminApiEndpointsTest {

    private static final String TOKEN = "alpha-token";
    private static final FixSessionId TRADING = id("trading");
    private static final FixSessionId TRADING_DRP = id("trading-drp");

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<HttpAdminApi> exporters = new ArrayList<>();
    private AdminApi adminApi;
    private int port;

    private static FixSessionId id(String name) {
        return FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionIdBuilder.builder()
                .group("alpha").name(name).senderCompID("US").targetCompID("ALPHA").build());
    }

    private static AdminApi adminApi(String instanceId) {
        FixSession trading = mock(FixSession.class);
        FixSessionSettings settings = mock(FixSessionSettings.class);
        when(settings.getFixSessionType()).thenReturn(FixSession.FixSessionType.INITIATOR);
        when(trading.getFixSessionId()).thenReturn(TRADING);
        when(trading.getFixSessionSettings()).thenReturn(settings);
        FixInitiatorTargets targets = FixInitiatorTargets.builder()
                .instanceId("alpha-initiator")
                .activeFixSessionId(TRADING)
                .target(FixInitiatorTarget.builder().fixSessionId(TRADING).build())
                .target(FixInitiatorTarget.builder().fixSessionId(TRADING_DRP).build())
                .build();
        AdminApi adminApi = mock(AdminApi.class);
        when(adminApi.getInstanceId()).thenReturn(instanceId);
        when(adminApi.getManagedFixSessions()).thenReturn(List.of(trading));
        when(adminApi.getInitiatorsTargets()).thenReturn(List.of(targets));
        return adminApi;
    }

    private HttpAdminApi export(AdminApi adminApi, int port, String token) {
        HttpAdminApi exporter = new HttpAdminApi(HttpAdminApiSettings.builder()
                .bindAddress("127.0.0.1").port(port).apiToken(token).build());
        exporters.add(exporter);
        exporter.export(adminApi);
        return exporter;
    }

    @BeforeEach
    void setUp() {
        adminApi = adminApi("alpha-engine");
        port = export(adminApi, 0, TOKEN).getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        exporters.forEach(exporter -> exporter.shutdown(Deadline.of(Duration.ofSeconds(1))));
    }

    private HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        return call(method, path, body, TOKEN);
    }

    @Test
    void aRequestWithoutTheTokenIsRefused() throws Exception {
        assertThat(call("GET", "/engines/alpha-engine/status", null, null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/engines/alpha-engine/sessions/alpha/trading/logon", null, "wrong").statusCode()).isEqualTo(401);
        verify(adminApi, never()).logonSession(any());
    }

    @Test
    void statusListsTheSessions() throws Exception {
        HttpResponse<String> response = call("GET", "/engines/alpha-engine/status", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(response.body()).contains("\"instanceId\":\"alpha-engine\"", "\"name\":\"trading\"");
    }

    @Test
    void sessionsAreServedWithTheVersionStatusReportsAsTheirETag() throws Exception {
        HttpResponse<String> sessions = call("GET", "/engines/alpha-engine/sessions", null);

        assertThat(sessions.statusCode()).isEqualTo(200);
        assertThat(sessions.body()).contains("\"name\":\"trading\"", "\"configs\":[{\"name\":\"trading\"");
        String etag = sessions.headers().firstValue("ETag").orElseThrow();
        assertThat(call("GET", "/engines/alpha-engine/status", null).body()).contains("\"sessionsVersion\":" + etag);

        HttpResponse<String> unchanged = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/engines/alpha-engine/sessions"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("If-None-Match", etag)
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unchanged.statusCode()).isEqualTo(304);
    }

    @Test
    void sessionOperationsReachTheAdminApi() throws Exception {
        assertThat(call("POST", "/engines/alpha-engine/sessions/alpha/trading/logon", null).statusCode()).isEqualTo(204);
        assertThat(call("POST", "/engines/alpha-engine/sessions/alpha/trading/logout", null).statusCode()).isEqualTo(204);
        assertThat(call("POST", "/engines/alpha-engine/sessions/alpha/trading/reset", "{\"mode\":\"RESET_SEQUENCE\"}").statusCode()).isEqualTo(204);
        assertThat(call("PUT", "/engines/alpha-engine/sessions/alpha/trading/seqnums", "{\"outgoing\":42}").statusCode()).isEqualTo(204);
        assertThat(call("POST", "/engines/alpha-engine/sessions/alpha/trading/messages",
                "{\"message\":\"35=B|148=hello|\",\"separator\":\"|\",\"possDup\":true}").statusCode()).isEqualTo(204);
        assertThat(call("POST", "/engines/alpha-engine/sessions/alpha/trading-drp/activate", null).statusCode()).isEqualTo(204);

        verify(adminApi).logonSession(TRADING);
        verify(adminApi).logoutSession(TRADING);
        verify(adminApi).resetSession(TRADING, ResetFixSessionMode.RESET_SEQUENCE);
        verify(adminApi).setOutgoingSeqNum(TRADING, 42);
        verify(adminApi, never()).setIncomingSeqNum(any(), anyLong());
        verify(adminApi).sendFixMessage(TRADING, "35=B|148=hello|", '|', true);
        verify(adminApi).switchInitiatorSession(TRADING_DRP);
    }

    @Test
    void settingsOfTheRunningConfigAreJson() throws Exception {
        HttpResponse<String> response = call("GET", "/engines/alpha-engine/sessions/alpha/trading/settings", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(call("GET", "/engines/alpha-engine/sessions/alpha/trading-drp/settings", null).statusCode()).isEqualTo(404);
    }

    @Test
    void aDictionaryIsServedWithItsHashAsETag() throws Exception {
        HttpResponse<String> dictionary = call("GET", "/engines/alpha-engine/dictionaries/alpha-FIX.4.4", null);

        assertThat(dictionary.statusCode()).isEqualTo(200);
        assertThat(dictionary.headers().firstValue("Content-Type")).hasValue("application/xml");
        assertThat(dictionary.body()).startsWith("<fix major=\"4\"");
        String etag = dictionary.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).isEqualTo("\"" + Dictionaries.get("alpha-FIX.4.4").orElseThrow().hash + "\"");

        HttpResponse<String> unchanged = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/engines/alpha-engine/dictionaries/alpha-FIX.4.4"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("If-None-Match", etag)
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unchanged.statusCode()).isEqualTo(304);
        assertThat(call("GET", "/engines/alpha-engine/dictionaries/beta-FIX.4.4", null).statusCode()).isEqualTo(404);
    }

    @Test
    void errorsAreProblemDetails() throws Exception {
        doThrow(new IllegalStateException("trading is not logged in"))
                .when(adminApi).sendFixMessage(any(), anyString(), anyChar(), anyBoolean());

        HttpResponse<String> refused = call("POST", "/engines/alpha-engine/sessions/alpha/trading/messages",
                "{\"message\":\"35=B|\",\"separator\":\"|\"}");
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(refused.body()).isEqualTo("{\"status\":409,\"detail\":\"trading is not logged in\"}");

        assertThat(call("POST", "/engines/alpha-engine/sessions/beta/trading/logon", null).statusCode()).isEqualTo(404);
        assertThat(call("POST", "/engines/alpha-engine/sessions/alpha/trading-drp/logon", null).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/engines/alpha-engine/sessions/alpha/trading/logon", null).statusCode()).isEqualTo(405);
        assertThat(call("POST", "/engines/alpha-engine/sessions/alpha/trading/reset", "{\"mode\":\"NOPE\"}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/engines/alpha-engine/sessions/alpha/trading/reset", "{}").statusCode()).isEqualTo(400);
        assertThat(call("GET", "/engines/gamma-engine/status", null).statusCode()).isEqualTo(404);
    }

    @Test
    void enginesOfOneJvmShareAPortEachWithItsOwnToken() throws Exception {
        int sharedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            sharedPort = probe.getLocalPort();
        }
        AdminApi alpha = adminApi("alpha-engine");
        AdminApi beta = adminApi("beta-engine");
        export(alpha, sharedPort, "alpha-token");
        HttpAdminApi betaExporter = export(beta, sharedPort, "beta-token");
        port = sharedPort;

        assertThat(call("POST", "/engines/beta-engine/sessions/alpha/trading/logon", null, "beta-token").statusCode()).isEqualTo(204);
        assertThat(call("POST", "/engines/beta-engine/sessions/alpha/trading/logon", null, "alpha-token").statusCode()).isEqualTo(401);
        verify(beta).logonSession(TRADING);
        verify(alpha, never()).logonSession(any());

        betaExporter.shutdown(Deadline.of(Duration.ofSeconds(1)));
        assertThat(call("GET", "/engines/beta-engine/status", null, "beta-token").statusCode()).isEqualTo(404);
        assertThat(call("GET", "/engines/alpha-engine/status", null, "alpha-token").statusCode()).isEqualTo(200);
    }

    @Test
    void aSecondEngineWithTheSameInstanceIdIsNotServed() throws Exception {
        int sharedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            sharedPort = probe.getLocalPort();
        }
        export(adminApi("alpha-engine"), sharedPort, "first");
        HttpAdminApi duplicate = export(adminApi("alpha-engine"), sharedPort, "second");
        port = sharedPort;

        assertThat(duplicate.getAddress()).isNull();
        assertThat(call("GET", "/engines/alpha-engine/status", null, "first").statusCode()).isEqualTo(200);
    }

    @Test
    void aGeneratedTokenIsUnguessable() {
        HttpAdminApi generated = export(adminApi("beta-engine"), 0, null);

        assertThat(generated.getApiToken()).hasSize(43).isNotEqualTo(export(adminApi("gamma-engine"), 0, null).getApiToken());
    }
}
