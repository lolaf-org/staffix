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

import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.spring.boot.spi.FixMessagesLoggerSettingsContributor;
import org.lolaf.staffix.tests.TestingFixMessagesLoggerSettings;
import org.lolaf.staffix.tests.TestingLogger;

import java.util.Map;

public class TestLoggerContributor implements FixMessagesLoggerSettingsContributor {

    private final String instanceId;

    public TestLoggerContributor(String instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    public void contribute(Map<String, FixMessagesLoggerSettings> registry) {
        registry.put(instanceId, TestingFixMessagesLoggerSettings.builder()
                .instanceId(instanceId)
                .testingLogger(new TestingLogger(instanceId))
                .build());
    }
}
