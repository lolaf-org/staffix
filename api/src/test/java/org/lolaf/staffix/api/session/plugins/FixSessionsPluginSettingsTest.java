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
package org.lolaf.staffix.api.session.plugins;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.monitoring.FixSessionMonitoringManagerSettings;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager;

import static org.assertj.core.api.Assertions.assertThat;

class FixSessionsPluginSettingsTest {

    interface AlphaPlugin extends FixSessionsPlugin<PluginContext> {
    }

    static class AlphaSettings implements FixSessionsPluginSettings<AlphaPlugin> {
        @Override
        public String getInstanceId() {
            return "alpha";
        }
    }

    static class BetaSettings extends AlphaSettings {
    }

    static class AlphaMonitoringSettings implements FixSessionMonitoringManagerSettings {
        @Override
        public String getInstanceId() {
            return "metrics";
        }
    }

    @Test
    void servesTheDeclaredPluginTypeByDefault() {
        assertThat(new AlphaSettings().getPluginTypes()).containsExactly(AlphaPlugin.class);
        assertThat(new BetaSettings().getPluginTypes()).containsExactly(AlphaPlugin.class);
    }

    @Test
    void aMonitoringPluginServesTheMonitoringManagerType() {
        assertThat(new AlphaMonitoringSettings().getPluginTypes()).containsExactly(FixSessionsMonitoringManager.class);
    }
}
