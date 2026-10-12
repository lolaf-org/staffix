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
package org.lolaf.staffix.spring.boot.testfixtures;

import lombok.Getter;
import org.lolaf.staffix.api.alerting.FixAlertsPublisherSettings;
import org.lolaf.staffix.spring.boot.spi.FixAlertsPublisherSettingsContributor;
import org.lolaf.staffix.tests.TestingFixAlertsPublisherSettings;

import java.util.Map;

public class TestAlertsPublisherContributor implements FixAlertsPublisherSettingsContributor {

    @Getter
    private final TestingFixAlertsPublisherSettings settings;

    public TestAlertsPublisherContributor(String instanceId) {
        this.settings = TestingFixAlertsPublisherSettings.builder().instanceId(instanceId).build();
    }

    @Override
    public void contribute(Map<String, FixAlertsPublisherSettings> registry) {
        registry.put(settings.getInstanceId(), settings);
    }
}
