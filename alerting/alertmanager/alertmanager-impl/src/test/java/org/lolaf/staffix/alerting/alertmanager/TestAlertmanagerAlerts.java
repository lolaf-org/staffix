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
package org.lolaf.staffix.alerting.alertmanager;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.AlertEvent;
import org.lolaf.staffix.api.session.FixSessionId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.lolaf.staffix.api.alerting.AlertEvent.Type.CHANGED;
import static org.lolaf.staffix.api.alerting.AlertEvent.Type.CLEARED;
import static org.lolaf.staffix.api.alerting.AlertEvent.Type.RAISED;

class TestAlertmanagerAlerts {

    static final FixSessionId SESSION_ID = FixSessionId.of("orders", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
    private static final Instant SINCE = Instant.parse("2026-10-11T10:00:00Z");
    private static final Duration RESEND = Duration.ofMinutes(1);

    private final AlertmanagerAlerts alerts = new AlertmanagerAlerts(Map.of("env", "prod", "severity", "ignored"), RESEND);

    static AlertEvent event(AlertEvent.Type type, Alert alert, Instant time) {
        return AlertEvent.builder()
                .type(type)
                .engineId("engine")
                .fixSessionId(SESSION_ID)
                .probeName("queue")
                .probeDescription("Orders waiting")
                .alert(alert)
                .since(SINCE)
                .time(time)
                .build();
    }

    @Test
    void aRaisedAlertIsPostedWithItsLabelsAndEndsAtFourResendsAhead() {
        Instant now = SINCE.plusSeconds(1);

        List<PostableAlert> posted = alerts.apply(List.of(event(RAISED, Alert.warning("100 queued"), SINCE)), now);

        assertThat(posted).singleElement().satisfies(alert -> {
            assertThat(alert.getLabels()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "env", "prod",
                    "alertname", "queue",
                    "engine", "engine",
                    "fixSessionGroup", SESSION_ID.getGroup(),
                    "fixSessionName", "orders",
                    "severity", "warning"));
            assertThat(alert.getAnnotations()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "summary", "100 queued", "description", "Orders waiting"));
            assertThat(alert.getStartsAt()).isEqualTo(SINCE);
            assertThat(alert.getEndsAt()).isEqualTo(now.plus(RESEND.multipliedBy(4)));
        });
    }

    @Test
    void aLevelChangeResolvesTheAlertAtThePreviousSeverity() {
        alerts.apply(List.of(event(RAISED, Alert.warning("100 queued"), SINCE)), SINCE);
        Instant changedAt = SINCE.plusSeconds(10);

        List<PostableAlert> posted = alerts.apply(List.of(event(CHANGED, Alert.major("1000 queued"), changedAt)), changedAt);

        assertThat(posted).extracting(alert -> alert.getLabels().get("severity"), PostableAlert::getEndsAt)
                .containsExactlyInAnyOrder(
                        tuple("major", changedAt.plus(RESEND.multipliedBy(4))),
                        tuple("warning", changedAt));
    }

    @Test
    void aClearedAlertEndsAtItsClearTimeAndIsResentForFifteenMinutes() {
        alerts.apply(List.of(event(RAISED, Alert.warning("100 queued"), SINCE)), SINCE);
        Instant clearedAt = SINCE.plusSeconds(30);

        assertThat(alerts.apply(List.of(event(CLEARED, Alert.warning("100 queued"), clearedAt)), clearedAt))
                .singleElement().extracting(PostableAlert::getEndsAt).isEqualTo(clearedAt);
        assertThat(alerts.all(clearedAt.plus(AlertmanagerAlerts.RESOLVED_RETENTION)))
                .singleElement().extracting(PostableAlert::getEndsAt).isEqualTo(clearedAt);
        assertThat(alerts.all(clearedAt.plus(AlertmanagerAlerts.RESOLVED_RETENTION).plusSeconds(1))).isEmpty();
    }

    @Test
    void anAlertRaisedAgainIsNoLongerResentAsResolved() {
        alerts.apply(List.of(event(RAISED, Alert.warning("100 queued"), SINCE)), SINCE);
        alerts.apply(List.of(event(CLEARED, Alert.warning("100 queued"), SINCE.plusSeconds(1))), SINCE.plusSeconds(1));
        Instant raisedAgain = SINCE.plusSeconds(2);

        alerts.apply(List.of(event(RAISED, Alert.warning("200 queued"), raisedAgain)), raisedAgain);

        assertThat(alerts.all(raisedAgain)).singleElement().satisfies(alert -> {
            assertThat(alert.getEndsAt()).isAfter(raisedAgain);
            assertThat(alert.getAnnotations()).containsEntry("summary", "200 queued");
        });
    }

    @Test
    void aBatchPostsOnlyTheLatestStateOfEachAlert() {
        Instant now = SINCE.plusSeconds(1);

        List<PostableAlert> posted = alerts.apply(List.of(
                event(RAISED, Alert.warning("100 queued"), SINCE),
                event(CLEARED, Alert.warning("100 queued"), now)), now);

        assertThat(posted).singleElement().extracting(PostableAlert::getEndsAt).isEqualTo(now);
    }
}
