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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.spring.boot.actuator.health.FixSessionsHealthIndicator;
import org.lolaf.staffix.spring.boot.actuator.spring.ActuatorMonitoringProps;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import org.assertj.core.api.InstanceOfAssertFactories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FixSessionsHealthIndicatorTest {

    private static final FixSessionId SESSION_ID =
            FixSessionId.of("test", FixRegularVersion.VERSION_44, "HEALTH_SENDER", "HEALTH_TARGET");

    private final ActuatorSessionsRegistry registry = new ActuatorSessionsRegistry();
    private final ActuatorMonitoringProps props = new ActuatorMonitoringProps();
    private final FixSessionsHealthIndicator indicator = new FixSessionsHealthIndicator(registry, props);

    private ActuatorSessionStats session(boolean withinSessionTime, FixSessionState desiredState) {
        FixSession fixSession = mock(FixSession.class);
        when(fixSession.getFixSessionId()).thenReturn(SESSION_ID);
        when(fixSession.isWithinSessionTime()).thenReturn(withinSessionTime);
        when(fixSession.getDesiredState()).thenReturn(desiredState);
        return registry.register("instance", fixSession);
    }

    @Test
    void aSessionStateDoesNotContributeByDefault() {
        session(true, FixSessionState.LOGGED_IN);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aSessionThatShouldBeLoggedInAndIsNotIsDown() {
        props.setFixSessionStateContributesToHealthStatus(true);
        session(true, FixSessionState.LOGGED_IN);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get(SESSION_ID.getId())).asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("status", "DOWN")
                .containsEntry("state", "DISCONNECTED")
                .containsEntry("desiredState", "LOGGED_IN")
                .containsEntry("withinSessionTime", true);
    }

    @Test
    void aLoggedInSessionIsUp() {
        props.setFixSessionStateContributesToHealthStatus(true);
        session(true, FixSessionState.LOGGED_IN).onLogon();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aSessionOutsideItsScheduleIsUp() {
        props.setFixSessionStateContributesToHealthStatus(true);
        session(false, FixSessionState.LOGGED_IN).onLogout();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aSessionLoggedOutOnPurposeIsUp() {
        props.setFixSessionStateContributesToHealthStatus(true);
        session(true, FixSessionState.LOGGED_OUT).onLogout();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }
}
