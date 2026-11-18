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

import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.AlertEvent;
import org.lolaf.staffix.api.alerting.AlertLevel;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeEvaluator;
import org.lolaf.staffix.api.alerting.ProbeDefinition;
import org.lolaf.staffix.api.alerting.ProbeId;
import org.lolaf.staffix.api.alerting.ProbeScope;
import org.lolaf.staffix.api.alerting.ProbeState;
import org.lolaf.staffix.api.alerting.ProbesListener;
import org.lolaf.staffix.api.alerting.RaisedAlertEvaluator;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionProbeScope;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.tests.TestingFixAlertsPublisher;
import org.lolaf.staffix.tests.TestingFixAlertsPublisherSettings;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.api.alerting.AlertEvent.Type.CHANGED;
import static org.lolaf.staffix.api.alerting.AlertEvent.Type.CLEARED;
import static org.lolaf.staffix.api.alerting.AlertEvent.Type.RAISED;
import static org.lolaf.staffix.api.alerting.AlertEvent.Type.REMINDED;

class TestProbesManager {

    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    private static final FixSessionId SESSION_ID = FixSessionId.of("orders", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
    private static final FixSessionId OTHER_SESSION_ID = FixSessionId.of("other", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
    private static final ProbeScope SCOPE = FixSessionProbeScope.of(SESSION_ID);
    private static final ProbeScope OTHER_SCOPE = FixSessionProbeScope.of(OTHER_SESSION_ID);

    private final TestingFixAlertsPublisherSettings publisherSettings = TestingFixAlertsPublisherSettings.builder().build();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicInteger activityReads = new AtomicInteger();
    private final ProbesImpl probes = new ProbesImpl(SCOPE);
    private final AtomicReference<Optional<Alert>> result = new AtomicReference<>(Optional.empty());
    private final List<String> listened = new CopyOnWriteArrayList<>();
    private final ProbesListeners listeners = new ProbesListeners();
    private final ProbesManager manager = new ProbesManager("engine", Clock.fixed(NOW, ZoneOffset.UTC), listeners);

    TestProbesManager() {
        listeners.add(new RecordingListener(listened));
        manager.register(probes, new TestingFixAlertsPublisher(publisherSettings), () -> {
            activityReads.incrementAndGet();
            return active.get();
        });
    }

    @Test
    void theListenerIsToldOfTheProbesAScopeHadWhenRegisteredAndOfEachChange() {
        ProbesImpl otherProbes = new ProbesImpl(OTHER_SCOPE);
        otherProbes.add(probe("before", result::get));

        manager.register(otherProbes, new TestingFixAlertsPublisher(publisherSettings), () -> true);
        otherProbes.add(probe("after", result::get));
        otherProbes.remove("before");
        manager.unregister(otherProbes);
        otherProbes.add(probe("unregistered", result::get));

        assertThat(listened).containsExactly("added other before", "added other after", "removed other before",
                "removed other after");
    }

    @Test
    void theListenerIsToldOfTransitionsButNotOfRemindersOrMessageChanges() {
        probes.add(Probe.builder().name("queue").description("d").evaluator(result::get)
                .reminderInterval(Duration.ofSeconds(1)).build());
        listened.clear();

        result.set(Optional.of(Alert.warning("100 queued")));
        tick(2);
        result.set(Optional.of(Alert.warning("150 queued")));
        manager.tick();
        result.set(Optional.of(Alert.critical("1000 queued")));
        manager.tick();
        result.set(Optional.empty());
        manager.tick();

        assertThat(listened).containsExactly("transition orders queue WARNING", "transition orders queue CRITICAL",
                "transition orders queue normal");
    }

    @Test
    void aProbeRemovedInAlertIsClearedForTheListenerAtTheNextTick() {
        probes.add(probe("queue", () -> Optional.of(Alert.warning("100 queued"))));
        manager.tick();
        listened.clear();

        probes.remove("queue");
        assertThat(manager.getActiveAlerts()).hasSize(1);
        manager.tick();

        assertThat(listened).containsExactly("removed orders queue", "transition orders queue normal");
        assertThat(manager.getActiveAlerts()).isEmpty();
    }

    @Test
    void activeAlertsListOnlyTheProbesInAlert() {
        probes.add(probe("normal", () -> Optional.empty()));
        probes.add(probe("alerting", () -> Optional.of(Alert.major("down"))));

        manager.tick();

        assertThat(manager.getActiveAlerts()).singleElement().satisfies(alert -> {
            assertThat(alert.getScope()).isEqualTo(SCOPE);
            assertThat(alert.getProbeState().getProbeName()).isEqualTo("alerting");
        });
    }

    @Test
    void eventsAndActiveAlertsCarryTheProbesLabelsAndRunbook() {
        URI runbook = URI.create("https://wiki.example.com/runbooks/gateway");
        probes.add(Probe.builder().name("gateway").description("d").evaluator(() -> Optional.of(Alert.major("refused")))
                .label("team", "fix_ops").runbookUrl(runbook).build());

        manager.tick();

        assertThat(publisherSettings.getEvents()).singleElement().satisfies(event -> {
            assertThat(event.getProbeLabels()).containsExactly(Map.entry("team", "fix_ops"));
            assertThat(event.getProbeRunbookUrl()).contains(runbook);
        });
        assertThat(manager.getActiveAlerts()).singleElement()
                .satisfies(alert -> assertThat(alert.getRunbookUrl()).contains(runbook));
    }

    @Test
    void aManualClearIsPublishedAtTheNextTickWhateverTheIntervalAndMarkedManual() {
        RaisedAlertEvaluator raised = new RaisedAlertEvaluator();
        probes.add(Probe.builder().name("rejects").description("d").evaluator(raised)
                .evaluationInterval(Duration.ofSeconds(10)).build());
        raised.raise(Alert.major("rejected"));
        manager.tick();
        listened.clear();

        assertThat(manager.clearAlert(ProbeId.of(SCOPE, "rejects"))).isTrue();
        manager.tick();

        assertThat(publisherSettings.getEvents()).extracting(AlertEvent::getType, AlertEvent::isClearedManually)
                .containsExactly(tuple(RAISED, false), tuple(CLEARED, true));
        assertThat(listened).containsExactly("transition orders rejects normal manually");
        assertThat(manager.getProbeStates(SCOPE)).singleElement()
                .satisfies(state -> assertThat(state.isClearedManually()).isFalse());
    }

    @Test
    void aManualClearIsRefusedOutOfAlertAndByAPolledCondition() {
        probes.add(probe("queue", result::get));
        manager.tick();
        assertThat(manager.clearAlert(ProbeId.of(SCOPE, "queue"))).isFalse();

        result.set(Optional.of(Alert.warning("100 queued")));
        manager.tick();
        assertThat(manager.clearAlert(ProbeId.of(SCOPE, "queue"))).isFalse();
        result.set(Optional.empty());
        manager.tick();

        assertThat(publisherSettings.getEvents()).extracting(AlertEvent::getType, AlertEvent::isClearedManually)
                .containsExactly(tuple(RAISED, false), tuple(CLEARED, false));
    }

    @Test
    void aManualClearOfAProbeNotEvaluatedYetUnknownOrOfAnUnregisteredScopeIsRefused() {
        probes.add(probe("added", result::get));

        assertThat(manager.clearAlert(ProbeId.of(SCOPE, "added"))).isFalse();
        assertThat(manager.clearAlert(ProbeId.of(SCOPE, "unknown"))).isFalse();
        assertThat(manager.clearAlert(ProbeId.of(OTHER_SCOPE, "added"))).isFalse();
        assertThat(manager.getProbeStates(OTHER_SCOPE)).isEmpty();
    }

    @Test
    void aFailingListenerDoesNotKeepTheOthersFromBeingTold() {
        ProbesListeners failingFirst = new ProbesListeners();
        failingFirst.add(new RecordingListener(null));
        failingFirst.add(new RecordingListener(listened));

        failingFirst.onProbeRemoved(SCOPE, "queue");

        assertThat(listened).containsExactly("removed orders queue");
    }

    @Test
    void publishesARaiseALevelChangeAndAClear() {
        probes.add(probe("queue", result::get));

        manager.tick();
        result.set(Optional.of(Alert.warning("100 queued")));
        manager.tick();
        result.set(Optional.of(Alert.critical("1000 queued")));
        manager.tick();
        result.set(Optional.empty());
        manager.tick();

        assertThat(publisherSettings.getEvents()).extracting(AlertEvent::getType).containsExactly(RAISED, CHANGED, CLEARED);
        assertThat(publisherSettings.getEvents()).extracting(event -> event.getAlert().getMessage())
                .containsExactly("100 queued", "1000 queued", "1000 queued");
        assertThat(publisherSettings.getEvents()).allSatisfy(event -> {
            assertThat(event.getSource()).isEqualTo("engine");
            assertThat(event.getProbeId()).isEqualTo(ProbeId.of(SCOPE, "queue"));
            assertThat(event.getProbeDescription()).isEqualTo("queue description");
            assertThat(event.getSince()).isEqualTo(NOW);
        });
    }

    @Test
    void aMessageChangeAloneIsNotPublishedButShowsInTheState() {
        probes.add(probe("queue", result::get));
        result.set(Optional.of(Alert.warning("100 queued")));
        manager.tick();
        result.set(Optional.of(Alert.warning("150 queued")));
        manager.tick();

        assertThat(publisherSettings.getEvents()).extracting(AlertEvent::getType).containsExactly(RAISED);
        assertThat(manager.getProbeStates(SCOPE)).singleElement().satisfies(state -> {
            assertThat(state.getAlert()).contains(Alert.warning("150 queued"));
            assertThat(state.getSince()).contains(NOW);
            assertThat(state.getLastEvaluation()).contains(NOW);
        });
    }

    @Test
    void remindsAnOngoingAlertAtItsReminderInterval() {
        probes.add(Probe.builder().name("queue").description("d").evaluator(result::get)
                .reminderInterval(Duration.ofSeconds(3)).build());
        result.set(Optional.of(Alert.warning("100 queued")));

        tick(7);

        assertThat(publisherSettings.getEvents()).extracting(AlertEvent::getType).containsExactly(RAISED, REMINDED, REMINDED);
    }

    @Test
    void evaluatesAtItsOwnIntervalRoundedUpToWholeTicks() {
        AtomicInteger evaluations = new AtomicInteger();
        probes.add(Probe.builder().name("slow").description("d")
                .evaluator(() -> {
                    evaluations.incrementAndGet();
                    return Optional.empty();
                })
                .evaluationInterval(Duration.ofMillis(2500)).build());

        tick(7);

        assertThat(evaluations).hasValue(3);
    }

    @Test
    void aFailingEvaluatorRaisesAMajorAlert() {
        probes.add(probe("throwing", () -> {
            throw new IllegalStateException("boom");
        }));
        probes.add(probe("null", () -> null));

        manager.tick();

        assertThat(publisherSettings.getEvents()).hasSize(2).allSatisfy(event -> {
            assertThat(event.getType()).isEqualTo(RAISED);
            assertThat(event.getAlert().getLevel()).isEqualTo(AlertLevel.MAJOR);
        });
        assertThat(publisherSettings.getEvents()).first().satisfies(event -> assertThat(event.getAlert().getMessage()).contains("boom"));
    }

    @Test
    void aRemovedProbeIsClearedAndAReplacementOfTheSameNameStartsOver() {
        probes.add(probe("queue", result::get));
        result.set(Optional.of(Alert.warning("100 queued")));
        manager.tick();

        probes.remove("queue");
        probes.add(probe("queue", () -> Optional.of(Alert.critical("replaced"))));
        manager.tick();
        probes.remove("queue");
        manager.tick();

        assertThat(publisherSettings.getEvents()).extracting(AlertEvent::getType, event -> event.getAlert().getMessage())
                .containsExactly(
                        tuple(RAISED, "100 queued"),
                        tuple(CLEARED, "100 queued"),
                        tuple(RAISED, "replaced"),
                        tuple(CLEARED, "replaced"));
        assertThat(manager.getProbeStates(SCOPE)).isEmpty();
    }

    @Test
    void aFailingPublisherDoesNotStopTheEvaluation() {
        ProbesImpl otherProbes = new ProbesImpl(OTHER_SCOPE);
        manager.register(otherProbes, event -> {
            throw new IllegalStateException("publisher down");
        }, () -> true);
        otherProbes.add(probe("failing", () -> Optional.of(Alert.warning("x"))));
        probes.add(probe("queue", () -> Optional.of(Alert.warning("y"))));

        manager.tick();

        assertThat(publisherSettings.getEvents()).extracting(AlertEvent::getType).containsExactly(RAISED);
        assertThat(manager.getProbeStates(OTHER_SCOPE)).hasSize(1);
    }

    @Test
    void aProbeHasNoStateUntilItsFirstEvaluation() {
        probes.add(probe("queue", result::get));
        assertThat(manager.getProbeStates(SCOPE)).isEmpty();

        manager.tick();

        assertThat(manager.getProbeStates(SCOPE)).extracting(ProbeState::getProbeName).containsExactly("queue");
        assertThat(manager.getProbeStates(SCOPE).get(0).getAlert()).isEmpty();
    }

    @Test
    void aStartedManagerEvaluatesEverySecondAndClearsTheAlertsOfAnUnregisteredScope() {
        probes.add(probe("queue", () -> Optional.of(Alert.warning("100 queued"))));
        manager.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> !publisherSettings.getEvents().isEmpty());

            manager.unregister(probes);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(publisherSettings.getEvents())
                    .extracting(AlertEvent::getType).containsExactly(RAISED, CLEARED));
        } finally {
            manager.stop(Deadline.unlimited());
        }
    }

    @Test
    void aStoppedManagerDoesNotClearTheAlertsOfAnUnregisteredScope() {
        probes.add(probe("queue", () -> Optional.of(Alert.warning("100 queued"))));
        manager.tick();
        manager.start();
        manager.stop(Deadline.unlimited());

        manager.unregister(probes);

        assertThat(publisherSettings.getEvents()).extracting(AlertEvent::getType).containsExactly(RAISED);
    }

    @Test
    void aProbeOnlyRunWhileActiveIsClearedWhenInactiveAndNotEvaluatedUntilActive() {
        CountingEvaluator evaluator = new CountingEvaluator(result::get);
        probes.add(probe("orders", evaluator));
        result.set(Optional.of(Alert.warning("no order")));
        manager.tick();
        listened.clear();

        active.set(false);
        tick(3);

        assertThat(publisherSettings.getEvents()).extracting(AlertEvent::getType).containsExactly(RAISED, CLEARED);
        assertThat(listened).containsExactly("transition orders orders normal");
        assertThat(manager.getProbeStates(SCOPE)).singleElement().satisfies(state -> {
            assertThat(state.isSuspended()).isTrue();
            assertThat(state.getAlert()).isEmpty();
        });
        assertThat(evaluator.evaluations).isEqualTo(1);
        assertThat(manager.getActiveAlerts()).isEmpty();
        assertThat(manager.clearAlert(ProbeId.of(SCOPE, "orders"))).isFalse();
    }

    @Test
    void onceActiveTheEvaluatorIsResetAndEvaluatedAtOnceWhateverItsInterval() {
        CountingEvaluator evaluator = new CountingEvaluator(result::get);
        probes.add(Probe.builder().name("orders").description("d").evaluator(evaluator)
                .evaluationInterval(Duration.ofSeconds(10)).build());
        manager.tick();
        active.set(false);
        manager.tick();
        result.set(Optional.of(Alert.warning("no order")));

        active.set(true);
        manager.tick();

        assertThat(evaluator.resets).isEqualTo(1);
        assertThat(manager.getProbeStates(SCOPE)).singleElement()
                .satisfies(state -> assertThat(state.isSuspended()).isFalse());
        assertThat(evaluator.evaluations).isEqualTo(2);
        assertThat(publisherSettings.getEvents()).extracting(AlertEvent::getType).containsExactly(RAISED);
    }

    @Test
    void aProbeAddedWhileInactiveIsSuspendedWithoutAnEvaluationUntilActive() {
        active.set(false);
        CountingEvaluator evaluator = new CountingEvaluator(result::get);
        probes.add(probe("orders", evaluator));
        tick(2);

        assertThat(evaluator.evaluations).isZero();
        assertThat(manager.getProbeStates(SCOPE)).singleElement().satisfies(state -> {
            assertThat(state.isSuspended()).isTrue();
            assertThat(state.getLastEvaluation()).isEmpty();
        });
        active.set(true);
        manager.tick();
        assertThat(evaluator.evaluations).isEqualTo(1);
        assertThat(manager.getProbeStates(SCOPE)).singleElement()
                .satisfies(state -> assertThat(state.getLastEvaluation()).contains(NOW));
    }

    @Test
    void aProbeRunWhetherActiveOrNotKeepsItsAlertWhenInactive() {
        result.set(Optional.of(Alert.critical("disk full")));
        probes.add(Probe.builder().name("disk").description("d").evaluator(result::get).onlyWhileActive(false).build());
        manager.tick();

        active.set(false);
        tick(2);

        assertThat(publisherSettings.getEvents()).extracting(AlertEvent::getType).containsExactly(RAISED);
        assertThat(manager.getActiveAlerts()).hasSize(1);
    }

    @Test
    void aScopeWithoutAProbeOnlyRunWhileActiveNeverHasItsActivityRead() {
        probes.add(Probe.builder().name("disk").description("d").evaluator(result::get).onlyWhileActive(false).build());
        tick(2);

        assertThat(activityReads).hasValue(0);
    }

    private void tick(int times) {
        for (int i = 0; i < times; i++) {
            manager.tick();
        }
    }

    private static Probe probe(String name, ProbeEvaluator evaluator) {
        return Probe.builder().name(name).description(name + " description").evaluator(evaluator).build();
    }

    private static final class CountingEvaluator implements ProbeEvaluator {

        private final ProbeEvaluator delegate;
        private int evaluations;
        private int resets;

        CountingEvaluator(ProbeEvaluator delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<Alert> evaluate() {
            evaluations++;
            return delegate.evaluate();
        }

        @Override
        public void reset() {
            resets++;
        }
    }

    /**
     * Records each call as one line; a null list makes every call fail.
     */
    private static final class RecordingListener implements ProbesListener {

        private final List<String> calls;

        RecordingListener(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void onProbeAdded(ProbeScope scope, ProbeDefinition probe) {
            calls.add("added " + sessionName(scope) + " " + probe.getName());
        }

        @Override
        public void onProbeRemoved(ProbeScope scope, String probeName) {
            calls.add("removed " + sessionName(scope) + " " + probeName);
        }

        @Override
        public void onAlertTransition(ProbeScope scope, ProbeState state) {
            calls.add("transition " + sessionName(scope) + " " + state.getProbeName() + " "
                    + state.getAlert().map(alert -> alert.getLevel().name()).orElse("normal")
                    + (state.isClearedManually() ? " manually" : ""));
        }

        private static String sessionName(ProbeScope scope) {
            return scope.getLabels().get(FixSessionProbeScope.NAME_LABEL);
        }
    }
}
