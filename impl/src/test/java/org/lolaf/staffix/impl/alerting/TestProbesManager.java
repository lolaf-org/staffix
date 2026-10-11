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
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.AlertEvent;
import org.lolaf.staffix.api.alerting.AlertLevel;
import org.lolaf.staffix.api.alerting.FixAlertsPublisher;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeEvaluator;
import org.lolaf.staffix.api.alerting.ProbeDefinition;
import org.lolaf.staffix.api.alerting.ProbeState;
import org.lolaf.staffix.api.alerting.ProbesListener;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.tests.TestingFixAlertsPublisher;
import org.lolaf.staffix.tests.TestingFixAlertsPublisherSettings;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.api.alerting.AlertEvent.Type.CHANGED;
import static org.lolaf.staffix.api.alerting.AlertEvent.Type.CLEARED;
import static org.lolaf.staffix.api.alerting.AlertEvent.Type.RAISED;
import static org.lolaf.staffix.api.alerting.AlertEvent.Type.REMINDED;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestProbesManager {

    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    private static final FixSessionId SESSION_ID = FixSessionId.of("orders", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
    private static final FixSessionId OTHER_SESSION_ID = FixSessionId.of("other", FixRegularVersion.VERSION_44, "SENDER", "TARGET");

    private final TestingFixAlertsPublisherSettings publisherSettings = TestingFixAlertsPublisherSettings.builder().build();
    private final FixSession session = mock(FixSession.class);
    private final FixSessionProbesImpl probes = new FixSessionProbesImpl();
    private final AtomicReference<Optional<Alert>> result = new AtomicReference<>(Optional.empty());
    private final List<String> listened = new CopyOnWriteArrayList<>();
    private final ProbesListeners listeners = new ProbesListeners();
    private final ProbesManager manager = new ProbesManager("engine", Clock.fixed(NOW, ZoneOffset.UTC), listeners);

    TestProbesManager() {
        when(session.getFixSessionId()).thenReturn(SESSION_ID);
        listeners.add(new RecordingListener(listened));
        manager.register(session, probes, new TestingFixAlertsPublisher(publisherSettings));
    }

    @Test
    void theListenerIsToldOfTheProbesASessionHadWhenRegisteredAndOfEachChange() {
        FixSession other = mock(FixSession.class);
        when(other.getFixSessionId()).thenReturn(OTHER_SESSION_ID);
        FixSessionProbesImpl otherProbes = new FixSessionProbesImpl();
        otherProbes.add(probe("before", result::get));

        manager.register(other, otherProbes, new TestingFixAlertsPublisher(publisherSettings));
        otherProbes.add(probe("after", result::get));
        otherProbes.remove("before");
        manager.unregister(other, otherProbes);
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

        assertThat(manager.getActiveAlerts()).singleElement().satisfies(active -> {
            assertThat(active.getFixSessionId()).isEqualTo(SESSION_ID);
            assertThat(active.getProbeState().getProbeName()).isEqualTo("alerting");
        });
    }

    @Test
    void aFailingListenerDoesNotKeepTheOthersFromBeingTold() {
        ProbesListeners failingFirst = new ProbesListeners();
        failingFirst.add(new RecordingListener(null));
        failingFirst.add(new RecordingListener(listened));

        failingFirst.onProbeRemoved(SESSION_ID, "queue");

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
            assertThat(event.getEngineId()).isEqualTo("engine");
            assertThat(event.getFixSessionId()).isEqualTo(SESSION_ID);
            assertThat(event.getProbeName()).isEqualTo("queue");
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
        assertThat(manager.getProbeStates(session)).singleElement().satisfies(state -> {
            assertThat(state.getAlert()).contains(Alert.warning("150 queued"));
            assertThat(state.getSince()).contains(NOW);
            assertThat(state.getLastEvaluation()).isEqualTo(NOW);
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
        assertThat(manager.getProbeStates(session)).isEmpty();
    }

    @Test
    void aFailingPublisherDoesNotStopTheEvaluation() {
        FixSession other = mock(FixSession.class);
        when(other.getFixSessionId()).thenReturn(SESSION_ID);
        FixSessionProbesImpl otherProbes = new FixSessionProbesImpl();
        manager.register(other, otherProbes, new FailingPublisher());
        otherProbes.add(probe("failing", () -> Optional.of(Alert.warning("x"))));
        probes.add(probe("queue", () -> Optional.of(Alert.warning("y"))));

        manager.tick();

        assertThat(publisherSettings.getEvents()).extracting(AlertEvent::getType).containsExactly(RAISED);
        assertThat(manager.getProbeStates(other)).hasSize(1);
    }

    @Test
    void aProbeHasNoStateUntilItsFirstEvaluation() {
        probes.add(probe("queue", result::get));
        assertThat(manager.getProbeStates(session)).isEmpty();

        manager.tick();

        assertThat(manager.getProbeStates(session)).extracting(ProbeState::getProbeName).containsExactly("queue");
        assertThat(manager.getProbeStates(session).get(0).getAlert()).isEmpty();
    }

    @Test
    void aStartedManagerEvaluatesEverySecondAndClearsTheAlertsOfAnUnregisteredSession() {
        probes.add(probe("queue", () -> Optional.of(Alert.warning("100 queued"))));
        manager.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> !publisherSettings.getEvents().isEmpty());

            manager.unregister(session, probes);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(publisherSettings.getEvents())
                    .extracting(AlertEvent::getType).containsExactly(RAISED, CLEARED));
        } finally {
            manager.stop(Deadline.unlimited());
        }
    }

    @Test
    void aStoppedManagerDoesNotClearTheAlertsOfAnUnregisteredSession() {
        probes.add(probe("queue", () -> Optional.of(Alert.warning("100 queued"))));
        manager.tick();
        manager.start();
        manager.stop(Deadline.unlimited());

        manager.unregister(session, probes);

        assertThat(publisherSettings.getEvents()).extracting(AlertEvent::getType).containsExactly(RAISED);
    }

    private void tick(int times) {
        for (int i = 0; i < times; i++) {
            manager.tick();
        }
    }

    private static Probe probe(String name, ProbeEvaluator evaluator) {
        return Probe.builder().name(name).description(name + " description").evaluator(evaluator).build();
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
        public void onProbeAdded(FixSessionId fixSessionId, ProbeDefinition probe) {
            calls.add("added " + fixSessionId.getName() + " " + probe.getName());
        }

        @Override
        public void onProbeRemoved(FixSessionId fixSessionId, String probeName) {
            calls.add("removed " + fixSessionId.getName() + " " + probeName);
        }

        @Override
        public void onAlertTransition(FixSessionId fixSessionId, ProbeState state) {
            calls.add("transition " + fixSessionId.getName() + " " + state.getProbeName() + " "
                    + state.getAlert().map(alert -> alert.getLevel().name()).orElse("normal"));
        }
    }

    private static final class FailingPublisher extends Startable.VoidStartable<FixAlertsPublisher> implements FixAlertsPublisher {

        @Override
        public String getInstanceId() {
            return "failing";
        }

        @Override
        public void publish(AlertEvent event) {
            throw new IllegalStateException("publisher down");
        }
    }
}
