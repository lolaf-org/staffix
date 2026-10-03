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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActuatorSessionsRegistryTest {

    private static final FixSessionId ALPHA_TRADING = sessionId("alpha", "ALPHA");
    private static final FixSessionId BETA_TRADING = sessionId("beta", "BETA");

    private final ActuatorSessionsRegistry registry = new ActuatorSessionsRegistry();

    @Test
    void tellsApartSessionsSharingAnIdInDifferentGroups() {
        register(ALPHA_TRADING);
        register(BETA_TRADING);

        assertThat(registry.find("alpha", "trading")).hasValueSatisfying(s -> assertThat(s.getFixSession().getFixSessionId()).isSameAs(ALPHA_TRADING));
        assertThat(registry.find("beta", "trading")).hasValueSatisfying(s -> assertThat(s.getFixSession().getFixSessionId()).isSameAs(BETA_TRADING));
        assertThat(registry.find("gamma", "trading")).isEmpty();
    }

    @Test
    void reportsEverySessionInTheHealthDetails() {
        register(ALPHA_TRADING);
        register(BETA_TRADING);

        assertThat(new FixSessionsHealthIndicator(registry, new ActuatorMonitoringProps()).health().getDetails())
                .containsOnlyKeys("alpha.trading", "beta.trading");
    }

    private void register(FixSessionId fixSessionId) {
        FixSession fixSession = mock(FixSession.class);
        when(fixSession.getFixSessionId()).thenReturn(fixSessionId);
        when(fixSession.getDesiredState()).thenReturn(FixSessionState.LOGGED_IN);
        registry.register("instance", fixSession);
    }

    private static FixSessionId sessionId(String group, String targetCompId) {
        return FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                .name("trading").group(group).senderCompID("US").targetCompID(targetCompId).build());
    }
}
