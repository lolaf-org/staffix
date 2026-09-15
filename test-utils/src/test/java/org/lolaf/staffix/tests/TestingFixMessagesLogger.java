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

import lombok.AllArgsConstructor;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;

@AllArgsConstructor
public class TestingFixMessagesLogger extends Startable.VoidStartable<FixMessagesLogger> implements FixMessagesLogger {

    private TestingFixMessagesLoggerSettings testingFixMessagesLoggerSettings;

    @Override
    public Logger getLogger(String fixInstanceId, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry) {
        return testingFixMessagesLoggerSettings.getTestingLogger();
    }

    @Override
    public String getInstanceId() {
        return testingFixMessagesLoggerSettings.getInstanceId();
    }

    public static class TestingFixMessagesLoggerFactoryImpl implements FixMessagesLoggerSettings.FixMessagesLoggerFactory<TestingFixMessagesLoggerSettings> {

        @Override
        public FixMessagesLogger newInstance(TestingFixMessagesLoggerSettings settings) {
            return new TestingFixMessagesLogger(settings);
        }

        @Override
        public Class<TestingFixMessagesLoggerSettings> getSettingsClass() {
            return TestingFixMessagesLoggerSettings.class;
        }
    }
}
