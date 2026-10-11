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
package org.lolaf.staffix.alerting.alertmanager;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.alerting.AlertEvent;
import org.lolaf.staffix.api.alerting.FixAlertsPublisher;
import org.lolaf.staffix.api.alerting.FixAlertsPublisherSettings;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Posts each alert change to Alertmanager at once, and every ongoing alert again at each resend interval. Posting
 * runs on its own thread, so a slow Alertmanager never delays the probes; events arriving while a post is in flight
 * are sent together in the next one.
 */
@Slf4j
public class AlertmanagerAlertsPublisher extends Startable.SimpleStartable<FixAlertsPublisher> implements FixAlertsPublisher {

    private final AlertmanagerAlertsPublisherSettings settings;
    private final Clock clock;
    private final BlockingQueue<AlertEvent> events = new LinkedBlockingQueue<>();
    private final AlertmanagerAlerts alerts;
    private AlertmanagerClient client;
    private volatile Thread sender;

    protected AlertmanagerAlertsPublisher(AlertmanagerAlertsPublisherSettings settings) {
        this(settings, Clock.systemUTC());
    }

    AlertmanagerAlertsPublisher(AlertmanagerAlertsPublisherSettings settings, Clock clock) {
        if (settings.getAlertmanagerUrls().isEmpty()) {
            throw new IllegalArgumentException("Alertmanager alerts publisher " + settings.getInstanceId() + " has no Alertmanager URL");
        }
        if (settings.getResendInterval().isNegative() || settings.getResendInterval().isZero()) {
            throw new IllegalArgumentException("Alertmanager alerts publisher " + settings.getInstanceId() + " resend interval must be positive");
        }
        this.settings = settings;
        this.clock = clock;
        this.alerts = new AlertmanagerAlerts(settings.getStaticLabels(), settings.getResendInterval());
    }

    @Override
    public String getInstanceId() {
        return settings.getInstanceId();
    }

    @Override
    public void publish(AlertEvent event) {
        events.add(event);
    }

    @Override
    protected void startMe() {
        client = new AlertmanagerClient(settings.getAlertmanagerUrls(), settings.getHttpSenderSettings(), settings.getHttpSenderFactory());
        Thread thread = new Thread(this::sendUntilInterrupted, "Staffix-alertmanager-" + settings.getInstanceId());
        thread.setDaemon(true);
        sender = thread;
        thread.start();
    }

    @Override
    protected void stopMe(Deadline stopDeadline) {
        Thread thread = sender;
        sender = null;
        thread.interrupt();
        try {
            thread.join(Math.max(1L, stopDeadline.getRemainingTime().toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            log.warn("Alertmanager alerts publisher {} still posting at the stop deadline", settings.getInstanceId());
        }
        client.close();
    }

    private void sendUntilInterrupted() {
        long resendMillis = settings.getResendInterval().toMillis();
        long nextResend = clock.millis() + resendMillis;
        List<AlertEvent> batch = new ArrayList<>();
        Thread currentThread = Thread.currentThread();
        try {
            while (!currentThread.isInterrupted()) {
                AlertEvent first = events.poll(Math.max(0L, nextResend - clock.millis()), TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    events.drainTo(batch);
                    postSafely(() -> alerts.apply(batch, clock.instant()));
                    batch.clear();
                }
                if (clock.millis() >= nextResend) {
                    postSafely(() -> alerts.all(clock.instant()));
                    nextResend = clock.millis() + resendMillis;
                }
            }
        } catch (InterruptedException e) {
            log.debug("Alertmanager alerts publisher {} stopped", settings.getInstanceId());
        }
    }

    private void postSafely(Supplier<List<PostableAlert>> toPost) throws InterruptedException {
        try {
            client.post(toPost.get());
        } catch (RuntimeException e) {
            log.error("Alertmanager alerts publisher {} failed to post", settings.getInstanceId(), e);
        }
    }

    public static class AlertmanagerAlertsPublisherFactoryImpl implements FixAlertsPublisherSettings.FixAlertsPublisherFactory<AlertmanagerAlertsPublisherSettings> {

        @Override
        public FixAlertsPublisher newInstance(AlertmanagerAlertsPublisherSettings settings) {
            return new AlertmanagerAlertsPublisher(settings);
        }

        @Override
        public Class<AlertmanagerAlertsPublisherSettings> getSettingsClass() {
            return AlertmanagerAlertsPublisherSettings.class;
        }
    }
}
