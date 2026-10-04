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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.admin.FixAcceptorSessions;
import org.lolaf.staffix.api.admin.FixInitiatorTargets;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionId.FixSessionIdBuilder;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionSettingsEndpointsTest {

    private static final String TOKEN = "alpha-token";
    private static final String ENGINE = "/engines/alpha-engine/v1/";
    private static final FixSessionId TRADING = id("trading");
    private static final FixSessionId TRADING_DRP = id("trading-drp");
    private static final FixSessionId DROP_COPY = id("drop-copy");
    private static final FixApplicationSessionSettingDescriptor PASSWORD =
            FixApplicationSessionSettingDescriptor.secret("endpoints.logon", "The Logon password");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private final AdminApi adminApi = mock(AdminApi.class);
    private HttpAdminApi exporter;

    private static FixSessionId id(String name) {
        return FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionIdBuilder.builder()
                .group("alpha").name(name).senderCompID("US").targetCompID("ALPHA").build());
    }

    private static FixSessionSettings settings(FixSessionId fixSessionId, FixSessionType type) {
        return FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(type)
                .fixApplicationSessionSetting(PASSWORD, "hunter2")
                .fixApplicationSessionSetting(FixApplicationSessionSettingDescriptor.of("account"), "ACC-1")
                .build();
    }

    private void running(FixSessionId initiatorConfig) {
        when(adminApi.getInitiatorsTargets()).thenReturn(List.of(FixInitiatorTargets.builder()
                .instanceId("alpha-initiator")
                .activeFixSessionId(initiatorConfig)
                .mainTarget(FixInitiatorTarget.builder().fixSessionId(TRADING).build())
                .backupTarget(FixInitiatorTarget.builder().fixSessionId(TRADING_DRP).build())
                .build()));
        FixSessionSettings initiatorSettings = settings(TRADING, FixSessionType.INITIATOR).toBuilder()
                .fixSessionId(initiatorConfig).build();
        when(adminApi.getManagedFixSessionsSettings())
                .thenReturn(List.of(initiatorSettings, settings(DROP_COPY, FixSessionType.ACCEPTOR)));
    }

    @BeforeEach
    void export() {
        when(adminApi.getInstanceId()).thenReturn("alpha-engine");
        when(adminApi.getAcceptorsSessions()).thenReturn(List.of(FixAcceptorSessions.builder()
                .instanceId("alpha-acceptor").fixSessionId(DROP_COPY).build()));
        when(adminApi.getFixSessionsSettingsStoresInstanceIds()).thenReturn(List.of("main-store"));
        running(TRADING);
        exporter = new HttpAdminApi(HttpAdminApiSettings.builder().bindAddress("127.0.0.1").port(0).apiToken(TOKEN).build());
        exporter.export(adminApi);
    }

    @AfterEach
    void shutdown() {
        exporter.shutdown(Deadline.of(Duration.ofSeconds(1)));
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + exporter.getAddress().getPort() + ENGINE + path))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + TOKEN)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode settingsOf(String session) throws Exception {
        HttpResponse<String> response = call("GET", "sessions/alpha/" + session + "/settings", null);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    @Test
    void theSchemaDescribesResolvedValues() throws Exception {
        HttpResponse<String> response = call("GET", "schemas/session-settings", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/schema+json");
        JsonNode timeout = JSON.readTree(response.body()).path("properties").path("logInOrOutResponseTimeout");
        assertThat(timeout.has("anyOf")).as("no placeholder alternative").isFalse();
        assertThat(timeout.path("description").asText()).isNotEmpty();
    }

    @Test
    void settingsAreTheSessionsDocumentWithSecretsMasked() throws Exception {
        JsonNode settings = settingsOf("drop-copy");

        assertThat(settings.at("/fixSessionId/name").asText()).isEqualTo("drop-copy");
        assertThat(settings.path("logInOrOutResponseTimeout").asText()).isEqualTo("PT10S");
        assertThat(settings.at("/fixApplicationSessionSettings/endpoints.logon").asText()).isEqualTo(SessionSettingsDocuments.MASK);
        assertThat(settings.at("/fixApplicationSessionSettings/account").asText()).isEqualTo("ACC-1");
    }

    @Test
    void aSessionRunningABackupShowsItsMainSettings() throws Exception {
        running(TRADING_DRP);

        assertThat(settingsOf("trading").at("/fixSessionId/name").asText()).isEqualTo("trading");
    }

    @Test
    void anUpdateKeepsTheSecretsSentBackMasked() throws Exception {
        ObjectNode settings = (ObjectNode) settingsOf("drop-copy");
        settings.put("desiredSessionState", "LOGGED_OUT");

        assertThat(call("PUT", "sessions/alpha/drop-copy/settings", settings.toString()).statusCode()).isEqualTo(204);

        ArgumentCaptor<FixSessionSettings> updated = ArgumentCaptor.forClass(FixSessionSettings.class);
        verify(adminApi).updateFixSessionSettings(eq(DROP_COPY), updated.capture());
        assertThat(updated.getValue().getDesiredSessionState()).isEqualTo(FixSessionState.LOGGED_OUT);
        assertThat(updated.getValue().getFixApplicationSessionSettings()).containsEntry(PASSWORD, "hunter2");
    }

    @Test
    void anInvalidDocumentIsABadRequest() throws Exception {
        ObjectNode settings = (ObjectNode) settingsOf("drop-copy");

        assertThat(call("PUT", "sessions/alpha/drop-copy/settings", settings.deepCopy().put("unknown", 1).toString()).statusCode())
                .isEqualTo(400);
        settings.remove("fixSessionId");
        assertThat(call("PUT", "sessions/alpha/drop-copy/settings", settings.toString()).statusCode()).isEqualTo(400);
        verify(adminApi, never()).updateFixSessionSettings(any(), any());
    }

    @Test
    void aSessionIsRemovedFromItsStore() throws Exception {
        assertThat(call("DELETE", "sessions/alpha/drop-copy/settings", null).statusCode()).isEqualTo(204);

        verify(adminApi).removeFixSessionSettings(DROP_COPY, FixSessionType.ACCEPTOR);
    }

    @Test
    void anAddedSessionIsCreatedAtItsName() throws Exception {
        ObjectNode settings = (ObjectNode) settingsOf("drop-copy");
        ((ObjectNode) settings.get("fixSessionId")).put("name", "drop-copy-2");
        ((ObjectNode) settings.get("fixApplicationSessionSettings")).put("endpoints.logon", "s3cret");

        HttpResponse<String> response = call("POST", "session-settings-stores/main-store/sessions", settings.toString());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).hasValue("/engines/alpha-engine/v1/sessions/alpha/drop-copy-2");
        verify(adminApi).addFixSessionSettings(eq("main-store"), any());
    }

    @Test
    void aMaskedSecretCannotStartANewSession() throws Exception {
        ObjectNode settings = (ObjectNode) settingsOf("drop-copy");
        ((ObjectNode) settings.get("fixSessionId")).put("name", "drop-copy-2");

        assertThat(call("POST", "session-settings-stores/main-store/sessions", settings.toString()).statusCode()).isEqualTo(400);
    }

    @Test
    void addingToAnUnknownStoreOrATakenNameIsRefused() throws Exception {
        String settings = settingsOf("drop-copy").toString();
        doThrow(new IllegalStateException("The engine already has a session alpha.drop-copy"))
                .when(adminApi).addFixSessionSettings(eq("main-store"), any());

        assertThat(call("POST", "session-settings-stores/missing/sessions", settings).statusCode()).isEqualTo(404);
        assertThat(call("POST", "session-settings-stores/main-store/sessions", settings.replace("******", "x")).statusCode()).isEqualTo(409);
    }

    @Test
    void theApplicationSettingsASessionDeclaresSayWhichAreSecret() throws Exception {
        when(adminApi.getFixApplicationSessionSettingDescriptors(DROP_COPY)).thenReturn(List.of(PASSWORD));

        JsonNode declared = JSON.readTree(call("GET", "sessions/alpha/drop-copy/application-settings", null).body());

        assertThat(declared.get(0).path("id").asText()).isEqualTo("endpoints.logon");
        assertThat(declared.get(0).path("description").asText()).isEqualTo("The Logon password");
        assertThat(declared.get(0).path("secret").asBoolean()).isTrue();
    }
}
