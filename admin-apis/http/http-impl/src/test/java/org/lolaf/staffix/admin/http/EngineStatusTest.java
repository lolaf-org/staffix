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
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.admin.FixAcceptorSessions;
import org.lolaf.staffix.api.admin.FixInitiatorTargets;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionId.FixSessionIdBuilder;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EngineStatusTest {

    private static final FixSessionId TRADING = FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionIdBuilder.builder()
            .group("alpha").name("trading").senderCompID("US").targetCompID("ALPHA").build());
    private static final FixSessionId TRADING_DRP = FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionIdBuilder.builder()
            .group("alpha").name("trading-drp").senderCompID("US").senderSubID("DR").targetCompID("ALPHA").build());
    private static final FixSessionId DROP_COPY = FixSessionId.ofFIXT11(FixApplVerID.FIX50SP2, FixSessionIdBuilder.builder()
            .group("beta").name("drop-copy").senderCompID("US").targetCompID("BETA").targetLocationID("LDN").build());

    private final AdminApi adminApi = mock(AdminApi.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private static FixSession session(FixSessionId fixSessionId, FixSession.FixSessionType type, boolean loggedIn) {
        FixSession session = mock(FixSession.class);
        FixSessionSettings settings = mock(FixSessionSettings.class);
        when(settings.getFixSessionType()).thenReturn(type);
        if (type == FixSession.FixSessionType.INITIATOR) {
            when(settings.getFixMessageLoggerInstanceId()).thenReturn("otlp-logger");
            when(settings.getFixSessionPluginsInstanceIds()).thenReturn(Map.of(FixSessionsMonitoringManager.class, "otlp-metrics"));
        } else {
            when(settings.getFixMessageLoggerInstanceId()).thenReturn("default");
        }
        when(session.getFixSessionId()).thenReturn(fixSessionId);
        when(session.getFixSessionSettings()).thenReturn(settings);
        when(session.isLoggedIn()).thenReturn(loggedIn);
        when(session.isConnected()).thenReturn(loggedIn);
        when(session.isWithinSessionTime()).thenReturn(true);
        when(session.getDesiredState()).thenReturn(loggedIn ? FixSessionState.LOGGED_IN : FixSessionState.LOGGED_OUT);
        return session;
    }

    private void engine(FixSession... managed) {
        when(adminApi.getInstanceId()).thenReturn("alpha-engine");
        when(adminApi.getManagedFixSessions()).thenReturn(List.of(managed));
        when(adminApi.getInitiatorsTargets()).thenReturn(List.of(FixInitiatorTargets.builder()
                .instanceId("alpha-initiator")
                .activeFixSessionId(TRADING_DRP)
                .mainTarget(FixInitiatorTarget.builder().fixSessionId(TRADING)
                        .connectAddress(InetSocketAddress.createUnresolved("alpha.example.com", 9001)).build())
                .backupTarget(FixInitiatorTarget.builder().fixSessionId(TRADING_DRP)
                        .connectAddress(InetSocketAddress.createUnresolved("2001:db8::7", 9002)).build())
                .build()));
        when(adminApi.getAcceptorsSessions()).thenReturn(List.of(FixAcceptorSessions.builder()
                .instanceId("main-acceptor")
                .fixSessionId(DROP_COPY)
                .build()));
        when(adminApi.getIncomingSeqNum(TRADING_DRP)).thenReturn(12L);
        when(adminApi.getOutgoingSeqNum(TRADING_DRP)).thenReturn(34L);
    }

    private void engine() {
        engine(session(TRADING_DRP, FixSession.FixSessionType.INITIATOR, true),
                session(DROP_COPY, FixSession.FixSessionType.ACCEPTOR, false));
    }

    private JsonNode status() {
        return mapper.valueToTree(EngineStatus.of(adminApi, SessionsDocument.running(adminApi), "v1"));
    }

    private JsonNode sessions() throws Exception {
        return mapper.readTree(SessionsDocument.of(adminApi, SessionsDocument.running(adminApi)).json);
    }

    @Test
    void anInitiatorIsOneSessionNamedAfterItsMainConfigWithTheSelectedOneRunning() {
        engine();
        JsonNode trading = status().get("sessions").get(0);

        assertThat(trading.toString()).isEqualTo("{\"group\":\"alpha\",\"name\":\"trading\",\"selectedConfig\":\"trading-drp\","
                + "\"loggedIn\":true,\"connected\":true,\"withinSessionTime\":true,\"desiredState\":\"LOGGED_IN\","
                + "\"incomingSeqNum\":12,\"outgoingSeqNum\":34}");
        assertThat(status().get("sessionsVersion").asText()).isEqualTo("v1");
        assertThat(status().get("engineId").asText()).isEqualTo("alpha-engine");
    }

    @Test
    void anInitiatorIsDescribedWithEachConfigsAddressesAndIdentity() throws Exception {
        engine();
        JsonNode trading = sessions().get("sessions").get(0);

        assertThat(trading.get("name").asText()).isEqualTo("trading");
        assertThat(trading.get("type").asText()).isEqualTo("INITIATOR");
        assertThat(trading.get("instanceId").asText()).isEqualTo("alpha-initiator");
        assertThat(trading.get("configs")).extracting(config -> config.get("name").asText() + " " + config.get("connectAddresses"))
                .containsExactly("trading [\"alpha.example.com:9001\"]", "trading-drp [\"[2001:db8::7]:9002\"]");
        assertThat(trading.get("configs").get(1).get("identity").toString())
                .isEqualTo("{\"fixVersion\":\"FIX.4.4\",\"sender\":{\"compId\":\"US\",\"subId\":\"DR\"},\"target\":{\"compId\":\"ALPHA\"}}");
    }

    @Test
    void anAcceptorSessionHasItsOwnNameAsItsOnlyConfig() throws Exception {
        engine();
        JsonNode dropCopy = sessions().get("sessions").get(1);

        assertThat(dropCopy.get("type").asText()).isEqualTo("ACCEPTOR");
        assertThat(dropCopy.get("instanceId").asText()).isEqualTo("main-acceptor");
        assertThat(dropCopy.get("configs")).hasSize(1);
        assertThat(dropCopy.get("configs").get(0).get("identity").toString())
                .isEqualTo("{\"fixVersion\":\"FIXT.1.1\",\"defaultApplVerId\":\"9\",\"sender\":{\"compId\":\"US\"},\"target\":{\"compId\":\"BETA\",\"locationId\":\"LDN\"}}");
        assertThat(status().get("sessions").get(1).get("desiredState").asText()).isEqualTo("LOGGED_OUT");
    }

    @Test
    void aSessionIsDescribedWithItsMessagesLoggerAndMonitoringPlugin() throws Exception {
        engine();
        JsonNode sessions = sessions().get("sessions");

        assertThat(sessions.get(0).get("messagesLoggerInstanceId").asText()).isEqualTo("otlp-logger");
        assertThat(sessions.get(0).get("monitoringInstanceId").asText()).isEqualTo("otlp-metrics");
        assertThat(sessions.get(1).get("messagesLoggerInstanceId").asText()).isEqualTo("default");
        assertThat(sessions.get(1).has("monitoringInstanceId")).as("absent when not monitored").isFalse();
    }

    @Test
    void aSessionUnregisteredWhileTheStatusIsBuiltIsLeftOut() {
        engine();
        when(adminApi.getIncomingSeqNum(DROP_COPY)).thenThrow(new IllegalArgumentException("not managed"));

        assertThat(status().get("sessions")).extracting(session -> session.get("name").asText()).containsExactly("trading");
    }

    @Test
    void theDocumentStaysCurrentUntilASessionComesGoesOrRestartsOnNewSettings() throws Exception {
        FixSession trading = session(TRADING_DRP, FixSession.FixSessionType.INITIATOR, true);
        FixSession dropCopy = session(DROP_COPY, FixSession.FixSessionType.ACCEPTOR, false);
        engine(trading, dropCopy);
        SessionsDocument document = SessionsDocument.of(adminApi, SessionsDocument.running(adminApi));

        assertThat(document.describes(SessionsDocument.running(adminApi))).isTrue();
        engine(trading);
        assertThat(document.describes(SessionsDocument.running(adminApi))).as("gone").isFalse();
        engine(trading, session(DROP_COPY, FixSession.FixSessionType.ACCEPTOR, false));
        assertThat(document.describes(SessionsDocument.running(adminApi))).as("restarted on new settings").isFalse();
    }

    @Test
    void equalContentHasTheSameVersion() throws Exception {
        engine();
        String version = SessionsDocument.of(adminApi, SessionsDocument.running(adminApi)).version;

        engine();

        assertThat(SessionsDocument.of(adminApi, SessionsDocument.running(adminApi)).version).isEqualTo(version).hasSize(64);
        engine(session(TRADING_DRP, FixSession.FixSessionType.INITIATOR, true));
        assertThat(SessionsDocument.of(adminApi, SessionsDocument.running(adminApi)).version).isNotEqualTo(version);
    }
}
