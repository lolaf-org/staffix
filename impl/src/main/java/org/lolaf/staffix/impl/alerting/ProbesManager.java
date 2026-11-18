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
import org.lolaf.staffix.api.alerting.AlertsPublisher;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeId;
import org.lolaf.staffix.api.alerting.ProbeScope;
import org.lolaf.staffix.api.alerting.ProbeState;
import org.lolaf.staffix.api.alerting.ProbesListener;
import org.lolaf.staffix.api.alerting.ScopedProbeState;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Evaluates the probes of every registered scope on one thread, ticking every second, and publishes the changes of
 * alert state to each scope's publisher.
 *
 * <p>All alert state is confined to that thread: a scope unregistered from elsewhere has its ongoing alerts cleared
 * by a task queued to it. Once stopped, unregistered scopes are dropped silently, so a source shutting down does not
 * report its alerts as resolved.
 */
@Slf4j
public final class ProbesManager extends Startable.SimpleStartable<ProbesManager> {

    static final Duration TICK = Probe.MIN_INTERVAL;

    private final String source;
    private final Clock clock;
    private final ProbesListener listener;
    private volatile ScopeProbesEvaluation[] scopes = new ScopeProbesEvaluation[0];
    private volatile ScheduledExecutorService scheduler;
    private long tick;

    /**
     * @param source names this manager on every {@link org.lolaf.staffix.api.alerting.AlertEvent}
     */
    public ProbesManager(String source, Clock clock, ProbesListener listener) {
        this.source = source;
        this.clock = clock;
        this.listener = listener;
    }

    private static int indexOf(ScopeProbesEvaluation[] evaluations, ProbesImpl probes) {
        for (int i = 0; i < evaluations.length; i++) {
            if (evaluations[i].getProbes() == probes) {
                return i;
            }
        }
        return -1;
    }

    private static ScopeProbesEvaluation find(ScopeProbesEvaluation[] evaluations, ProbeScope scope) {
        for (ScopeProbesEvaluation evaluation : evaluations) {
            if (evaluation.getProbes().getScope().equals(scope)) {
                return evaluation;
            }
        }
        return null;
    }

    /**
     * @param active whether the scope is active, read on the probes thread, and only while it has a probe
     *               {@link org.lolaf.staffix.api.alerting.Probe#isOnlyWhileActive() run only while active}
     */
    public synchronized void register(ProbesImpl probes, AlertsPublisher publisher, BooleanSupplier active) {
        ScopeProbesEvaluation[] updated = Arrays.copyOf(scopes, scopes.length + 1);
        updated[scopes.length] = new ScopeProbesEvaluation(source, probes, publisher, active, listener);
        scopes = updated;
        probes.attach(listener);
    }

    public void unregister(ProbesImpl probes) {
        probes.detach();
        ScopeProbesEvaluation evaluation = remove(probes);
        ScheduledExecutorService current = scheduler;
        if (evaluation == null || current == null) {
            return;
        }
        try {
            current.execute(() -> evaluation.clearAll(clock.millis()));
        } catch (RejectedExecutionException stopping) {
            log.debug("Probes of {} not cleared, the probes manager of {} is stopping", probes.getScope(), source);
        }
    }

    /**
     * Empty for a scope that is not registered.
     */
    public List<ProbeState> getProbeStates(ProbeScope scope) {
        ScopeProbesEvaluation evaluation = find(scopes, scope);
        return evaluation == null ? List.of() : evaluation.getStates();
    }

    /**
     * False for a scope that is not registered, since none of its probes can be in alert, and for a probe it does not
     * have.
     */
    public boolean clearAlert(ProbeId probeId) {
        ScopeProbesEvaluation evaluation = find(scopes, probeId.getScope());
        return evaluation != null && evaluation.clearAlert(probeId.getName());
    }

    public List<ScopedProbeState> getActiveAlerts() {
        List<ScopedProbeState> active = null;
        for (ScopeProbesEvaluation scope : scopes) {
            active = scope.addActiveAlerts(active);
        }
        return active == null ? List.of() : Collections.unmodifiableList(active);
    }

    @Override
    protected void startMe() {
        ScheduledExecutorService newScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Staffix-probes-" + source);
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
                log.warn("Probes manager of {} still evaluating at the stop deadline", source);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void tick() {
        long currentTick = tick++;
        long nowMillis = clock.millis();
        ScopeProbesEvaluation[] local = scopes;
        for (ScopeProbesEvaluation scopeProbesEvaluation : local) {
            scopeProbesEvaluation.tick(currentTick, nowMillis);
        }
    }

    private synchronized ScopeProbesEvaluation remove(ProbesImpl probes) {
        int index = indexOf(scopes, probes);
        if (index < 0) {
            return null;
        }
        ScopeProbesEvaluation removed = scopes[index];
        ScopeProbesEvaluation[] updated = new ScopeProbesEvaluation[scopes.length - 1];
        System.arraycopy(scopes, 0, updated, 0, index);
        System.arraycopy(scopes, index + 1, updated, index, updated.length - index);
        scopes = updated;
        return removed;
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.error("Probes evaluation of {} failed", source, e);
        }
    }
}
