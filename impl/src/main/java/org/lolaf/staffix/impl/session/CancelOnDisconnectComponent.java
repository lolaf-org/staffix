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

import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.session.CancelOnDisconnectType;
import org.lolaf.staffix.impl.threading.SchedulerThread;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cancel on disconnect: the timer a session ending starts, and which logging back on within its window stops.
 *
 * <p>What it does when a session ends is what the peer asked for in its Logon(35=A), which is why the type and the
 * window are kept from one to the other.
 */
@RequiredArgsConstructor
class CancelOnDisconnectComponent implements FixSessionLayerComponent {

    private final FixSessionImpl fixSession;
    private final FixApplication fixApplication;
    private final FixSessionStateComponent fixSessionStateComponent;
    private final ScheduledExecutorService scheduler;
    private CancelOnDisconnectType cancelOnDisconnectType;
    private int codTimeoutWindowInMillis;
    private ScheduledFuture<?> cancelOnDisconnectTask;

    @Override
    public void onLogonCompleted(LogonCompleted logon) {
        if (cancelOnDisconnectTask != null) {
            fixSession.logEvent("Cancelling cancel on disconnect task");
            cancelOnDisconnectTask.cancel(false);
            cancelOnDisconnectTask = null;
        }
        cancelOnDisconnectType = logon.getCancelOnDisconnectType();
        codTimeoutWindowInMillis = logon.getCodTimeoutWindowInMillis();
    }

    @Override
    public void onLogoutProcessed(boolean cleanLogout) {
        if (cancelOnDisconnectType != null) {
            switch (cancelOnDisconnectType) {
                case DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT:
                    break;
                case CANCEL_ON_DISCONNECT_OR_LOGOUT:
                    scheduleCodTask();
                    break;
                case CANCEL_ON_DISCONNECT_ONLY:
                    if (!cleanLogout) {
                        scheduleCodTask();
                    }
                    break;
                case CANCEL_ON_LOGOUT_ONLY:
                    if (cleanLogout) {
                        scheduleCodTask();
                    }
                    break;
            }
        }
        cancelOnDisconnectType = null;
        codTimeoutWindowInMillis = 0;
    }

    @SchedulerThread
    private void triggerCancelOnDisconnect(CancelOnDisconnectType triggeredType) {
        fixSession.logEvent("Triggered cancel on disconnect task");
        fixApplication.onCancelOnDisconnectTriggered(fixSession, triggeredType);
        cancelOnDisconnectTask = null;
    }

    private void scheduleCodTask() {
        if (fixSessionStateComponent.isStarted()) {
            CancelOnDisconnectType triggeredType = cancelOnDisconnectType;
            fixSession.logEvent("Schedule cancel on disconnect task for type %s with timeout %s", triggeredType, codTimeoutWindowInMillis);
            cancelOnDisconnectTask = scheduler.schedule(() -> triggerCancelOnDisconnect(triggeredType),
                    codTimeoutWindowInMillis, TimeUnit.MILLISECONDS);
        }
    }
}
