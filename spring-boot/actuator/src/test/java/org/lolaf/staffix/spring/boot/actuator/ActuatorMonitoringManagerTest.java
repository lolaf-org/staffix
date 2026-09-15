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
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.tests.TestingClock;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActuatorMonitoringManagerTest {

    private static final FixSessionId SESSION_ID =
            FixSessionId.of("test", FixRegularVersion.VERSION_44, "ACT_TEST_SENDER", "ACT_TEST_TARGET");

    private final MessageType anyMsgType = mock(MessageType.class);
    private final TestingClock clock = new TestingClock();

    @Test
    void registersStatsOnSessionCreatedAndCapturesLifecycleEvents() {
        ActuatorSessionsRegistry registry = new ActuatorSessionsRegistry();
        ActuatorMonitoringManager manager = new ActuatorMonitoringManager(
                ActuatorMonitoringManagerSettings.builder()
                        .registry(registry)
                        .build());
        manager.start();

        FixSession session = mock(FixSession.class);
        when(session.getFixSessionId()).thenReturn(SESSION_ID);

        Optional<FixSessionPlugin<PluginContext.VoidPluginContext, Void>> plugin =
                manager.onSessionCreated("instance-1", session, List.of(), List.of());

        assertThat(plugin).isPresent();
        assertThat(registry.find(SESSION_ID)).isPresent();
        ActuatorSessionStats stats = registry.find(SESSION_ID).orElseThrow();
        assertThat(stats.getState()).isNull();
        assertThat(stats.getMessagesReceived()).isZero();

        FixSessionPlugin<?, ?> p = plugin.get();
        p.onLogon();
        p.onMessageReceived(anyMsgType, 128, 0L, clock.now());
        p.onMessageReceived(anyMsgType, 64, 0L, clock.now());
        p.onMessageSent(anyMsgType, 256, 0L, clock.now());

        assertThat(stats.getState()).isEqualTo(FixSessionState.LOGGED_IN);
        assertThat(stats.getMessagesReceived()).isEqualTo(2);
        assertThat(stats.getMessagesSent()).isEqualTo(1);
        assertThat(stats.getBytesReceived()).isEqualTo(192);
        assertThat(stats.getBytesSent()).isEqualTo(256);
        assertThat(stats.getLastLogonEpochMillis()).isPositive();

        p.onLogout();
        assertThat(stats.getState()).isEqualTo(FixSessionState.LOGGED_OUT);
    }

    @Test
    void stopClearsRegistry() {
        ActuatorSessionsRegistry registry = new ActuatorSessionsRegistry();
        ActuatorMonitoringManager manager = new ActuatorMonitoringManager(
                ActuatorMonitoringManagerSettings.builder().registry(registry).build());
        manager.start();

        FixSession session = mock(FixSession.class);
        when(session.getFixSessionId()).thenReturn(SESSION_ID);
        manager.onSessionCreated("instance-1", session, List.of(), List.of());
        assertThat(registry.snapshot()).hasSize(1);

        manager.stop(Deadline.of(Duration.ofSeconds(1)));
        assertThat(registry.snapshot()).isEmpty();
    }
}
