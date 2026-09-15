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
package org.lolaf.staffix.spring.boot;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.spring.boot.spi.FixMessagesStoreSettingsContributor;
import org.lolaf.staffix.spring.boot.spi.StaffixApplicationFactoryConstants;
import org.lolaf.staffix.spring.boot.testfixtures.TestLoggerContributor;
import org.lolaf.staffix.spring.boot.testfixtures.TestSessionsSettingsStoreContributor;
import org.lolaf.staffix.spring.boot.testfixtures.TestStoreContributor;
import org.lolaf.staffix.tests.TestingFixMessagesStoreSettings;
import org.lolaf.staffix.tests.TestingFixSessionMessagesStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = StaffixAutoConfigurationTest.TestApp.class)
class StaffixAutoConfigurationTest {

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
    private List<FixMessagesStoreSettingsContributor> storeContributors;

    @Test
    void initiatorLogsOnToAcceptor() {
        assertThat(fixEngine).isNotNull();
        assertThat(acceptor).isNotNull();
        assertThat(initiator).isNotNull();

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> initiator.isConnected()
                        && initiatorApp.getLogonCount().get() >= 1
                        && acceptorApp.getLogonCount().get() >= 1);

        assertThat(acceptor.getConnectedSessions()).hasSize(1);
    }

    @Test
    void higherOrderContributorOverwritesEarlierEntry() {
        Map<String, FixMessagesStoreSettings> registry = new LinkedHashMap<>();
        storeContributors.stream()
                .sorted(Comparator.comparingInt(FixMessagesStoreSettingsContributor::order))
                .forEach(c -> c.contribute(registry));

        assertThat(registry).containsKey("OVERWRITTEN");
        FixMessagesStoreSettings entry = registry.get("OVERWRITTEN");
        assertThat(entry).isInstanceOf(TestingFixMessagesStoreSettings.class);
        assertThat(entry.getInstanceId()).isEqualTo("OVERWRITTEN");
    }

    @SpringBootApplication
    static class TestApp {

        public static void main(String[] args) {
            new SpringApplicationBuilder(TestApp.class).run(args);
        }

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
        TestStoreContributor baseOverwritableStore() {
            return new TestStoreContributor("OVERWRITTEN", 0);
        }

        @Bean
        FixMessagesStoreSettingsContributor wrapperContributor() {
            return new FixMessagesStoreSettingsContributor() {
                @Override
                public int order() {
                    return 10;
                }

                @Override
                public void contribute(Map<String, FixMessagesStoreSettings> registry) {
                    registry.put("OVERWRITTEN", TestingFixMessagesStoreSettings.builder()
                            .instanceId("OVERWRITTEN")
                            .testingFixSessionMessagesStore(new TestingFixSessionMessagesStore())
                            .build());
                }
            };
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
