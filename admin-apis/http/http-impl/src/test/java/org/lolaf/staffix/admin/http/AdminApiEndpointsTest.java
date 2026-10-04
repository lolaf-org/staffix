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
import org.lolaf.staffix.api.admin.FixAcceptorSessions;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminApiEndpointsTest {

    private static final String TOKEN = "alpha-token";
    private static final FixSessionId TRADING = id("trading");
    private static final FixSessionId TRADING_DRP = id("trading-drp");
    private static final FixSessionId DROP_COPY = id("drop-copy");

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<HttpAdminApi> exporters = new ArrayList<>();
    private AdminApi adminApi;
    private int port;

    private static FixSessionId id(String name) {
        return FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionIdBuilder.builder()
                .group("alpha").name(name).senderCompID("US").targetCompID("ALPHA").build());
    }

    private static AdminApi adminApi(String instanceId) {
        AdminApi adminApi = mock(AdminApi.class);
        when(adminApi.getInstanceId()).thenReturn(instanceId);
        when(adminApi.getAcceptorsSessions()).thenReturn(List.of(FixAcceptorSessions.builder()
                .instanceId("alpha-acceptor").fixSessionId(DROP_COPY).build()));
        running(adminApi, TRADING);
        return adminApi;
    }

    private static void running(AdminApi adminApi, FixSessionId activeConfig) {
        FixSession trading = mock(FixSession.class);
        FixSessionSettings settings = mock(FixSessionSettings.class);
        when(settings.getFixSessionType()).thenReturn(FixSession.FixSessionType.INITIATOR);
        when(trading.getFixSessionId()).thenReturn(activeConfig);
        when(trading.getFixSessionSettings()).thenReturn(settings);
        when(adminApi.getManagedFixSessions()).thenReturn(List.of(trading));
        when(adminApi.getInitiatorsTargets()).thenReturn(List.of(FixInitiatorTargets.builder()
                .instanceId("alpha-initiator")
                .activeFixSessionId(activeConfig)
                .mainTarget(FixInitiatorTarget.builder().fixSessionId(TRADING).build())
                .backupTarget(FixInitiatorTarget.builder().fixSessionId(TRADING_DRP).build())
                .build()));
    }

    private HttpAdminApi export(AdminApi adminApi, int port, String token) {
        return export(adminApi, port, token, null);
    }

    private HttpAdminApi export(AdminApi adminApi, int port, String token, String readOnlyToken) {
        HttpAdminApi exporter = new HttpAdminApi(HttpAdminApiSettings.builder()
                .bindAddress("127.0.0.1").port(port).apiToken(token).readOnlyApiToken(readOnlyToken).build());
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
        assertThat(call("GET", "/engines/alpha-engine/v1/status", null, null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/logon", null, "wrong").statusCode()).isEqualTo(401);
        verify(adminApi, never()).logonSession(any());
    }

    @Test
    void statusListsTheSessions() throws Exception {
        HttpResponse<String> response = call("GET", "/engines/alpha-engine/v1/status", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(response.body()).contains("\"engineId\":\"alpha-engine\"", "\"name\":\"trading\"");
    }

    @Test
    void sessionsAreServedWithTheVersionStatusReportsAsTheirETag() throws Exception {
        HttpResponse<String> sessions = call("GET", "/engines/alpha-engine/v1/sessions", null);

        assertThat(sessions.statusCode()).isEqualTo(200);
        assertThat(sessions.body()).contains("\"name\":\"trading\"", "\"configs\":[{\"name\":\"trading\"");
        String etag = sessions.headers().firstValue("ETag").orElseThrow();
        assertThat(call("GET", "/engines/alpha-engine/v1/status", null).body()).contains("\"sessionsVersion\":" + etag);

        HttpResponse<String> unchanged = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/engines/alpha-engine/v1/sessions"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("If-None-Match", etag)
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unchanged.statusCode()).isEqualTo(304);
    }

    @Test
    void sessionOperationsReachTheAdminApi() throws Exception {
        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/logon", null).statusCode()).isEqualTo(204);
        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/logout", null).statusCode()).isEqualTo(204);
        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/reset", "{\"mode\":\"RESET_SEQUENCE\"}").statusCode()).isEqualTo(204);
        assertThat(call("PUT", "/engines/alpha-engine/v1/sessions/alpha/trading/seqnums", "{\"outgoing\":42}").statusCode()).isEqualTo(204);
        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/messages",
                "{\"message\":\"35=B|148=hello|\",\"separator\":\"|\",\"possDup\":true}").statusCode()).isEqualTo(204);
        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/activate", "{\"config\":\"trading-drp\"}").statusCode()).isEqualTo(204);

        verify(adminApi).logonSession(TRADING);
        verify(adminApi).logoutSession(TRADING);
        verify(adminApi).resetSession(TRADING, ResetFixSessionMode.RESET_SEQUENCE);
        verify(adminApi).setOutgoingSeqNum(TRADING, 42);
        verify(adminApi, never()).setIncomingSeqNum(any(), anyLong());
        verify(adminApi).sendFixMessage(TRADING, "35=B|148=hello|", '|', true);
        verify(adminApi).switchInitiatorSession(TRADING_DRP);
    }

    @Test
    void aSessionRunningABackupIsStillAddressedByItsName() throws Exception {
        running(adminApi, TRADING_DRP);

        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/logon", null).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/engines/alpha-engine/v1/sessions/alpha/trading/settings", null).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading-drp/logon", null).statusCode())
                .as("a backup's name is not a session's").isEqualTo(404);
        verify(adminApi).logonSession(TRADING_DRP);
    }

    @Test
    void aSwitchWhileAnOperationRunsAsksForARetry() throws Exception {
        doAnswer(invocation -> {
            running(adminApi, TRADING_DRP);
            throw new IllegalArgumentException("Unknown session ID: " + TRADING);
        }).when(adminApi).logonSession(TRADING);

        HttpResponse<String> response = call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/logon", null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("retry");
    }

    @Test
    void onlyAnInitiatorSessionsOwnConfigsCanBeActivated() throws Exception {
        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/activate", "{\"config\":\"trading-uat\"}").statusCode())
                .isEqualTo(404);
        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/drop-copy/activate", "{\"config\":\"drop-copy\"}").statusCode())
                .isEqualTo(409);
        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/activate", "{}").statusCode()).isEqualTo(400);
        verify(adminApi, never()).switchInitiatorSession(any());
    }

    @Test
    void theResetModeIsAlsoAcceptedUnderItsFirstMisspeltName() throws Exception {
        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/reset", "{\"mode\":\"LOGOUT_LOGON_RESET_NUM_FLAG\"}").statusCode())
                .isEqualTo(204);
        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/reset", "{\"mode\":\"LOGOUT_LOGON_REST_NUM_FLAG\"}").statusCode())
                .isEqualTo(204);
    }

    @Test
    void theEngineTellsTheApiVersionsItServes() throws Exception {
        HttpResponse<String> engine = call("GET", "/engines/alpha-engine/", null);

        assertThat(engine.statusCode()).isEqualTo(200);
        assertThat(engine.body()).contains("\"engineId\":\"alpha-engine\"", "\"apiVersions\":[\"v1\"]");
        HttpResponse<String> unknownVersion = call("GET", "/engines/alpha-engine/v2/status", null);
        assertThat(unknownVersion.statusCode()).isEqualTo(404);
        assertThat(unknownVersion.body()).contains("this engine serves v1");
    }

    @Test
    void theReadOnlyTokenReadsButChangesNothing() throws Exception {
        port = export(adminApi("beta-engine"), 0, "beta-token", "beta-reader").getAddress().getPort();

        assertThat(call("GET", "/engines/beta-engine/v1/status", null, "beta-reader").statusCode()).isEqualTo(200);
        HttpResponse<String> refused = call("POST", "/engines/beta-engine/v1/sessions/alpha/trading/logon", null, "beta-reader");
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(call("POST", "/engines/beta-engine/v1/sessions/alpha/trading/logon", null, "beta-token").statusCode()).isEqualTo(204);
    }

    @Test
    void anUnexpectedFailureDoesNotExposeItsCause() throws Exception {
        doThrow(new NullPointerException("internal detail")).when(adminApi).logonSession(TRADING);

        HttpResponse<String> response = call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/logon", null);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("internal detail", "NullPointerException");
    }

    @Test
    void settingsStoresAreObjects() throws Exception {
        when(adminApi.getFixSessionsSettingsStoresInstanceIds()).thenReturn(List.of("main-store"));

        assertThat(call("GET", "/engines/alpha-engine/v1/settings-stores", null).body()).isEqualTo("[{\"instanceId\":\"main-store\"}]");
    }

    @Test
    void settingsOfTheRunningConfigAreJson() throws Exception {
        HttpResponse<String> response = call("GET", "/engines/alpha-engine/v1/sessions/alpha/trading/settings", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(call("GET", "/engines/alpha-engine/v1/sessions/alpha/trading-drp/settings", null).statusCode()).isEqualTo(404);
    }

    @Test
    void aDictionaryIsServedWithItsHashAsETag() throws Exception {
        HttpResponse<String> dictionary = call("GET", "/engines/alpha-engine/v1/dictionaries/alpha-FIX.4.4", null);

        assertThat(dictionary.statusCode()).isEqualTo(200);
        assertThat(dictionary.headers().firstValue("Content-Type")).hasValue("application/xml");
        assertThat(dictionary.body()).startsWith("<fix major=\"4\"");
        String etag = dictionary.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).isEqualTo("\"" + Dictionaries.get("alpha-FIX.4.4").orElseThrow().hash + "\"");

        HttpResponse<String> unchanged = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/engines/alpha-engine/v1/dictionaries/alpha-FIX.4.4"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("If-None-Match", etag)
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unchanged.statusCode()).isEqualTo(304);
        assertThat(call("GET", "/engines/alpha-engine/v1/dictionaries/beta-FIX.4.4", null).statusCode()).isEqualTo(404);
    }

    @Test
    void errorsAreProblemDetails() throws Exception {
        doThrow(new IllegalStateException("trading is not logged in"))
                .when(adminApi).sendFixMessage(any(), anyString(), anyChar(), anyBoolean());

        HttpResponse<String> refused = call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/messages",
                "{\"message\":\"35=B|\",\"separator\":\"|\"}");
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(refused.body()).isEqualTo("{\"status\":409,\"detail\":\"trading is not logged in\"}");

        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/beta/trading/logon", null).statusCode()).isEqualTo(404);
        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading-drp/logon", null).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/engines/alpha-engine/v1/sessions/alpha/trading/logon", null).statusCode()).isEqualTo(405);
        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/reset", "{\"mode\":\"NOPE\"}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/engines/alpha-engine/v1/sessions/alpha/trading/reset", "{}").statusCode()).isEqualTo(400);
        assertThat(call("GET", "/engines/gamma-engine/v1/status", null).statusCode()).isEqualTo(404);
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

        assertThat(call("POST", "/engines/beta-engine/v1/sessions/alpha/trading/logon", null, "beta-token").statusCode()).isEqualTo(204);
        assertThat(call("POST", "/engines/beta-engine/v1/sessions/alpha/trading/logon", null, "alpha-token").statusCode()).isEqualTo(401);
        verify(beta).logonSession(TRADING);
        verify(alpha, never()).logonSession(any());

        betaExporter.shutdown(Deadline.of(Duration.ofSeconds(1)));
        assertThat(call("GET", "/engines/beta-engine/v1/status", null, "beta-token").statusCode()).isEqualTo(404);
        assertThat(call("GET", "/engines/alpha-engine/v1/status", null, "alpha-token").statusCode()).isEqualTo(200);
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
        assertThat(call("GET", "/engines/alpha-engine/v1/status", null, "first").statusCode()).isEqualTo(200);
    }

    @Test
    void aGeneratedTokenIsUnguessable() {
        HttpAdminApi generated = export(adminApi("beta-engine"), 0, null);

        assertThat(generated.getApiToken()).hasSize(43).isNotEqualTo(export(adminApi("gamma-engine"), 0, null).getApiToken());
    }
}
