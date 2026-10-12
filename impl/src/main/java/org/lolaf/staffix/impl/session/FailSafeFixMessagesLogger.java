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
package org.lolaf.staffix.impl.session;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.RaisedAlertEvaluator;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;

import java.nio.ByteBuffer;

/**
 * Wraps a session's messages logger so a logging failure never reaches the session, and raises the session's
 * {@code messages-logger} alert, so every logger has one without implementing it. Its calls are skipped while its
 * resource is down, so the session is not blocked on each one: they are not logged either way.
 */
@Slf4j
public class FailSafeFixMessagesLogger implements FixMessagesLogger.Logger {

    static final String PROBE_NAME = "messages-logger";

    private final FixMessagesLogger.Logger logger;
    private final FixSessionId fixSessionId;
    private final RaisedAlertEvaluator alertEvaluator;
    private final UnderlyingResourceBreaker breaker;
    @Getter
    private final Probe probe;
    private volatile boolean failing;
    private volatile String lastFailure;

    public FailSafeFixMessagesLogger(FixMessagesLogger.Logger logger, FixSessionId fixSessionId,
                                     FixMessagesLoggerSettings settings, UnderlyingResourceChecker checker) {
        this.logger = logger;
        this.fixSessionId = fixSessionId;
        this.alertEvaluator = new RaisedAlertEvaluator();
        this.breaker = UnderlyingResourceBreaker.of("Messages logger of FIX session " + fixSessionId,
                logger::isUnderlyingStorageResourceAvailable, logger::getUnderlyingStorageResourceDescription,
                settings.getResourceCheckInterval(), checker, this::raiseResourceDown);
        this.probe = Probe.builder().name(PROBE_NAME).description("Messages logger")
                .evaluator(alertEvaluator.withClearDelay(settings.getAlertClearDelay())).build();
    }

    @Override
    public boolean isUnderlyingStorageResourceAvailable() {
        return logger.isUnderlyingStorageResourceAvailable();
    }

    @Override
    public String getUnderlyingStorageResourceDescription() {
        return logger.getUnderlyingStorageResourceDescription();
    }

    @Override
    public void logIncoming(UTCTime logTime, MessageType messageType, ByteBuffer message) {
        if (skipped()) {
            return;
        }
        try {
            logger.logIncoming(logTime, messageType, message);
            succeeded();
        } catch (Exception ex) {
            failed("logIncoming()", ex);
        }
    }

    @Override
    public void logOutgoing(UTCTime logTime, MessageType messageType, ByteBuffer message) {
        if (skipped()) {
            return;
        }
        try {
            logger.logOutgoing(logTime, messageType, message);
            succeeded();
        } catch (Exception ex) {
            failed("logOutgoing()", ex);
        }
    }

    @Override
    public void logEvent(UTCTime eventTime, String event) {
        if (skipped()) {
            return;
        }
        try {
            logger.logEvent(eventTime, event);
            succeeded();
        } catch (Exception ex) {
            failed("logEvent()", ex);
        }
    }

    @Override
    public void logEvent(UTCTime eventTime, String event, Object... params) {
        if (skipped()) {
            return;
        }
        try {
            logger.logEvent(eventTime, event, params);
            succeeded();
        } catch (Exception ex) {
            failed("logEvent()", ex);
        }
    }

    @Override
    public boolean isLoggingOutgoing() {
        return logger.isLoggingOutgoing();
    }

    @Override
    public boolean isLoggingIncoming() {
        return logger.isLoggingIncoming();
    }

    @Override
    public boolean isLoggingEvents() {
        return logger.isLoggingEvents();
    }

    @Override
    public FixMessagesLogger.Logger start() throws StartStopException {
        return logger.start();
    }

    @Override
    public FixMessagesLogger.Logger stop(Deadline stopDeadline) throws StartStopException {
        breaker.stop();
        return logger.stop(stopDeadline);
    }

    @Override
    public boolean isStarted() {
        return logger.isStarted();
    }

    private boolean skipped() {
        if (breaker.isOpen()) {
            breaker.callSkipped();
            return true;
        }
        return false;
    }

    private void succeeded() {
        if (failing) {
            failing = false;
            alertEvaluator.clear();
        }
    }

    private void failed(String call, Exception ex) {
        log.warn("Failed to call {} on Logger {} for FIX session {}", call, logger.getClass().getSimpleName(), fixSessionId, ex);
        failing = true;
        lastFailure = call + " failed: " + ex;
        alertEvaluator.raise(Alert.warning(lastFailure));
        breaker.callFailed();
    }

    private void raiseResourceDown() {
        alertEvaluator.raise(Alert.major("'" + getUnderlyingStorageResourceDescription() + "' is down, messages and events are not logged; "
                + lastFailure));
    }
}
