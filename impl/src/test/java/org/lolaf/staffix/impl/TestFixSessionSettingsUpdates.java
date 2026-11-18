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
package org.lolaf.staffix.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionDesiredState;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionStatus;
import org.lolaf.staffix.impl.session.FixSessionImpl;
import org.lolaf.staffix.tests.FixMessageFields;
import org.lolaf.staffix.tests.TestingClock;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Settings updated or removed through the admin API, as an operator does from the console.
 */
class TestFixSessionSettingsUpdates extends AbstractFixTests {

    private static final ZonedDateTime SESSION_TIME = ZonedDateTime.of(2026, 3, 9, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void anInitiatorUpdatedWhileLoggedOutLogsOnWithTheNewSettings() {
        assertTheNextLogonUsesTheUpdate(ConnectorType.INITIATOR);
    }

    @Test
    void anAcceptorUpdatedWhileLoggedOutLogsOnWithTheNewSettings() {
        assertTheNextLogonUsesTheUpdate(ConnectorType.ACCEPTOR);
    }

    @Test
    void anInitiatorLoggedOutByAnOperatorStaysLoggedOutAcrossAnUpdate() {
        setupInitiatorSessionSettings(s -> s.desiredSessionState(FixSessionDesiredState.LOGGED_IN).build());
        logonClient();
        FixSessionSettings settings = logoutThroughTheAdminApi(ConnectorType.INITIATOR);

        updateThroughTheAdminApi(ConnectorType.INITIATOR, settings.toBuilder().sendingTimeAccuracy(TimeUnit.SECONDS).build());

        assertThat(currentSession(ConnectorType.INITIATOR, settings.getFixSessionId()).getStatus())
                .isEqualTo(FixSessionStatus.LOGGED_OUT_BY_OPERATOR);
    }

    /**
     * The acceptor answers nothing to a Logon it refuses, so the initiator timing out on it, and logging on again, is
     * what shows the refusal.
     */
    @Test
    void anAcceptorLoggedOutByAnOperatorStaysLoggedOutAcrossAnUpdate() {
        setupInitiatorSessionSettings(s -> s.logInOrOutResponseTimeout(Duration.ofMillis(300)).build());
        logonClient();
        FixSessionSettings settings = logoutThroughTheAdminApi(ConnectorType.ACCEPTOR);

        updateThroughTheAdminApi(ConnectorType.ACCEPTOR, settings.toBuilder().sendingTimeAccuracy(TimeUnit.SECONDS).build());
        int logonsBefore = logonsSent(ConnectorType.INITIATOR).size();
        getFixSession(ConnectorType.INITIATOR).logon();

        await().untilAsserted(() -> assertThat(logonsSent(ConnectorType.INITIATOR)).hasSizeGreaterThan(logonsBefore + 1));
        assertThat(getFixSession(ConnectorType.INITIATOR).isLoggedIn()).isFalse();
        assertThat(currentSession(ConnectorType.ACCEPTOR, settings.getFixSessionId()).getStatus())
                .isEqualTo(FixSessionStatus.LOGGED_OUT_BY_OPERATOR);
    }

    /**
     * The initiator's settings say logged out, as does the operator's logout, so only the update can log it on.
     */
    @Test
    void anUpdateChangingTheDesiredStateOverridesTheOperatorsLogout() {
        logonClient();
        FixSessionSettings settings = logoutThroughTheAdminApi(ConnectorType.INITIATOR);
        assertThat(settings.getDesiredSessionState()).isEqualTo(FixSessionDesiredState.LOGGED_OUT);

        updateThroughTheAdminApi(ConnectorType.INITIATOR, settings.toBuilder()
                .desiredSessionState(FixSessionDesiredState.LOGGED_IN)
                .build());

        await().untilAsserted(() -> assertThat(currentSession(ConnectorType.INITIATOR, settings.getFixSessionId()).isLoggedIn()).isTrue());
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void aRestartAppliesTheUpdateToTheLiveSessionAtOnce(ConnectorType connectorType) {
        logonClient();
        FixSessionImpl liveSession = getFixSessionImpl(connectorType);
        FixSessionSettings settings = liveSession.getFixSessionSettings();
        int logonsBefore = logonsSent(connectorType).size();

        adminApi(connectorType).updateFixSessionSettings(settings.getFixSessionId(),
                settings.toBuilder().sendingTimeAccuracy(TimeUnit.SECONDS).build(), true);

        assertThat(currentSession(connectorType, settings.getFixSessionId())).isNotSameAs(liveSession);
        assertTheLogonAfterUsesTheUpdate(connectorType, logonsBefore, "Restarted by the admin API on its pending settings");
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void aRemovalStopsTheLiveSessionForGood(ConnectorType connectorType) {
        logonClient();
        FixSessionImpl liveSession = getFixSessionImpl(connectorType);
        int logonsBefore = logonsSent(connectorType).size();

        removeThroughTheAdminApi(connectorType);

        await().untilAsserted(() -> assertThat(liveSession.isConnected()).isFalse());
        await().during(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(logonsSent(connectorType)).hasSize(logonsBefore));
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void aHeldUpdateAppliesAfterAnOperatorsLogoutAndLogon(ConnectorType connectorType) {
        logonClient();
        int logonsBefore = holdAnUpdateOfTheLiveSession(connectorType);

        FixSessionSettings settings = logoutThroughTheAdminApi(connectorType);
        adminApi(connectorType).logonSession(settings.getFixSessionId());
        if (connectorType == ConnectorType.ACCEPTOR) {
            getFixSession(ConnectorType.INITIATOR).logon();
        }

        assertTheLogonAfterUsesTheUpdate(connectorType, logonsBefore, "Rebuilt on its pending settings");
    }

    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void aHeldUpdateAppliesOnReconnection(ConnectorType connectorType) {
        logonClient();
        int logonsBefore = holdAnUpdateOfTheLiveSession(connectorType);

        getFixSessionImpl(connectorType).disconnect();

        assertTheLogonAfterUsesTheUpdate(connectorType, logonsBefore, "Rebuilt on its pending settings");
    }

    /**
     * The initiator answers the acceptor's silence outside the window by timing out and logging on again, which is
     * what brings it back once the window reopens.
     */
    @ParameterizedTest
    @MethodSource("initiatorOrAcceptorParams")
    void aHeldUpdateAppliesWhenTheSessionTimeWindowReopens(ConnectorType connectorType) {
        TestingClock clock = connectorType.select(fixInitiatorClock, fixAcceptorClock);
        clock.toAdvanceableTime(SESSION_TIME.toInstant());
        if (connectorType == ConnectorType.ACCEPTOR) {
            setupInitiatorSessionSettings(s -> s.logInOrOutResponseTimeout(Duration.ofMillis(300)).build());
        }
        LocalTime base = SESSION_TIME.toLocalTime();
        setupSessionSettings(connectorType, s -> s
                .sessionScheduleSettings(weeklyWindow(base.minusSeconds(2), base.plusSeconds(3)))
                .desiredSessionState(FixSessionDesiredState.LOGGED_IN)
                .build());
        logonClient();
        int logonsBefore = holdAnUpdateOfTheLiveSession(connectorType);

        clock.advance(Duration.ofSeconds(4));
        await().untilAsserted(() -> assertThat(getLogger(connectorType).getEvents()).contains("FIX session outside of timeframe"));
        clock.advance(Duration.ofDays(7).minusSeconds(5));

        assertTheLogonAfterUsesTheUpdate(connectorType, logonsBefore, "Rebuilt on its pending settings");
    }

    private static FixSessionSettings.SessionScheduleSettings weeklyWindow(LocalTime start, LocalTime end) {
        return FixSessionSettings.SessionScheduleSettings.builder()
                .timeZone(TimeZone.getTimeZone("UTC"))
                .sessionSchedule(FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(SESSION_TIME.getDayOfWeek())
                        .startTime(start)
                        .endDay(SESSION_TIME.getDayOfWeek())
                        .endTime(end)
                        .build())
                .build();
    }

    /**
     * @return the number of Logons the side had sent before the update
     */
    private int holdAnUpdateOfTheLiveSession(ConnectorType connectorType) {
        FixSessionImpl liveSession = getFixSessionImpl(connectorType);
        FixSessionSettings settings = liveSession.getFixSessionSettings();
        assertThat(settings.getSendingTimeAccuracy()).isNotEqualTo(TimeUnit.SECONDS);
        int logonsBefore = logonsSent(connectorType).size();

        updateThroughTheAdminApi(connectorType, settings.toBuilder().sendingTimeAccuracy(TimeUnit.SECONDS).build());

        assertThat(currentSession(connectorType, settings.getFixSessionId())).as("held, not restarted").isSameAs(liveSession);
        assertThat(liveSession.hasPendingSettingsUpdate()).isTrue();
        await().untilAsserted(() -> assertThat(getLogger(connectorType).getEvents())
                .contains("Settings updated, applying them when it next connects"));
        return logonsBefore;
    }

    private void assertTheNextLogonUsesTheUpdate(ConnectorType connectorType) {
        logonClient();
        FixSessionSettings settings = logoutThroughTheAdminApi(connectorType);
        assertThat(settings.getSendingTimeAccuracy()).isNotEqualTo(TimeUnit.SECONDS);
        int logonsBefore = logonsSent(connectorType).size();

        updateThroughTheAdminApi(connectorType, settings.toBuilder().sendingTimeAccuracy(TimeUnit.SECONDS).build());
        adminApi(connectorType).logonSession(settings.getFixSessionId());
        if (connectorType == ConnectorType.ACCEPTOR) {
            getFixSession(ConnectorType.INITIATOR).logon();
        }

        assertTheLogonAfterUsesTheUpdate(connectorType, logonsBefore, "Rebuilt on updated settings");
    }

    private void assertTheLogonAfterUsesTheUpdate(ConnectorType connectorType, int logonsBefore, String rebuildEvent) {
        await().untilAsserted(() -> assertThat(logonsSent(connectorType)).hasSizeGreaterThan(logonsBefore));
        String logon = logonsSent(connectorType).get(logonsBefore);
        assertThat(FixMessageFields.valuesOf(logon, CoreFields.SENDING_TIME))
                .as("SendingTime(52) to the second: %s", logon)
                .singleElement().asString().doesNotContain(".");
        FixSession running = connectorType == ConnectorType.INITIATOR ? fixInitiator.getSession()
                : fixAcceptor.getSessions().iterator().next();
        assertThat(running.hasPendingSettingsUpdate()).isFalse();
        await().untilAsserted(() -> assertThat(getLogger(connectorType).getEvents()).contains(rebuildEvent));
    }

    private FixSessionSettings logoutThroughTheAdminApi(ConnectorType connectorType) {
        FixSessionSettings settings = getFixSessionImpl(connectorType).getFixSessionSettings();
        adminApi(connectorType).logoutSession(settings.getFixSessionId());
        await().untilAsserted(() -> assertThat(getFixSession(ConnectorType.INITIATOR).isLoggedIn()).isFalse());
        await().untilAsserted(() -> assertThat(getFixSession(connectorType).isConnected()).isFalse());
        return settings;
    }

    private void updateThroughTheAdminApi(ConnectorType connectorType, FixSessionSettings settings) {
        adminApi(connectorType).updateFixSessionSettings(settings.getFixSessionId(), settings, false);
    }

    private void removeThroughTheAdminApi(ConnectorType connectorType) {
        FixSessionSettings settings = getFixSessionImpl(connectorType).getFixSessionSettings();
        adminApi(connectorType).removeFixSessionSettings(settings.getFixSessionId(), settings.getFixSessionType());
    }

    private AdminApi adminApi(ConnectorType connectorType) {
        return (AdminApi) connectorType.select(initiatorFixEngine, acceptorFixEngine);
    }

    private FixSession currentSession(ConnectorType connectorType, FixSessionId fixSessionId) {
        if (connectorType == ConnectorType.INITIATOR) {
            return fixInitiator.getSession();
        }
        return fixAcceptor.getSessions().stream()
                .filter(session -> session.getFixSessionId().equals(fixSessionId))
                .findFirst().orElseThrow();
    }

    private List<String> logonsSent(ConnectorType connectorType) {
        return getLogger(connectorType).getOutgoingMessages().stream()
                .filter(message -> FixMessageFields.hasFieldWithValue(message, CoreFields.MESSAGE_TYPE, CoreMessageType.LOGON.code()))
                .collect(Collectors.toList());
    }
}
