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
package org.lolaf.staffix.alerting.slf4j;

import com.github.valfirst.slf4jtest.LoggingEvent;
import com.github.valfirst.slf4jtest.TestLogger;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.AlertEvent;
import org.lolaf.staffix.api.alerting.ProbeId;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionProbeScope;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TestSlf4jAlertsPublisher {

    private static final Instant SINCE = Instant.parse("2026-10-10T10:00:00Z");
    private static final FixSessionId SESSION_ID = FixSessionId.of("orders", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
    private static final ProbeId PROBE_ID = ProbeId.of(FixSessionProbeScope.of(SESSION_ID), "queue");

    @BeforeEach
    void clearLogs() {
        TestLoggerFactory.clearAll();
    }

    @Test
    void logsAtTheLevelOfTheAlertAndInfoOnceCleared() {
        Slf4jAlertsPublisher publisher = new Slf4jAlertsPublisher(Slf4jAlertsPublisherSettings.builder().build());

        publisher.publish(event(AlertEvent.Type.RAISED, Alert.warning("100 queued")));
        publisher.publish(event(AlertEvent.Type.CHANGED, Alert.major("500 queued")));
        publisher.publish(event(AlertEvent.Type.REMINDED, Alert.critical("1000 queued")));
        publisher.publish(event(AlertEvent.Type.CLEARED, Alert.critical("1000 queued")));

        TestLogger logger = TestLoggerFactory.getTestLogger(Slf4jAlertsPublisher.class);
        assertThat(logger.getLoggingEvents()).extracting(LoggingEvent::getLevel)
                .containsExactly(Level.WARN, Level.ERROR, Level.ERROR, Level.INFO);
        assertThat(logger.getLoggingEvents().get(0).getFormattedMessage()).isEqualTo(
                "Alert RAISED on session " + SESSION_ID.getQualifiedName() + ", probe queue (Orders waiting): WARNING 100 queued (since " + SINCE + ")");
    }

    @Test
    void saysWhenAnAlertWasClearedManually() {
        Slf4jAlertsPublisher publisher = new Slf4jAlertsPublisher(Slf4jAlertsPublisherSettings.builder().build());

        publisher.publish(AlertEvent.builder().type(AlertEvent.Type.CLEARED).source("engine").probeId(PROBE_ID)
                .probeDescription("Orders waiting").alert(Alert.warning("100 queued")).since(SINCE)
                .time(SINCE.plusSeconds(5)).clearedManually(true).build());

        assertThat(TestLoggerFactory.getTestLogger(Slf4jAlertsPublisher.class).getLoggingEvents()).singleElement()
                .satisfies(logged -> assertThat(logged.getFormattedMessage()).startsWith("Alert CLEARED manually on session "));
    }

    @Test
    void putsTheSessionInTheMdcOnlyWhileLogging() {
        Slf4jAlertsPublisher publisher = new Slf4jAlertsPublisher(Slf4jAlertsPublisherSettings.builder().build());

        publisher.publish(event(AlertEvent.Type.RAISED, Alert.warning("100 queued")));

        LoggingEvent logged = TestLoggerFactory.getTestLogger(Slf4jAlertsPublisher.class).getLoggingEvents().get(0);
        assertThat(logged.getMdc()).containsEntry("fixSessionGroup", SESSION_ID.getGroup()).containsEntry("fixSessionName", "orders");
        assertThat(MDC.get("fixSessionName")).isNull();
    }

    @Test
    void logsUnderTheLoggerChosenForTheSessionWithoutMdcWhenDisabled() {
        Slf4jAlertsPublisher publisher = new Slf4jAlertsPublisher(Slf4jAlertsPublisherSettings.builder()
                .loggerNameForScope(scope -> "alerts." + scope.getLabels().get(FixSessionProbeScope.NAME_LABEL))
                .useMDC(false)
                .build());

        publisher.publish(event(AlertEvent.Type.RAISED, Alert.warning("100 queued")));

        assertThat(TestLoggerFactory.getTestLogger("alerts.orders").getLoggingEvents()).singleElement()
                .satisfies(logged -> assertThat(logged.getMdc()).isEmpty());
    }

    private static AlertEvent event(AlertEvent.Type type, Alert alert) {
        return AlertEvent.builder()
                .type(type)
                .source("engine")
                .probeId(PROBE_ID)
                .probeDescription("Orders waiting")
                .alert(alert)
                .since(SINCE)
                .time(SINCE.plusSeconds(5))
                .build();
    }
}
