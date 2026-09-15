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
package org.lolaf.staffix.spring.boot.actuator;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.spring.boot.actuator.testfixtures.TestLoggerContributor;
import org.lolaf.staffix.spring.boot.actuator.testfixtures.TestSessionsSettingsStoreContributor;
import org.lolaf.staffix.spring.boot.actuator.testfixtures.TestStoreContributor;
import org.lolaf.staffix.spring.boot.spi.StaffixApplicationFactoryConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ActuatorHttpEndpointsTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.endpoints.web.exposure.include=fix-sessions,health,info",
                "management.endpoints.access.default=unrestricted",
                "management.endpoint.health.show-details=always",
                "staffix.acceptors.primary.bind-address=localhost:17022",
                "staffix.initiators.primary.connect-addresses[0]=localhost:17022"
        })
class ActuatorHttpEndpointsTest {

    private static final FixSessionId ACCEPTOR_SESSION_ID =
            FixSessionId.of("test", FixRegularVersion.VERSION_44, "ACCEPTOR", "INITIATOR_1");
    private static final FixSessionId INITIATOR_SESSION_ID =
            FixSessionId.of("test", FixRegularVersion.VERSION_44, "INITIATOR_1", "ACCEPTOR");

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    @LocalServerPort
    private int port;

    private RestClient rest;

    @Autowired
    @Qualifier("fixInitiator-primary")
    private FixInitiator initiator;

    @Autowired
    @Qualifier("acceptorApp")
    private TestFixApplication acceptorApp;

    @Autowired
    @Qualifier("initiatorApp")
    private TestFixApplication initiatorApp;

    @BeforeEach
    void setupClient() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(s -> true, (req, res) -> { /* no-op: surface raw response */ })
                .build();
    }

    @Test
    void httpEndpointsExposeSessionsHealthAndInfo() {
        Awaitility.await().atMost(Duration.ofSeconds(15))
                .until(() -> initiator.isConnected()
                        && initiatorApp.getLogonCount().get() >= 1
                        && acceptorApp.getLogonCount().get() >= 1);

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ResponseEntity<Map<String, Object>> resp = getJson("/actuator/fix-sessions");
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> body = resp.getBody();
            assertThat(body).isNotNull().containsEntry("count", 2);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sessions = (List<Map<String, Object>>) body.get("sessions");
            assertThat(sessions).hasSize(2);
            assertThat(sessions).extracting(s -> s.get("state"))
                    .allMatch("LOGGED_IN"::equals);
            assertThat(sessions).extracting(s -> s.get("id"))
                    .containsExactlyInAnyOrder(ACCEPTOR_SESSION_ID.getId(), INITIATOR_SESSION_ID.getId());
        });

        ResponseEntity<Map<String, Object>> single =
                getJson("/actuator/fix-sessions/" + ACCEPTOR_SESSION_ID.getId());
        assertThat(single.getStatusCode().value()).isEqualTo(200);
        assertThat(single.getBody())
                .containsEntry("id", ACCEPTOR_SESSION_ID.getId())
                .containsEntry("state", "LOGGED_IN");

        ResponseEntity<Map<String, Object>> missing = getJson("/actuator/fix-sessions/does-not-exist");
        assertThat(missing.getStatusCode().value()).isEqualTo(404);

        ResponseEntity<Map<String, Object>> health = getJson("/actuator/health");
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> healthBody = health.getBody();
        assertThat(healthBody).isNotNull().containsEntry("status", "UP");
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) healthBody.get("components");
        assertThat(components).containsKey("staffixFixSessions");
        @SuppressWarnings("unchecked")
        Map<String, Object> fixSessions = (Map<String, Object>) components.get("staffixFixSessions");
        @SuppressWarnings("unchecked")
        Map<String, Object> healthDetails = (Map<String, Object>) fixSessions.get("details");
        assertThat(healthDetails).containsKeys(
                ACCEPTOR_SESSION_ID.getId(), INITIATOR_SESSION_ID.getId());

        ResponseEntity<Map<String, Object>> info = getJson("/actuator/info");
        assertThat(info.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> infoBody = info.getBody();
        assertThat(infoBody).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> staffixInfo = (Map<String, Object>) infoBody.get("staffix");
        assertThat(staffixInfo)
                .containsEntry("monitoringInstanceId", "actuator-monitoring")
                .containsEntry("knownSessions", 2);

        initiator.getSession().testRequest("ping");
        Awaitility.await().untilAsserted(() -> {
            ResponseEntity<Map<String, Object>> after = getJson("/actuator/fix-sessions");
            assertThat(after.getStatusCode().value()).isEqualTo(200);
            Map<String, Object> afterBody = after.getBody();
            assertThat(afterBody).isNotNull();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> all = (List<Map<String, Object>>) afterBody.get("sessions");
            assertThat(all).hasSize(2).allSatisfy(s -> {
                assertThat(((Number) s.get("messagesReceived")).longValue()).isPositive();
                assertThat(((Number) s.get("messagesSent")).longValue()).isPositive();
            });
        });
    }

    private ResponseEntity<Map<String, Object>> getJson(String path) {
        return rest.get().uri(path).retrieve().toEntity(MAP_TYPE);
    }

    @SpringBootApplication
    static class TestApp {

        @Bean
        FixApplication acceptorApp() {
            return new TestFixApplication();
        }

        @Bean
        FixApplication initiatorApp() {
            return new TestFixApplication();
        }

        @Bean
        TestStoreContributor acceptorStore() {
            return new TestStoreContributor("ACCEPTOR");
        }

        @Bean
        TestStoreContributor initiatorStore() {
            return new TestStoreContributor("INITIATOR");
        }

        @Bean
        TestLoggerContributor acceptorLogger() {
            return new TestLoggerContributor("ACCEPTOR");
        }

        @Bean
        TestLoggerContributor initiatorLogger() {
            return new TestLoggerContributor("INITIATOR");
        }

        @Bean
        TestSessionsSettingsStoreContributor sessionsStoreContributor() {
            FixSessionSettings acceptorSession = FixSessionSettings.builder()
                    .fixSessionId(ACCEPTOR_SESSION_ID)
                    .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                    .fixMessageStoreInstanceId("ACCEPTOR")
                    .fixMessageLoggerInstanceId("ACCEPTOR")
                    .fixApplicationFactoryInstanceId(StaffixApplicationFactoryConstants.SPRING_FACTORY_INSTANCE_ID)
                    .fixApplicationInstanceId("acceptorApp")
                    .resetSeqNumOnLogon(true)
                    .build();
            FixSessionSettings initiatorSession = FixSessionSettings.builder()
                    .fixSessionId(INITIATOR_SESSION_ID)
                    .fixSessionType(FixSession.FixSessionType.INITIATOR)
                    .fixMessageStoreInstanceId("INITIATOR")
                    .fixMessageLoggerInstanceId("INITIATOR")
                    .fixApplicationFactoryInstanceId(StaffixApplicationFactoryConstants.SPRING_FACTORY_INSTANCE_ID)
                    .fixApplicationInstanceId("initiatorApp")
                    .resetSeqNumOnLogon(true)
                    .build();
            return new TestSessionsSettingsStoreContributor("ACCEPTOR", List.of(acceptorSession, initiatorSession));
        }
    }
}
