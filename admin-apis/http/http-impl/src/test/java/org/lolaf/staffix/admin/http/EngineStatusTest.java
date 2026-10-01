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
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionId.FixSessionIdBuilder;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.net.InetSocketAddress;
import java.util.List;

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
        when(session.getFixSessionId()).thenReturn(fixSessionId);
        when(session.getFixSessionSettings()).thenReturn(settings);
        when(session.isLoggedIn()).thenReturn(loggedIn);
        when(session.isConnected()).thenReturn(loggedIn);
        when(session.isWithinSessionTime()).thenReturn(true);
        when(session.getDesiredState()).thenReturn(loggedIn ? FixSessionState.LOGGED_IN : FixSessionState.LOGGED_OUT);
        return session;
    }

    private JsonNode snapshot() {
        when(adminApi.getInstanceId()).thenReturn("alpha-engine");
        List<FixSession> managed = List.of(
                session(TRADING_DRP, FixSession.FixSessionType.INITIATOR, true),
                session(DROP_COPY, FixSession.FixSessionType.ACCEPTOR, false));
        when(adminApi.getManagedFixSessions()).thenReturn(managed);
        when(adminApi.getInitiatorsTargets()).thenReturn(List.of(FixInitiatorTargets.builder()
                .instanceId("alpha-initiator")
                .activeFixSessionId(TRADING_DRP)
                .target(FixInitiatorTarget.builder().fixSessionId(TRADING)
                        .connectAddress(InetSocketAddress.createUnresolved("alpha.example.com", 9001)).build())
                .target(FixInitiatorTarget.builder().fixSessionId(TRADING_DRP).build())
                .build()));
        when(adminApi.getAcceptorsSessions()).thenReturn(List.of(FixAcceptorSessions.builder()
                .instanceId("main-acceptor")
                .fixSessionId(DROP_COPY)
                .build()));
        when(adminApi.getIncomingSeqNum(TRADING_DRP)).thenReturn(12L);
        when(adminApi.getOutgoingSeqNum(TRADING_DRP)).thenReturn(34L);
        return mapper.valueToTree(EngineStatus.of(adminApi));
    }

    @Test
    void anInitiatorIsOneSessionNamedAfterItsMainConfigWithTheSelectedOneRunning() {
        JsonNode trading = snapshot().get("sessions").get(0);

        assertThat(trading.get("group").asText()).isEqualTo("alpha");
        assertThat(trading.get("name").asText()).isEqualTo("trading");
        assertThat(trading.get("type").asText()).isEqualTo("INITIATOR");
        assertThat(trading.get("instanceId").asText()).isEqualTo("alpha-initiator");
        assertThat(trading.get("configs").toString()).isEqualTo("[{\"name\":\"trading\",\"connectAddresses\":[\"alpha.example.com:9001\"]},"
                + "{\"name\":\"trading-drp\",\"connectAddresses\":[]}]");
        assertThat(trading.get("selectedConfig").asText()).isEqualTo("trading-drp");
        assertThat(trading.get("loggedIn").asBoolean()).isTrue();
        assertThat(trading.get("desiredState").asText()).isEqualTo("LOGGED_IN");
        assertThat(trading.get("incomingSeqNum").asLong()).isEqualTo(12);
        assertThat(trading.get("outgoingSeqNum").asLong()).isEqualTo(34);
        assertThat(trading.get("identity").toString())
                .isEqualTo("{\"fixVersion\":\"FIX.4.4\",\"sender\":{\"compId\":\"US\",\"subId\":\"DR\"},\"target\":{\"compId\":\"ALPHA\"}}");
    }

    @Test
    void anAcceptorSessionHasItsOwnNameAsItsOnlyConfig() {
        JsonNode dropCopy = snapshot().get("sessions").get(1);

        assertThat(dropCopy.get("group").asText()).isEqualTo("beta");
        assertThat(dropCopy.get("type").asText()).isEqualTo("ACCEPTOR");
        assertThat(dropCopy.get("instanceId").asText()).isEqualTo("main-acceptor");
        assertThat(dropCopy.get("configs").toString()).isEqualTo("[{\"name\":\"drop-copy\",\"connectAddresses\":[]}]");
        assertThat(dropCopy.get("desiredState").asText()).isEqualTo("LOGGED_OUT");
        assertThat(dropCopy.get("identity").toString())
                .isEqualTo("{\"fixVersion\":\"FIXT.1.1\",\"defaultApplVerId\":\"9\",\"sender\":{\"compId\":\"US\"},\"target\":{\"compId\":\"BETA\",\"locationId\":\"LDN\"}}");
    }

    @Test
    void aSessionUnregisteredWhileTheSnapshotIsBuiltIsLeftOut() {
        when(adminApi.getIncomingSeqNum(DROP_COPY)).thenThrow(new IllegalArgumentException("not managed"));

        JsonNode sessions = snapshot().get("sessions");

        assertThat(sessions).extracting(session -> session.get("name").asText()).containsExactly("trading");
    }
}
