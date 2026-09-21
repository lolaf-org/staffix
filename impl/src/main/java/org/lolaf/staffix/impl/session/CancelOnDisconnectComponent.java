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
import org.lolaf.staffix.api.executor.MessageExecutor;
import org.lolaf.staffix.api.session.CancelOnDisconnectType;
import org.lolaf.staffix.impl.executor.SessionMessageExecutors;
import org.lolaf.staffix.impl.threading.SchedulerThread;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cancel on disconnect: the timer a session ending starts, and which logging back on within its window stops.
 *
 * <p>What it does when a session ends is what the peer asked for in its Logon(35=A), which is why the type and the
 * window are kept from one to the other.
 *
 * <p>This is the one timer that fires while the session is down, so its two ends run on different threads: the
 * scheduler times the window out while the IO thread may be logging the session back on, and whichever takes the
 * timer in force is the one that acts on it. The application hears about it on a message executor, cancelling a
 * client's orders being the kind of work that blocks.
 */
@RequiredArgsConstructor
class CancelOnDisconnectComponent implements FixSessionLayerComponent {

    private static final int NOTIFICATION_ROUTING_KEY = 0;

    private final FixSessionImpl fixSession;
    private final FixApplication fixApplication;
    private final FixSessionStateComponent fixSessionStateComponent;
    private final ScheduledExecutorService scheduler;
    private final SessionMessageExecutors messageExecutors;
    private final AtomicReference<CancelOnDisconnectTimer> currentTimer = new AtomicReference<>();
    private CancelOnDisconnectType cancelOnDisconnectType;
    private int codTimeoutWindowInMillis;

    @Override
    public void onLogonCompleted(LogonCompleted logon) {
        cancelCurrentTimer();
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

    private void cancelCurrentTimer() {
        CancelOnDisconnectTimer timer = currentTimer.getAndSet(null);
        if (timer != null) {
            fixSession.logEvent("Cancelling cancel on disconnect task");
            timer.cancel();
        }
    }

    private void scheduleCodTask() {
        if (fixSessionStateComponent.isStarted()) {
            fixSession.logEvent("Schedule cancel on disconnect task for type %s with timeout %s",
                    cancelOnDisconnectType, codTimeoutWindowInMillis);
            CancelOnDisconnectTimer timer = new CancelOnDisconnectTimer(cancelOnDisconnectType);
            currentTimer.set(timer);
            timer.scheduleIn(codTimeoutWindowInMillis);
        }
    }

    /**
     * One scheduled cancel on disconnect. Cancelling it is best effort, a timer that has already left the
     * scheduler being beyond recall; what decides is which of the two threads takes it.
     */
    private final class CancelOnDisconnectTimer {

        private final CancelOnDisconnectType triggeredType;
        private ScheduledFuture<?> scheduled;

        private CancelOnDisconnectTimer(CancelOnDisconnectType triggeredType) {
            this.triggeredType = triggeredType;
        }

        private void scheduleIn(int delayInMillis) {
            scheduled = scheduler.schedule(this::onTimerDue, delayInMillis, TimeUnit.MILLISECONDS);
        }

        @SchedulerThread
        private void onTimerDue() {
            if (!currentTimer.compareAndSet(this, null)) {
                return;
            }
            fixSession.logEvent("Triggered cancel on disconnect task");
            notifyApplication(triggeredType);
        }

        private void notifyApplication(CancelOnDisconnectType triggeredType) {
            MessageExecutor<Object, Object, Object, Object> executor =
                    messageExecutors.getMessageExecutor(CancelOnDisconnectComponent.class, NOTIFICATION_ROUTING_KEY);
            executor.execute((message, param1, param2, param3) -> {
                try {
                    fixApplication.onCancelOnDisconnectTriggered(fixSession, triggeredType);
                } finally {
                    // not from the executor thread: releasing its last executor interrupts it and offers to the queue
                    // it is itself consuming
                    fixSession.runOnSessionOwnerThread(executor::release);
                }
            }, null, null, null, null);
        }

        private void cancel() {
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }
}
