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
package org.lolaf.staffix.alerting.slf4j;

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.alerting.AlertEvent;
import org.lolaf.staffix.api.alerting.AlertLevel;
import org.lolaf.staffix.api.alerting.FixAlertsPublisher;
import org.lolaf.staffix.api.alerting.FixAlertsPublisherSettings;
import org.lolaf.staffix.api.alerting.ProbeScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;

/**
 * Logs every alert change through SLF4J.
 */
public class Slf4jAlertsPublisher extends Startable.SimpleStartable<FixAlertsPublisher> implements FixAlertsPublisher {

    static final String TEMPLATE = "Alert {} on {} {}, probe {} ({}): {} {} (since {})";

    private final Slf4jAlertsPublisherSettings settings;

    protected Slf4jAlertsPublisher(Slf4jAlertsPublisherSettings settings) {
        this.settings = settings;
    }

    @Override
    public String getInstanceId() {
        return settings.getInstanceId();
    }

    @Override
    public void publish(AlertEvent event) {
        ProbeScope scope = event.getProbeId().getScope();
        Logger logger = LoggerFactory.getLogger(settings.getLoggerNameForScope().apply(scope));
        if (settings.isUseMDC()) {
            scope.getLabels().forEach(MDC::put);
        }
        try {
            logger.atLevel(levelOf(event)).log(TEMPLATE, event.isClearedManually() ? "CLEARED manually" : event.getType(), scope.getType(),
                    scope.getName(), event.getProbeId().getName(), event.getProbeDescription(), event.getAlert().getLevel(),
                    event.getAlert().getMessage(), event.getSince());
        } finally {
            if (settings.isUseMDC()) {
                scope.getLabels().keySet().forEach(MDC::remove);
            }
        }
    }

    private static Level levelOf(AlertEvent event) {
        if (event.getType() == AlertEvent.Type.CLEARED) {
            return Level.INFO;
        }
        return event.getAlert().getLevel() == AlertLevel.WARNING ? Level.WARN : Level.ERROR;
    }

    @Override
    protected void startMe() throws StartStopException {
        // nothing to do
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        // nothing to do
    }

    public static class Slf4jAlertsPublisherFactoryImpl implements FixAlertsPublisherSettings.FixAlertsPublisherFactory<Slf4jAlertsPublisherSettings> {

        @Override
        public FixAlertsPublisher newInstance(Slf4jAlertsPublisherSettings settings) {
            return new Slf4jAlertsPublisher(settings);
        }

        @Override
        public Class<Slf4jAlertsPublisherSettings> getSettingsClass() {
            return Slf4jAlertsPublisherSettings.class;
        }
    }
}
