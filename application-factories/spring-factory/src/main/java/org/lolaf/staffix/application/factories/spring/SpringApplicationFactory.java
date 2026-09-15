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
package org.lolaf.staffix.application.factories.spring;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationFactory;
import org.lolaf.staffix.api.application.FixApplicationFactorySettings;
import org.springframework.context.ApplicationContext;

import java.util.HashSet;
import java.util.Set;

/**
 * Resolves each session's {@code FixApplication} from the Spring context, so it is a managed bean with its own
 * dependencies injected.
 */
@Slf4j
public class SpringApplicationFactory extends Startable.SimpleStartable<FixApplicationFactory> implements FixApplicationFactory {

    @Getter
    private final String instanceId;
    private final ApplicationContext applicationContext;
    private final Set<FixApplication> usedApps;

    private SpringApplicationFactory(SpringApplicationFactorySettings springApplicationFactorySettings) {
        this.instanceId = springApplicationFactorySettings.getInstanceId();
        this.applicationContext = springApplicationFactorySettings.getApplicationContext();
        this.usedApps = new HashSet<>();
    }

    @Override
    protected void startMe() throws StartStopException {
        // nothing to do
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        usedApps.forEach(a -> {
            try {
                a.destroy();
            } catch (Exception e) {
                log.warn("Error destroying application", e);
            }
        });
    }

    @Override
    public FixApplication getInstance(String applicationId) {
        if (!applicationContext.containsBean(applicationId)) {
            throw new IllegalArgumentException(String.format("No FixApplication bean named '%s' in ApplicationContext. Available FixApplication beans: '%s'",
                    applicationId, String.join(",", applicationContext.getBeanNamesForType(FixApplication.class))));
        }
        FixApplication app = applicationContext.getBean(applicationId, FixApplication.class);
        usedApps.add(app);
        return app;
    }

    public static class SpringApplicationFactoryImpl implements FixApplicationFactorySettings.FixApplicationFactoryFactory<SpringApplicationFactorySettings> {

        @Override
        public FixApplicationFactory newInstance(SpringApplicationFactorySettings settings) {
            return new SpringApplicationFactory(settings);
        }

        @Override
        public Class<SpringApplicationFactorySettings> getSettingsClass() {
            return SpringApplicationFactorySettings.class;
        }
    }
}
