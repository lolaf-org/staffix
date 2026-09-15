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
package org.lolaf.staffix.tracing.otlp.sender;

import io.opentelemetry.sdk.common.export.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retry arithmetic, tested without a server, a thread or a clock - which is the whole reason it is a
 * class of its own.
 */
class RetryScheduleTest {

    /**
     * Jitter that always takes the full bound, so a delay assertion is about the schedule rather than
     * about a sample of it.
     */
    private static final RetrySchedule DEFAULTS =
            new RetrySchedule(RetryPolicy.builder().build(), () -> 1.0);

    private static List<Long> backoffMillis(RetrySchedule schedule) {
        List<Long> millis = new ArrayList<>();
        for (int attempt = 1; attempt < schedule.maxAttempts(); attempt++) {
            millis.add(Duration.ofNanos(schedule.backoffBoundNanos(attempt)).toMillis());
        }
        return millis;
    }

    @Test
    void growsTheBackoffByTheMultiplierOnTheOtelDefaults() {
        assertThat(DEFAULTS.maxAttempts()).isEqualTo(5);
        assertThat(backoffMillis(DEFAULTS)).containsExactly(1000L, 1500L, 2250L, 3375L);
    }

    @Test
    void stopsGrowingAtTheMaximumBackoff() {
        RetrySchedule capped = new RetrySchedule(RetryPolicy.builder()
                .setMaxAttempts(5)
                .setInitialBackoff(Duration.ofMillis(100))
                .setMaxBackoff(Duration.ofMillis(250))
                .setBackoffMultiplier(2.0)
                .build(), () -> 1.0);

        assertThat(backoffMillis(capped)).containsExactly(100L, 200L, 250L, 250L);
    }

    @Test
    void reportsTheWorstCaseWaitOfAFullyRetriedPublish() {
        // 1 + 1.5 + 2.25 + 3.375, which is the figure to hold against the exporter timeout.
        assertThat(DEFAULTS.worstCaseTotal()).isEqualTo(Duration.ofMillis(8125));
    }

    @Test
    void drawsTheDelayBelowTheBoundWhenTheJitterIsPartial() {
        RetrySchedule half = new RetrySchedule(RetryPolicy.builder().build(), () -> 0.5);

        assertThat(Duration.ofNanos(half.backoffNanos(1)).toMillis()).isEqualTo(500);
        assertThat(Duration.ofNanos(half.backoffNanos(4)).toMillis()).isEqualTo(1687);
    }

    @Test
    void jittersWithinTheBoundOnTheRealRandomSource() {
        RetrySchedule real = new RetrySchedule(RetryPolicy.builder().build());

        for (int i = 0; i < 100; i++) {
            assertThat(real.backoffNanos(1))
                    .isGreaterThanOrEqualTo(0)
                    .isLessThan(real.backoffBoundNanos(1));
        }
    }

    /**
     * Not a test of this class so much as of the contract it rests on: OTel refuses to build a policy
     * outside two to five attempts, which is what bounds {@link RetrySchedule#worstCaseTotal()} and so
     * what makes it comparable against an exporter timeout. If an upgrade widens the range, that
     * reasoning needs revisiting and this test is where it surfaces.
     */
    @Test
    void restsOnOtelRefusingAPolicyOutsideTwoToFiveAttempts() {
        assertThatThrownBy(() -> RetryPolicy.builder().setMaxAttempts(1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
        assertThatThrownBy(() -> RetryPolicy.builder().setMaxAttempts(6).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    void refusesABackoffForAnAttemptThatHasNoRetryAfterIt() {
        assertThatThrownBy(() -> DEFAULTS.backoffBoundNanos(5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DEFAULTS.backoffBoundNanos(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retriesExactlyTheHttpStatusesOtelConsidersTransient() {
        List<Integer> retryable = new ArrayList<>();
        for (int status = 100; status < 600; status++) {
            if (RetrySchedule.isRetryableHttp(status)) {
                retryable.add(status);
            }
        }

        assertThat(retryable).containsExactly(429, 502, 503, 504);
    }

    @Test
    void retriesExactlyTheGrpcStatusesOtelConsidersTransient() {
        List<Integer> retryable = new ArrayList<>();
        for (int status = 0; status <= 16; status++) {
            if (RetrySchedule.isRetryableGrpc(status)) {
                retryable.add(status);
            }
        }

        assertThat(retryable).containsExactly(1, 4, 8, 10, 11, 14, 15);
    }

    @Test
    void retriesTheTransportFailuresOtelRetriesWhenThePolicyNamesNoPredicate() {
        assertThat(DEFAULTS.isRetryable(new SocketTimeoutException())).isTrue();
        assertThat(DEFAULTS.isRetryable(new ConnectException())).isTrue();
        assertThat(DEFAULTS.isRetryable(new UnknownHostException())).isTrue();

        assertThat(DEFAULTS.isRetryable(new EOFException())).isFalse();
        assertThat(DEFAULTS.isRetryable(new IOException("something else entirely"))).isFalse();
    }

    @Test
    void defersToThePolicysOwnPredicateWhenItHasOne() {
        RetrySchedule fussy = new RetrySchedule(RetryPolicy.builder()
                .setRetryExceptionPredicate(e -> e instanceof EOFException)
                .build(), () -> 1.0);

        assertThat(fussy.isRetryable(new EOFException())).isTrue();
        assertThat(fussy.isRetryable(new SocketTimeoutException()))
                .as("a configured predicate replaces the default rather than adding to it")
                .isFalse();
    }
}
