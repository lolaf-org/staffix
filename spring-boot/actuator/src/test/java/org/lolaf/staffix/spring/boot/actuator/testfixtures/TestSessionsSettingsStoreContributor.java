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
package org.lolaf.staffix.spring.boot.actuator.testfixtures;

import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStoreSettings;
import org.lolaf.staffix.spring.boot.spi.FixSessionsSettingsStoreSettingsContributor;
import org.lolaf.staffix.tests.TestingFixSessionsSettingsStoreSettings;

import java.util.List;
import java.util.Map;

public class TestSessionsSettingsStoreContributor implements FixSessionsSettingsStoreSettingsContributor {

    private final String instanceId;
    private final List<FixSessionSettings> sessions;

    public TestSessionsSettingsStoreContributor(String instanceId, List<FixSessionSettings> sessions) {
        this.instanceId = instanceId;
        this.sessions = sessions;
    }

    @Override
    public void contribute(Map<String, FixSessionsSettingsStoreSettings> registry) {
        TestingFixSessionsSettingsStoreSettings.TestingFixSessionsSettingsStoreSettingsBuilder b =
                TestingFixSessionsSettingsStoreSettings.builder()
                        .instanceId(instanceId);
        sessions.forEach(b::session);
        registry.put(instanceId, b.build());
    }
}
