package org.lolaf.staffix.impl;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionDesiredState;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionStatus;
import org.lolaf.staffix.tests.FixMessageFields;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Settings updated through the admin API while the session is logged out, as an operator does from the console.
 */
class TestFixSessionSettingsUpdates extends AbstractFixTests {

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

        await().untilAsserted(() -> assertThat(logonsSent(connectorType)).hasSizeGreaterThan(logonsBefore));
        String logon = logonsSent(connectorType).get(logonsBefore);
        assertThat(FixMessageFields.valuesOf(logon, CoreFields.SENDING_TIME))
                .as("SendingTime(52) to the second: %s", logon)
                .singleElement().asString().doesNotContain(".");
    }

    private FixSessionSettings logoutThroughTheAdminApi(ConnectorType connectorType) {
        FixSessionSettings settings = getFixSessionImpl(connectorType).getFixSessionSettings();
        adminApi(connectorType).logoutSession(settings.getFixSessionId());
        await().untilAsserted(() -> assertThat(getFixSession(ConnectorType.INITIATOR).isLoggedIn()).isFalse());
        return settings;
    }

    private void updateThroughTheAdminApi(ConnectorType connectorType, FixSessionSettings settings) {
        adminApi(connectorType).updateFixSessionSettings(settings.getFixSessionId(), settings);
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
