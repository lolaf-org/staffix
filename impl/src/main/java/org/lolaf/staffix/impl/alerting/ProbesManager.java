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
package org.lolaf.staffix.impl.alerting;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.alerting.FixAlertsPublisher;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeState;
import org.lolaf.staffix.api.alerting.ProbesListener;
import org.lolaf.staffix.api.alerting.SessionProbeState;
import org.lolaf.staffix.api.session.FixSession;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Evaluates the probes of every session of an engine on one thread, ticking every second, and publishes the changes
 * of alert state to each session's publisher.
 *
 * <p>All alert state is confined to that thread: a session unregistered from elsewhere has its ongoing alerts cleared
 * by a task queued to it. Once stopped, unregistered sessions are dropped silently, so an engine shutting down does
 * not report its alerts as resolved.
 */
@Slf4j
public final class ProbesManager extends Startable.SimpleStartable<ProbesManager> {

    static final Duration TICK = Probe.MIN_INTERVAL;

    private final String engineId;
    private final Clock clock;
    private final ProbesListener listener;
    private volatile SessionProbesEvaluation[] sessions = new SessionProbesEvaluation[0];
    private volatile ScheduledExecutorService scheduler;
    private long tick;

    public ProbesManager(String engineId, Clock clock, ProbesListener listener) {
        this.engineId = engineId;
        this.clock = clock;
        this.listener = listener;
    }

    private static int indexOf(SessionProbesEvaluation[] evaluations, FixSession fixSession) {
        for (int i = 0; i < evaluations.length; i++) {
            if (evaluations[i].getFixSession() == fixSession) {
                return i;
            }
        }
        return -1;
    }

    public synchronized void register(FixSession fixSession, FixSessionProbesImpl probes, FixAlertsPublisher publisher) {
        SessionProbesEvaluation[] updated = Arrays.copyOf(sessions, sessions.length + 1);
        updated[sessions.length] = new SessionProbesEvaluation(fixSession, probes, publisher, listener);
        sessions = updated;
        probes.attach(fixSession.getFixSessionId(), listener);
    }

    public void unregister(FixSession fixSession, FixSessionProbesImpl probes) {
        probes.detach();
        SessionProbesEvaluation evaluation = remove(fixSession);
        ScheduledExecutorService current = scheduler;
        if (evaluation == null || current == null) {
            return;
        }
        try {
            current.execute(() -> evaluation.clearAll(clock.millis()));
        } catch (RejectedExecutionException stopping) {
            log.debug("Probes of {} not cleared, the probes manager of engine {} is stopping", fixSession.getFixSessionId(), engineId);
        }
    }

    /**
     * Empty for a session that is not registered.
     */
    public List<ProbeState> getProbeStates(FixSession fixSession) {
        SessionProbesEvaluation[] local = sessions;
        int index = indexOf(local, fixSession);
        return index < 0 ? List.of() : local[index].getStates();
    }

    public List<SessionProbeState> getActiveAlerts() {
        List<SessionProbeState> active = null;
        for (SessionProbesEvaluation session : sessions) {
            active = session.addActiveAlerts(active);
        }
        return active == null ? List.of() : Collections.unmodifiableList(active);
    }

    @Override
    protected void startMe() {
        ScheduledExecutorService newScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Staffix-probes-" + engineId);
            thread.setDaemon(true);
            return thread;
        });
        newScheduler.scheduleAtFixedRate(this::tickSafely, TICK.toMillis(), TICK.toMillis(), TimeUnit.MILLISECONDS);
        scheduler = newScheduler;
    }

    @Override
    protected void stopMe(Deadline stopDeadline) {
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        current.shutdown();
        try {
            if (!current.awaitTermination(Math.max(1L, stopDeadline.getRemainingTime().toMillis()), TimeUnit.MILLISECONDS)) {
                log.warn("Probes manager of engine {} still evaluating at the stop deadline", engineId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void tick() {
        long currentTick = tick++;
        long nowMillis = clock.millis();
        SessionProbesEvaluation[] local = sessions;
        for (SessionProbesEvaluation sessionProbesEvaluation : local) {
            sessionProbesEvaluation.tick(currentTick, nowMillis);
        }
    }

    private synchronized SessionProbesEvaluation remove(FixSession fixSession) {
        int index = indexOf(sessions, fixSession);
        if (index < 0) {
            return null;
        }
        SessionProbesEvaluation removed = sessions[index];
        SessionProbesEvaluation[] updated = new SessionProbesEvaluation[sessions.length - 1];
        System.arraycopy(sessions, 0, updated, 0, index);
        System.arraycopy(sessions, index + 1, updated, index, updated.length - index);
        sessions = updated;
        return removed;
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.error("Probes evaluation of engine {} failed", engineId, e);
        }
    }
}
