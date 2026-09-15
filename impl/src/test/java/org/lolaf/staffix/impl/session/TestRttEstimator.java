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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.RttMeasurement;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TestRttEstimator {

    private static final long ONE_MS_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long T1_WALL_NANOS = 1_700_000_000L * 1_000_000_000L; // arbitrary wall clock baseline

    private static RttEstimator newEstimator(Duration maxRtt) {
        return new RttEstimator(FixSessionSettings.RttMeasurementSettings.builder()
                .maxAcceptedRtt(maxRtt)
                .emaTimeWindow(Duration.ofSeconds(2))
                .sendingTimeToWireDelay(Duration.ZERO)
                .build());
    }

    private static RttEstimator newEstimatorWithBias(Duration bias) {
        return new RttEstimator(FixSessionSettings.RttMeasurementSettings.builder()
                .maxAcceptedRtt(Duration.ofSeconds(2))
                .emaTimeWindow(Duration.ofSeconds(2))
                .sendingTimeToWireDelay(bias)
                .build());
    }

    @Test
    void measurementIsEmptyBeforeFirstSample() {
        RttEstimator estimator = newEstimator(Duration.ofSeconds(2));

        assertThat(estimator.getMeasurement()).isEmpty();
        assertThat(estimator.getSampleCount()).isZero();
    }

    @Test
    void firstSampleSeedsTheEmaWithTheRawRttAndOffset() {
        RttEstimator estimator = newEstimator(Duration.ofSeconds(2));
        long monoT1 = 1_000_000L;
        long monoT2 = monoT1 + 5 * ONE_MS_NANOS;
        long wallT1 = T1_WALL_NANOS;
        long wallT2 = wallT1 + 5 * ONE_MS_NANOS;
        // Remote clock perfectly synced — its SendingTime sits at the midpoint of T1..T2 plus the
        // one-way-latency assumption (RTT/2). With symmetric latency the remote-send instant on the
        // local wall clock is exactly the midpoint, so the offset should be zero.
        long remoteSendingTimeNanos = (wallT1 + wallT2) / 2;

        estimator.recordTestRequestSent("R-1", monoT1, wallT1);
        Optional<RttMeasurement> returned = estimator.recordHeartbeatReceived("R-1", monoT2, wallT2, remoteSendingTimeNanos);

        RttMeasurement measurement = returned.orElseThrow();
        assertThat(measurement.getRoundTripTime()).isEqualTo(Duration.ofNanos(5 * ONE_MS_NANOS));
        assertThat(measurement.getClockOffset()).isEqualTo(Duration.ZERO);
        assertThat(measurement.getSampleTime()).isNotNull();
        assertThat(estimator.getSampleCount()).isEqualTo(1);
        // Returned snapshot should equal the one read back via getMeasurement().
        assertThat(estimator.getMeasurement()).contains(measurement);
    }

    @Test
    void positiveOffsetWhenRemoteClockIsAhead() {
        RttEstimator estimator = newEstimator(Duration.ofSeconds(2));
        long monoT1 = 0L;
        long monoT2 = 4 * ONE_MS_NANOS;
        long wallT1 = T1_WALL_NANOS;
        long wallT2 = wallT1 + 4 * ONE_MS_NANOS;
        long midpoint = (wallT1 + wallT2) / 2;
        long skewNanos = 7 * ONE_MS_NANOS; // remote is 7 ms ahead
        long remoteSendingTimeNanos = midpoint + skewNanos;

        estimator.recordTestRequestSent("R-1", monoT1, wallT1);
        estimator.recordHeartbeatReceived("R-1", monoT2, wallT2, remoteSendingTimeNanos);

        assertThat(estimator.getMeasurement().orElseThrow().getClockOffset())
                .isEqualTo(Duration.ofNanos(skewNanos));
    }

    @Test
    void negativeOffsetWhenRemoteClockIsBehind() {
        RttEstimator estimator = newEstimator(Duration.ofSeconds(2));
        long monoT1 = 0L;
        long monoT2 = 4 * ONE_MS_NANOS;
        long wallT1 = T1_WALL_NANOS;
        long wallT2 = wallT1 + 4 * ONE_MS_NANOS;
        long midpoint = (wallT1 + wallT2) / 2;
        long skewNanos = -3 * ONE_MS_NANOS;
        long remoteSendingTimeNanos = midpoint + skewNanos;

        estimator.recordTestRequestSent("R-1", monoT1, wallT1);
        estimator.recordHeartbeatReceived("R-1", monoT2, wallT2, remoteSendingTimeNanos);

        assertThat(estimator.getMeasurement().orElseThrow().getClockOffset())
                .isEqualTo(Duration.ofNanos(skewNanos));
    }

    @Test
    void outlierSampleAboveMaxAcceptedRttIsDiscarded() {
        Duration maxRtt = Duration.ofMillis(10);
        RttEstimator estimator = newEstimator(maxRtt);
        long monoT1 = 0L;
        long monoT2 = TimeUnit.MILLISECONDS.toNanos(50); // way above 10ms max
        long wallT1 = T1_WALL_NANOS;
        long wallT2 = wallT1 + monoT2;
        long midpoint = (wallT1 + wallT2) / 2;

        estimator.recordTestRequestSent("R-1", monoT1, wallT1);
        estimator.recordHeartbeatReceived("R-1", monoT2, wallT2, midpoint);

        assertThat(estimator.getMeasurement()).isEmpty();
        assertThat(estimator.getSampleCount()).isZero();
    }

    @Test
    void responseForUnknownTestReqIdIsIgnored() {
        RttEstimator estimator = newEstimator(Duration.ofSeconds(2));

        estimator.recordHeartbeatReceived("never-sent", 0L, T1_WALL_NANOS, T1_WALL_NANOS);

        assertThat(estimator.getMeasurement()).isEmpty();
        assertThat(estimator.getSampleCount()).isZero();
    }

    @Test
    void duplicateResponseForSameTestReqIdIsIgnored() {
        RttEstimator estimator = newEstimator(Duration.ofSeconds(2));
        long monoT1 = 0L;
        long monoT2 = 3 * ONE_MS_NANOS;
        long wallT1 = T1_WALL_NANOS;
        long wallT2 = wallT1 + 3 * ONE_MS_NANOS;
        long midpoint = (wallT1 + wallT2) / 2;

        estimator.recordTestRequestSent("R-1", monoT1, wallT1);
        estimator.recordHeartbeatReceived("R-1", monoT2, wallT2, midpoint);
        // Second response for the same id — should be a no-op since we removed it from in-flight.
        estimator.recordHeartbeatReceived("R-1", monoT2 + 100 * ONE_MS_NANOS, wallT2, midpoint);

        assertThat(estimator.getSampleCount()).isEqualTo(1);
        assertThat(estimator.getMeasurement().orElseThrow().getRoundTripTime())
                .isEqualTo(Duration.ofNanos(3 * ONE_MS_NANOS));
    }

    @Test
    void rttEmaConvergesTowardsTheSampleStream() {
        RttEstimator estimator = newEstimator(Duration.ofSeconds(2));
        long stableRttNanos = 5 * ONE_MS_NANOS;
        long wall = T1_WALL_NANOS;
        // Feed many samples with the same RTT — EMA must converge to that value.
        for (int i = 0; i < 50; i++) {
            String id = "R-" + i;
            long monoT1 = i * 1_000_000L;
            long monoT2 = monoT1 + stableRttNanos;
            long wallT1 = wall + i * 1_000_000L;
            long wallT2 = wallT1 + stableRttNanos;
            estimator.recordTestRequestSent(id, monoT1, wallT1);
            estimator.recordHeartbeatReceived(id, monoT2, wallT2, (wallT1 + wallT2) / 2);
        }

        // Allow a tiny tolerance; with alpha derived from a 2s window we converge well within 1%.
        Duration ema = estimator.getMeasurement().orElseThrow().getRoundTripTime();
        assertThat(Math.abs(ema.toNanos() - stableRttNanos)).isLessThan(stableRttNanos / 100);
        assertThat(estimator.getSampleCount()).isEqualTo(50);
    }

    @Test
    void resetClearsInFlightProbesAndEmaSnapshot() {
        RttEstimator estimator = newEstimator(Duration.ofSeconds(2));
        long monoT1 = 0L;
        long wallT1 = T1_WALL_NANOS;
        // First sample so the EMA holds a value, then a second probe goes in-flight but never returns.
        estimator.recordTestRequestSent("R-done", monoT1, wallT1);
        estimator.recordHeartbeatReceived("R-done", monoT1 + 2 * ONE_MS_NANOS, wallT1 + 2 * ONE_MS_NANOS,
                wallT1 + ONE_MS_NANOS);
        estimator.recordTestRequestSent("R-pending", monoT1 + 10 * ONE_MS_NANOS, wallT1 + 10 * ONE_MS_NANOS);

        assertThat(estimator.getSampleCount()).isNotZero();

        estimator.reset();
        // The pending probe's late response is now a no-op.
        estimator.recordHeartbeatReceived("R-pending", monoT1 + 20 * ONE_MS_NANOS, wallT1 + 20 * ONE_MS_NANOS,
                wallT1 + 15 * ONE_MS_NANOS);

        assertThat(estimator.getSampleCount()).isZero();
        assertThat(estimator.getMeasurement()).isEmpty();
    }

    @Test
    void unansweredProbesDoNotAccumulateForever() {
        // A peer that keeps traffic flowing but never answers a TestRequest: the heartbeat machinery never
        // disconnects it, so nothing but the estimator's own bound stops the in-flight map from growing.
        RttEstimator estimator = new RttEstimator(FixSessionSettings.RttMeasurementSettings.builder()
                .probeInterval(Duration.ofMillis(100))
                .maxAcceptedRtt(Duration.ofSeconds(2))
                .emaTimeWindow(Duration.ofSeconds(2))
                .build());
        // 2s / 100ms = 20 probes could legitimately be outstanding, doubled for slack
        int expectedBound = 2 * (1 + 20);

        for (int i = 0; i < 10_000; i++) {
            estimator.recordTestRequestSent("R-" + i, i * TimeUnit.MILLISECONDS.toNanos(100), T1_WALL_NANOS);
        }

        assertThat(estimator.getInflightProbesCount()).isEqualTo(expectedBound);
    }

    @Test
    void theOldestUnansweredProbeIsTheOneEvicted() {
        RttEstimator estimator = new RttEstimator(FixSessionSettings.RttMeasurementSettings.builder()
                .probeInterval(Duration.ofSeconds(1))
                .maxAcceptedRtt(Duration.ofSeconds(1))
                // 2 * (1 + 1) = 4 outstanding probes allowed, below the 16 floor, so the floor applies
                .emaTimeWindow(Duration.ofSeconds(2))
                .sendingTimeToWireDelay(Duration.ZERO)
                .build());
        for (int i = 0; i < 17; i++) {
            estimator.recordTestRequestSent("R-" + i, i * ONE_MS_NANOS, T1_WALL_NANOS + i * ONE_MS_NANOS);
        }

        assertThat(estimator.getInflightProbesCount()).isEqualTo(16);
        // R-0 was pushed out, so its late answer is now an unknown TestReqID...
        assertThat(estimator.recordHeartbeatReceived("R-0", 100 * ONE_MS_NANOS, T1_WALL_NANOS + 100 * ONE_MS_NANOS,
                T1_WALL_NANOS)).isEmpty();
        // ...while the most recent probe still measures.
        long wallT1 = T1_WALL_NANOS + 16 * ONE_MS_NANOS;
        long wallT2 = wallT1 + 2 * ONE_MS_NANOS;
        assertThat(estimator.recordHeartbeatReceived("R-16", 18 * ONE_MS_NANOS, wallT2, (wallT1 + wallT2) / 2))
                .isPresent();
    }

    @Test
    void sendingTimeToWireDelayIsSubtractedWhenRawOffsetExceedsBias() {
        Duration bias = Duration.ofNanos(2_000L);
        RttEstimator estimator = newEstimatorWithBias(bias);
        long monoT1 = 0L;
        long monoT2 = 4 * ONE_MS_NANOS;
        long wallT1 = T1_WALL_NANOS;
        long wallT2 = wallT1 + 4 * ONE_MS_NANOS;
        long midpoint = (wallT1 + wallT2) / 2;
        long rawOffset = 10_000L; // 10µs, comfortably above the 2µs bias
        long remoteSendingTimeNanos = midpoint + rawOffset;

        estimator.recordTestRequestSent("R-1", monoT1, wallT1);
        estimator.recordHeartbeatReceived("R-1", monoT2, wallT2, remoteSendingTimeNanos);

        assertThat(estimator.getMeasurement().orElseThrow().getClockOffset())
                .isEqualTo(Duration.ofNanos(rawOffset - bias.toNanos()));
    }

    @Test
    void sendingTimeToWireDelayIsIgnoredWhenRawOffsetIsBelowBias() {
        Duration bias = Duration.ofNanos(2_000L);
        RttEstimator estimator = newEstimatorWithBias(bias);
        long monoT1 = 0L;
        long monoT2 = 4 * ONE_MS_NANOS;
        long wallT1 = T1_WALL_NANOS;
        long wallT2 = wallT1 + 4 * ONE_MS_NANOS;
        long midpoint = (wallT1 + wallT2) / 2;
        // Raw offset of 1µs is below the 2µs bias — leave it alone to avoid sign-flipping.
        long rawOffset = 1_000L;
        long remoteSendingTimeNanos = midpoint + rawOffset;

        estimator.recordTestRequestSent("R-1", monoT1, wallT1);
        estimator.recordHeartbeatReceived("R-1", monoT2, wallT2, remoteSendingTimeNanos);

        assertThat(estimator.getMeasurement().orElseThrow().getClockOffset())
                .isEqualTo(Duration.ofNanos(rawOffset));
    }
}
