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
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.RttMeasurement;
import org.lolaf.staffix.api.time.UTCTime;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Maintains an EMA-smoothed estimate of network RTT and remote-vs-local wall-clock offset for a
 * FIX session, based on TestRequest probes and their matching Heartbeat responses.
 *
 * <p><b>RTT.</b> Computed locally from monotonic-clock readings at probe send and response receipt;
 * unaffected by wall-clock skew or leap-second adjustments on either side.
 *
 * <p><b>Clock offset.</b> Computed NTP-style: given the remote SendingTime {@code R} (tag 52 of the
 * response), the local wall-clock at probe send {@code T1} and at response receipt {@code T2},
 * {@code offset = R - (T1 + T2) / 2}. Positive values mean the remote clock is ahead of the local
 * clock. The estimator assumes symmetric one-way latency, which is the standard NTP assumption.
 *
 * <p>All mutating methods are expected to be called from the FIX session's IO thread (the
 * heartbeat scheduler and the response decoder both run there); the EMA fields are
 * {@code volatile} so concurrent reads from other threads observe a consistent recent value.
 *
 * <p>Outliers — samples whose measured RTT exceeds
 * {@link FixSessionSettings.RttMeasurementSettings#getMaxAcceptedRtt()} — are dropped without
 * updating the EMAs.
 *
 * <p>The set of probes awaiting an answer is bounded: a peer that never answers a TestRequest but keeps the session
 * alive with other traffic would otherwise leave one entry behind per probe interval, for the life of the session.
 */
@Slf4j
public class RttEstimator {

    /**
     * Floor and ceiling for {@link #maxInflightProbes}. The floor keeps a session with a long probe interval from
     * being capped at nothing; the ceiling keeps the map small whatever the settings say — 1024 entries is a few
     * tens of kilobytes, and a session with that many probes outstanding has lost the peer, not a measurement.
     */
    private static final int MIN_INFLIGHT_PROBES = 16;
    private static final int MAX_INFLIGHT_PROBES = 1024;

    private final Map<String, ProbeSample> inflight;
    private final long maxAcceptedRttNanos;
    private final long sendingTimeToWireDelayNanos;
    private final double tauNanos;
    private final int maxInflightProbes;

    private long rttEmaNanos;
    private long clockOffsetEmaNanos;
    private long lastSampleAtEpochNanos;
    @Getter
    private long sampleCount;
    private Optional<RttMeasurement> lastRttMeasurement;

    public RttEstimator(FixSessionSettings.RttMeasurementSettings settings) {
        this.maxInflightProbes = calculateMaxInflightProbes(settings);
        // Insertion-ordered so the eldest entry is the oldest probe: a peer that answers TestRequests keeps this
        // map at one or two entries, but one that ignores them while staying otherwise talkative — so the heartbeat
        // machinery never disconnects it — would grow it by one entry per probe interval for the life of the
        // session. Evicting the eldest bounds that at a probe the estimator could no longer have accepted anyway.
        this.inflight = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ProbeSample> eldest) {
                boolean evict = size() > maxInflightProbes;
                if (evict && log.isDebugEnabled()) {
                    log.debug("Dropping unanswered RTT probe {}: more than {} probes in flight", eldest.getKey(), maxInflightProbes);
                }
                return evict;
            }
        };
        Duration maxRtt = settings.getMaxAcceptedRtt();
        this.maxAcceptedRttNanos = (maxRtt == null || maxRtt.isZero() || maxRtt.isNegative()) ? Long.MAX_VALUE : maxRtt.toNanos();
        Duration sendingTimeToWireDelay = settings.getSendingTimeToWireDelay();
        this.sendingTimeToWireDelayNanos = (sendingTimeToWireDelay == null || sendingTimeToWireDelay.isZero() || sendingTimeToWireDelay.isNegative()) ? 0L : sendingTimeToWireDelay.toNanos();
        double windowSeconds = getWindowSeconds(settings);
        this.tauNanos = windowSeconds * 1e9 / 3.0;
        reset();
    }

    /**
     * How many probes may legitimately be outstanding at once: a probe older than {@code maxAcceptedRtt} would be
     * discarded as an outlier even if it were answered, so anything beyond that many probe intervals is dead weight.
     * Doubled for slack, and clamped, so an unusual or disabled setting still yields a bounded map.
     */
    private static int calculateMaxInflightProbes(FixSessionSettings.RttMeasurementSettings settings) {
        Duration maxRtt = settings.getMaxAcceptedRtt();
        Duration probeInterval = settings.getProbeInterval();
        if (maxRtt == null || maxRtt.isZero() || maxRtt.isNegative()
                || probeInterval == null || probeInterval.isZero() || probeInterval.isNegative()) {
            return MAX_INFLIGHT_PROBES;
        }
        long concurrentProbes = 2 * (1 + maxRtt.toNanos() / Math.max(1L, probeInterval.toNanos()));
        return (int) Math.max(MIN_INFLIGHT_PROBES, Math.min(MAX_INFLIGHT_PROBES, concurrentProbes));
    }

    private static double getWindowSeconds(FixSessionSettings.RttMeasurementSettings settings) {
        Duration window = settings.getEmaTimeWindow();
        // Time-based EMA: per-sample weight is computed from the actual wall-clock elapsed time
        // since the previous accepted sample, so the estimator is sample-rate-invariant and copes
        // uniformly with continuous probing, heartbeat-driven probes, and dropped outliers.
        // tau = window / 3 makes the EMA "forget" ~95% of the past over the configured window.
        return window == null || window.isZero() || window.isNegative() ? 30.0 : Math.max(1.0, window.getSeconds());
    }

    private static long ema(long previous, long sample, double alpha) {
        return (long) (alpha * sample + (1.0 - alpha) * previous);
    }

    /**
     * Forget any in-flight probes (e.g. on logout/disconnect). Must be called from the IO thread.
     */
    public void reset() {
        inflight.clear();
        rttEmaNanos = Long.MIN_VALUE;
        clockOffsetEmaNanos = 0L;
        lastSampleAtEpochNanos = 0L;
        sampleCount = 0;
        lastRttMeasurement = Optional.empty();
    }

    /**
     * Record that a TestRequest probe was sent. Must be called from the session's IO thread.
     *
     * @param testReqId          the TestReqID (tag 112) of the outgoing TestRequest
     * @param sendMonotonicNanos local monotonic time at send (e.g. {@code Clock.nanoTime()})
     * @param sendWallNanos      local wall-clock epoch nanos at send (the tag 52 we put in the message)
     */
    public void recordTestRequestSent(String testReqId, long sendMonotonicNanos, long sendWallNanos) {
        inflight.put(testReqId, new ProbeSample(sendMonotonicNanos, sendWallNanos));
    }

    /**
     * Record receipt of a Heartbeat response carrying a matching TestReqID. Must be called from the
     * session's IO thread.
     *
     * @param testReqId              TestReqID (tag 112) of the received Heartbeat
     * @param recvMonotonicNanos     local monotonic time at receipt
     * @param recvWallNanos          local wall-clock epoch nanos at receipt
     * @param remoteSendingTimeNanos remote SendingTime (tag 52) in epoch nanos
     * @return the updated measurement snapshot if the sample was accepted, or
     * {@link Optional#empty()} for unknown / duplicate TestReqIDs and outlier samples
     */
    public Optional<RttMeasurement> recordHeartbeatReceived(String testReqId, long recvMonotonicNanos, long recvWallNanos, long remoteSendingTimeNanos) {
        if (testReqId == null) {
            return Optional.empty();
        }
        ProbeSample sent = inflight.remove(testReqId);
        if (sent == null) {
            return Optional.empty();
        }
        long rttNanos = recvMonotonicNanos - sent.sendMonotonicNanos;
        if (rttNanos < 0 || rttNanos > maxAcceptedRttNanos) {
            log.debug("Dropping RTT sample for {}: rtt={}ns outside accepted range [0, {}ns]", testReqId, rttNanos, maxAcceptedRttNanos);
            return Optional.empty();
        }
        long midpointWallNanos = sent.sendWallNanos + (recvWallNanos - sent.sendWallNanos) / 2;
        long offsetNanos = remoteSendingTimeNanos - midpointWallNanos;
        // Compensate for the remote-side delay between stamping tag 52 into the outgoing buffer
        // and the bytes actually leaving the wire; see FixSessionSettings.RttMeasurementSettings.
        // Only subtract the bias when the raw offset exceeds it — otherwise the correction would
        // sign-flip the reported offset, which is worse than leaving the small sample alone.
        if (offsetNanos > sendingTimeToWireDelayNanos) {
            offsetNanos -= sendingTimeToWireDelayNanos;
        }

        if (rttEmaNanos == Long.MIN_VALUE) {
            rttEmaNanos = rttNanos;
            clockOffsetEmaNanos = offsetNanos;
        } else {
            double alpha = alphaForElapsed(recvWallNanos - lastSampleAtEpochNanos);
            rttEmaNanos = ema(rttEmaNanos, rttNanos, alpha);
            clockOffsetEmaNanos = ema(clockOffsetEmaNanos, offsetNanos, alpha);
        }
        if (log.isDebugEnabled()) {
            log.debug("RTT = {} micros, ema = {} micros, clock offset = {} micros",
                    TimeUnit.NANOSECONDS.toMicros(rttNanos), TimeUnit.NANOSECONDS.toMicros(rttEmaNanos), TimeUnit.NANOSECONDS.toMicros(clockOffsetEmaNanos));
        }
        lastSampleAtEpochNanos = recvWallNanos;
        sampleCount++;
        lastRttMeasurement = Optional.of(new RttMeasurement(
                Duration.ofNanos(rttEmaNanos),
                Duration.ofNanos(clockOffsetEmaNanos),
                UTCTime.of(lastSampleAtEpochNanos)));
        return lastRttMeasurement;
    }

    /**
     * Snapshot of RTT, clock offset, and last-sample time, or
     * {@link Optional#empty()} if no sample has been recorded yet.
     */
    public Optional<RttMeasurement> getMeasurement() {
        return lastRttMeasurement;
    }

    /**
     * Probes sent and not yet answered. Bounded: see the eviction policy in the constructor.
     */
    int getInflightProbesCount() {
        return inflight.size();
    }

    private double alphaForElapsed(long elapsedNanos) {
        // Δt ≤ 0 means same-instant or out-of-order timestamps; leave the EMA untouched.
        // Δt ≫ tau drives alpha → 1, which correctly reseeds the EMA when the session has been
        // idle long enough that the previous estimate is stale.
        if (elapsedNanos <= 0L) {
            return 0.0;
        }
        return 1.0 - Math.exp(-elapsedNanos / tauNanos);
    }

    private static final class ProbeSample {
        final long sendMonotonicNanos;
        final long sendWallNanos;

        ProbeSample(long sendMonotonicNanos, long sendWallNanos) {
            this.sendMonotonicNanos = sendMonotonicNanos;
            this.sendWallNanos = sendWallNanos;
        }
    }
}