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

import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringContext;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.tests.TestingFixMessagesLoggerSettings;
import org.lolaf.staffix.tests.TestingFixMessagesStoreSettings;
import org.lolaf.staffix.tests.TestingFixSessionMonitoringManagerSettings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSessionMeters extends AbstractFixTests {

    @Test
    @SuppressWarnings("unchecked")
    void theSessionsStoreAndLoggerGetItsMetersOnceItsPluginsExist() {
        FixSessionsMonitoringManager monitoring = mock(FixSessionsMonitoringManager.class);
        FixSessionPlugin<FixSessionsMonitoringContext, Void> plugin = mock(FixSessionPlugin.class);
        FixSessionsMonitoringContext sessionMeters = mock(FixSessionsMonitoringContext.class);
        when(plugin.isForPluginContext(FixSessionsMonitoringContext.class)).thenReturn(true);
        when(plugin.getPluginContext()).thenReturn(Optional.of(sessionMeters));
        when(monitoring.matchesPluginClass(FixSessionsMonitoringManager.class)).thenReturn(true);
        doReturn(Optional.of(plugin)).when(monitoring).onSessionCreated(any(), any(), any(), any(), any());
        RecordingStore store = new RecordingStore(initiatorMessagesStore);
        RecordingLogger logger = new RecordingLogger(initiatorLogger);
        initiatorFixEngineBuilder = initiatorFixEngineBuilder.toBuilder()
                .clearFixMessagesStores()
                .fixMessagesStore(TestingFixMessagesStoreSettings.builder().testingFixSessionMessagesStore(store).build())
                .clearFixMessagesLoggers()
                .fixMessagesLogger(TestingFixMessagesLoggerSettings.builder().testingLogger(logger).build())
                .fixSessionsPlugin(TestingFixSessionMonitoringManagerSettings.builder().instanceId("monitoring").mock(monitoring).build())
                .build();

        setupInitiatorSessionSettings(b -> b.fixSessionPluginsInstanceId(FixSessionsMonitoringManager.class, "monitoring").build());
        logonClient();

        assertThat(store.sessionMeters).containsExactly(sessionMeters);
        assertThat(logger.sessionMeters).containsExactly(sessionMeters);
    }

    private interface SessionMetersHook {

        void onSessionMeters(FixSessionsMonitoringContext meters);
    }

    @RequiredArgsConstructor
    private static final class RecordingStore implements FixSessionMessagesStore {

        @Delegate(excludes = SessionMetersHook.class)
        private final FixSessionMessagesStore store;
        private final List<FixSessionsMonitoringContext> sessionMeters = new CopyOnWriteArrayList<>();

        @Override
        public void onSessionMeters(FixSessionsMonitoringContext meters) {
            sessionMeters.add(meters);
        }
    }

    @RequiredArgsConstructor
    private static final class RecordingLogger implements FixMessagesLogger.Logger {

        @Delegate(excludes = SessionMetersHook.class)
        private final FixMessagesLogger.Logger logger;
        private final List<FixSessionsMonitoringContext> sessionMeters = new CopyOnWriteArrayList<>();

        @Override
        public void onSessionMeters(FixSessionsMonitoringContext meters) {
            sessionMeters.add(meters);
        }
    }
}
