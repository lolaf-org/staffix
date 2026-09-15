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
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.spring.boot.actuator.endpoint.FixSessionsEndpoint;
import org.lolaf.staffix.spring.boot.actuator.health.FixSessionsHealthIndicator;
import org.lolaf.staffix.spring.boot.actuator.info.FixEngineInfoContributor;
import org.lolaf.staffix.spring.boot.actuator.testfixtures.TestLoggerContributor;
import org.lolaf.staffix.spring.boot.actuator.testfixtures.TestSessionsSettingsStoreContributor;
import org.lolaf.staffix.spring.boot.actuator.testfixtures.TestStoreContributor;
import org.lolaf.staffix.spring.boot.spi.StaffixApplicationFactoryConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ActuatorAutoConfigurationTest.TestApp.class,
        properties = {
                "management.endpoints.web.exposure.include=fix-sessions,health,info",
                "management.endpoints.access.default=unrestricted"
        })
class ActuatorAutoConfigurationTest {

    private static final FixSessionId ACCEPTOR_SESSION_ID =
            FixSessionId.of("test", FixRegularVersion.VERSION_44, "ACCEPTOR", "INITIATOR_1");
    private static final FixSessionId INITIATOR_SESSION_ID =
            FixSessionId.of("test", FixRegularVersion.VERSION_44, "INITIATOR_1", "ACCEPTOR");

    @Autowired
    private FixEngine fixEngine;

    @Autowired
    @Qualifier("fixAcceptor-primary")
    private FixAcceptor acceptor;

    @Autowired
    @Qualifier("fixInitiator-primary")
    private FixInitiator initiator;

    @Autowired
    @Qualifier("acceptorApp")
    private TestFixApplication acceptorApp;

    @Autowired
    @Qualifier("initiatorApp")
    private TestFixApplication initiatorApp;

    @Autowired
    private ActuatorSessionsRegistry registry;

    @Autowired
    private FixSessionsHealthIndicator healthIndicator;

    @Autowired
    private FixSessionsEndpoint endpoint;

    @Autowired
    private FixEngineInfoContributor infoContributor;

    @Test
    void registryAndActuatorBeansReflectLoggedInSessions() {
        assertThat(fixEngine).isNotNull();
        assertThat(registry).isNotNull();

        Awaitility.await().atMost(Duration.ofSeconds(15))
                .until(() -> initiator.isConnected()
                        && initiatorApp.getLogonCount().get() >= 1
                        && acceptorApp.getLogonCount().get() >= 1);

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertThat(registry.snapshot()).hasSize(2);
                    assertThat(registry.find(ACCEPTOR_SESSION_ID)).isPresent();
                    assertThat(registry.find(INITIATOR_SESSION_ID)).isPresent();
                    assertThat(registry.find(ACCEPTOR_SESSION_ID).get().getState())
                            .isEqualTo(FixSessionState.LOGGED_IN);
                    assertThat(registry.find(INITIATOR_SESSION_ID).get().getState())
                            .isEqualTo(FixSessionState.LOGGED_IN);
                });

        // health indicator
        assertThat(healthIndicator.health().getStatus().getCode()).isEqualTo("UP");
        assertThat(healthIndicator.health().getDetails()).containsKeys(
                ACCEPTOR_SESSION_ID.getId(), INITIATOR_SESSION_ID.getId());

        // endpoint
        Map<String, Object> list = endpoint.list();
        assertThat(list).containsEntry("count", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) list.get("sessions");
        assertThat(sessions).extracting(s -> s.get("state"))
                .allMatch("LOGGED_IN"::equals);
        assertThat(sessions).extracting(s -> s.get("id"))
                .containsExactlyInAnyOrder(ACCEPTOR_SESSION_ID.getId(), INITIATOR_SESSION_ID.getId());

        Map<String, Object> single = endpoint.getOne(ACCEPTOR_SESSION_ID.getId());
        assertThat(single).isNotNull()
                .containsEntry("id", ACCEPTOR_SESSION_ID.getId())
                .containsEntry("state", "LOGGED_IN");

        // a TestRequest forces a Heartbeat from the peer, exercising onMessageSent/onMessageReceived
        // across both sessions (logon-handshake messages alone may not have decoded by this point).
        initiator.getSession().testRequest("ping");
        Awaitility.await().untilAsserted(() -> {
            ActuatorSessionStats acc = registry.find(ACCEPTOR_SESSION_ID).orElseThrow();
            assertThat(acc.getMessagesReceived()).isPositive();
            assertThat(acc.getMessagesSent()).isPositive();

            ActuatorSessionStats ini = registry.find(INITIATOR_SESSION_ID).orElseThrow();
            assertThat(ini.getMessagesReceived()).isPositive();
            assertThat(ini.getMessagesSent()).isPositive();
        });

        // info contributor
        Info.Builder builder = new Info.Builder();
        infoContributor.contribute(builder);
        @SuppressWarnings("unchecked")
        Map<String, Object> staffixInfo = (Map<String, Object>) builder.build().getDetails().get("staffix");
        assertThat(staffixInfo).containsEntry("monitoringInstanceId", "actuator-monitoring")
                .containsEntry("knownSessions", 2);
    }

    @Test
    void unknownSessionIdReturnsNullFromEndpoint() {
        assertThat(endpoint.getOne("does-not-exist")).isNull();
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
