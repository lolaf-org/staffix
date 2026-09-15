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
package org.lolaf.staffix.application.factories.simple;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationFactory;
import org.lolaf.staffix.api.application.FixApplicationFactorySettings;

import java.util.Map;

/**
 * Builds a {@code FixApplication} from a class name, one instance per session, through its no-argument
 * constructor.
 */
@Slf4j
public class SimpleApplicationFactory extends Startable.SimpleStartable<FixApplicationFactory> implements FixApplicationFactory {

    @Getter
    private final String instanceId;
    private final Map<String, FixApplication> applications;

    private SimpleApplicationFactory(SimpleApplicationFactorySettings simpleApplicationFactorySettings) {
        instanceId = simpleApplicationFactorySettings.getInstanceId();
        applications = simpleApplicationFactorySettings.getApplications();
    }

    @Override
    protected void startMe() throws StartStopException {
        // nothing ot do
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        applications.values().forEach(a -> {
            try {
                a.destroy();
            } catch (Exception e) {
                log.warn("Error destroying application", e);
            }
        });
    }

    @Override
    public FixApplication getInstance(String applicationId) {
        FixApplication app = applications.get(applicationId);
        if (app == null) {
            throw new IllegalArgumentException(String.format("Unable to find any FIX application for id '%s' within: '%s'",
                    applicationId, String.join(",", applications.keySet())));
        }
        return app;
    }

    public static class SimpleApplicationFactoryImpl implements FixApplicationFactorySettings.FixApplicationFactoryFactory<SimpleApplicationFactorySettings> {

        @Override
        public FixApplicationFactory newInstance(SimpleApplicationFactorySettings settings) {
            return new SimpleApplicationFactory(settings);
        }

        @Override
        public Class<SimpleApplicationFactorySettings> getSettingsClass() {
            return SimpleApplicationFactorySettings.class;
        }
    }
}
