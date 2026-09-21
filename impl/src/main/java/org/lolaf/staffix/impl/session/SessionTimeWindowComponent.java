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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.impl.threading.SchedulerThread;

import java.io.EOFException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The session's trading window: when it opens, when it is about to close, and the scheduled sequence reset that
 * comes with the roll.
 *
 * <p>{@link FixSessionScheduleManager} works out where the clock is against the configured schedule; this turns that
 * into the session's own doing, logging on when the window opens and out when it closes.
 */
@Slf4j
class SessionTimeWindowComponent implements FixSessionLayerComponent {

    private final FixSessionImpl fixSession;
    private final FixApplication fixApplication;
    private final FixSessionStateComponent fixSessionStateComponent;
    private final FixSessionScheduleManager fixSessionScheduleManager;
    private final FixSessionSettings fixSessionSettings;
    private LogonLogoutComponent logonLogout;
    private ScheduledFuture<?> sessionTimeCheckTask;
    private boolean insideSessionTime;
    private boolean preOutsideSessionTimeTriggered;

    SessionTimeWindowComponent(FixSessionImpl fixSession, FixApplication fixApplication, FixSessionStateComponent fixSessionStateComponent,
                               FixSessionScheduleManager fixSessionScheduleManager, FixSessionSettings fixSessionSettings,
                               ScheduledExecutorService scheduler) {
        this.fixSession = fixSession;
        this.fixApplication = fixApplication;
        this.fixSessionStateComponent = fixSessionStateComponent;
        this.fixSessionScheduleManager = fixSessionScheduleManager;
        this.fixSessionSettings = fixSessionSettings;
        this.insideSessionTime = fixSessionScheduleManager.isWithinSessionTime();
        Duration checkInterval = fixSessionSettings.getSessionScheduleSettings().getWithinSessionTimeCheckInterval();
        this.sessionTimeCheckTask = fixSessionScheduleManager.isEnabled()
                ? scheduler.scheduleAtFixedRate(this::checkSessionTime, calculateInitialSessionTimeCheckDelay(checkInterval),
                checkInterval.toMillis(), TimeUnit.MILLISECONDS) : null;
    }

    private static long calculateInitialSessionTimeCheckDelay(Duration withinSessionCheckInterval) {
        if (withinSessionCheckInterval.toMillis() < 50) {
            throw new IllegalStateException("WithinSessionCheckInterval cannot be smaller than 50 milliseconds");
        }
        return withinSessionCheckInterval.toMillis() - (System.currentTimeMillis() % withinSessionCheckInterval.toMillis());
    }

    boolean isInsideSessionTime() {
        return insideSessionTime;
    }

    /**
     * Whether the clock is in the window right now, which is not the same question as {@link #isInsideSessionTime()}:
     * that one answers what the session has acted on, and it only moves on a transition, which needs a connection.
     * A session deciding whether to dial has none yet, so it asks the clock.
     */
    boolean isWithinSessionTimeNow() {
        return fixSessionScheduleManager.isWithinSessionTime();
    }

    /**
     * A window that opened or closed while the session was down is acted on as soon as it is back up, the checks
     * below having had no connection to run their transitions on.
     */
    void catchUpOnSessionTimeCrossedWhileDisconnected() {
        boolean withinSessionTime = fixSessionScheduleManager.isWithinSessionTime();
        if (withinSessionTime && !insideSessionTime) {
            // fires the application callback and, on an initiator wanting to be logged in, the logon the reopened
            // window calls for - the sendLogonRequestIfNeeded() that follows then finds the request already sent
            onInsideSessionTime();
        } else if (!withinSessionTime && insideSessionTime) {
            onOutsideSessionTime();
        }
    }

    /**
     * The warning that the window is about to close is given once per window, and a session that logged out in the
     * meantime gets it again when it comes back.
     */
    @Override
    public void onSessionStarted(FixSessionLayerComponents components) {
        logonLogout = components.get(LogonLogoutComponent.class);
    }

    @Override
    public void onLogoutProcessed(boolean cleanLogout) {
        preOutsideSessionTimeTriggered = false;
    }

    @Override
    public void onSessionStopping(Deadline deadline) {
        if (sessionTimeCheckTask != null) {
            sessionTimeCheckTask.cancel(false);
            sessionTimeCheckTask = null;
        }
    }

    @SchedulerThread
    private void checkSessionTime() {
        // some minor calculations and state change processed in scheduler thread for now
        if (fixSessionStateComponent.isDisconnected()) {
            return;
        }
        try {
            Duration outsideSessionTimePreTriggerDelay = fixSessionSettings.getSessionScheduleSettings().getOutsideSessionTimePreTriggerDelay();
            long remainingMillisUntilEndOfSessionTimeframe = fixSessionScheduleManager.getSessionTimeLeft();
            if (remainingMillisUntilEndOfSessionTimeframe <= outsideSessionTimePreTriggerDelay.toMillis()) {
                fixSession.processTask(this::onPreTriggerOutsideSessionTime, this::onFailedCheckSessionTimeTask);
            }
            if (remainingMillisUntilEndOfSessionTimeframe == 0 && insideSessionTime) {
                fixSession.processTask(this::onOutsideSessionTime, this::onFailedCheckSessionTimeTask);
            } else if (remainingMillisUntilEndOfSessionTimeframe > 0 && !insideSessionTime) {
                fixSession.processTask(this::onInsideSessionTime, this::onFailedCheckSessionTimeTask);
            }
            checkSequenceResetDue();
        } catch (Exception ex) {
            log.error("Failed to process checkSessionTime task", ex);
        }
    }

    private void checkSequenceResetDue() {
        FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry dueReset = fixSessionScheduleManager.dueSequenceReset();
        if (dueReset == null || !dueReset.getInitiatesReset()) {
            return;
        }
        if (!fixSessionStateComponent.isLoggedIn()) {
            // the crossing has been consumed above rather than held: a session that was down at the agreed time and
            // comes back an hour later would otherwise reset an hour late, which is a reset the counterparties never
            // agreed to. Losing the day's roll leaves the numbering running until tomorrow, which is the safer of the
            // two - the end that awaits the reset acts on the Logon it receives, never on the clock
            fixSession.logEvent("Scheduled sequence reset time reached while logged out, skipping this day's reset");
            return;
        }
        fixSession.logEvent("Scheduled sequence reset time reached, resetting over the live session");
        fixSession.processTask(fixSession::sendInSessionSequenceReset, this::onFailedCheckSessionTimeTask);
    }

    private void onFailedCheckSessionTimeTask(Runnable task, Exception error) {
        // the connection going between the check and the handover is the expected end of a session time check,
        // not a fault: what it was about to do goes with the connection
        if (error != null && error != FixSessionImpl.NO_CONNECTED_SESSION && !(error instanceof EOFException)) {
            log.error("Failed to process Check session time task {}", task.getClass().getSimpleName(), error);
        }
    }

    private void onOutsideSessionTime() {
        fixSession.logEvent("FIX session outside of timeframe");
        fixApplication.onOutsideSessionTime(fixSession);
        insideSessionTime = false;
        if (fixSessionStateComponent.canSendLogoutRequest()) {
            logonLogout.sendLogoutRequest("Outside of session timeframe", false);
        }
    }

    private void onInsideSessionTime() {
        fixSession.logEvent("FIX session inside of timeframe");
        fixApplication.onInsideSessionTime(fixSession);
        insideSessionTime = true;
        preOutsideSessionTimeTriggered = false;
        if (fixSessionStateComponent.canLogonForReopenedWindow()) {
            logonLogout.sendLogonRequestIfNeeded();
        }
    }

    private void onPreTriggerOutsideSessionTime() {
        if (!preOutsideSessionTimeTriggered) {
            preOutsideSessionTimeTriggered = true;
            fixApplication.onPreOutsideSessionTime(fixSession, Duration.ofMillis(fixSessionScheduleManager.getSessionTimeLeft()));
        }
    }
}
