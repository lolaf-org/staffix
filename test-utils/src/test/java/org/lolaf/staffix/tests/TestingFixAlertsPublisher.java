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
package org.lolaf.staffix.tests;

import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.alerting.AlertEvent;
import org.lolaf.staffix.api.alerting.FixAlertsPublisher;
import org.lolaf.staffix.api.alerting.FixAlertsPublisherSettings;

@RequiredArgsConstructor
public class TestingFixAlertsPublisher extends Startable.VoidStartable<FixAlertsPublisher> implements FixAlertsPublisher {

    private final TestingFixAlertsPublisherSettings settings;

    @Override
    public String getInstanceId() {
        return settings.getInstanceId();
    }

    @Override
    public void publish(AlertEvent event) {
        settings.getEvents().add(event);
    }

    public static class TestingFixAlertsPublisherFactory implements FixAlertsPublisherSettings.FixAlertsPublisherFactory<TestingFixAlertsPublisherSettings> {

        @Override
        public Class<TestingFixAlertsPublisherSettings> getSettingsClass() {
            return TestingFixAlertsPublisherSettings.class;
        }

        @Override
        public FixAlertsPublisher newInstance(TestingFixAlertsPublisherSettings settings) {
            return new TestingFixAlertsPublisher(settings);
        }
    }
}
