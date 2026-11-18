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
package org.lolaf.staffix.plugins.async;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncFixSessionsPluginTest {

    @Test
    void unwrapsToThePluginItWraps() {
        FixSessionsMonitoringManager monitoring = mock(FixSessionsMonitoringManager.class);
        when(monitoring.unwrap(FixSessionsMonitoringManager.class)).thenCallRealMethod();
        FixSessionsPluginSettings<?> delegateSettings = mock(FixSessionsPluginSettings.class);
        when(delegateSettings.instance()).thenAnswer(invocation -> monitoring);

        AsyncFixSessionsPlugin<?> async = new AsyncFixSessionsPlugin<>(AsyncFixSessionsPluginSettings.builder()
                .delegateSettings(delegateSettings).build());

        assertThat(async.unwrap(FixSessionsMonitoringManager.class)).containsSame(monitoring);
        assertThat(async.unwrap(AsyncFixSessionsPlugin.class)).containsSame(async);
        assertThat(async.unwrap(String.class)).isEmpty();
    }
}
