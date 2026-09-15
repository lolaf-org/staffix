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
import org.lolaf.staffix.api.grpc.GrpcStatus;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

/**
 * The arithmetic of a {@link RetryPolicy}: how many attempts, how long to wait between them, and which
 * outcomes are worth another try.
 *
 * <p>Deliberately without a clock, an executor or any I/O, so that the part of retrying that is easy to
 * get wrong can be tested as a plain function. Whoever schedules the wait is
 * {@code StaffixHttpSender}; this only says how long it should be.
 *
 * <p><strong>The delay is jittered, because OTel's own sender jitters it.</strong> The backoff sequence
 * is an upper bound rather than a delay: the wait actually taken is uniform in {@code [0, bound)}, the
 * "full jitter" strategy {@code RetryInterceptor} uses, which exists so that many exporters failing
 * against the same collector do not march back in step. A drop-in replacement that quietly dropped the
 * jitter would turn a recovering collector into a synchronised stampede, so {@link #backoffNanos(int)}
 * keeps it and {@link #backoffBoundNanos(int)} exposes the bound the jitter is drawn against - which is
 * the part worth asserting in a test.
 *
 * <p>The retryable HTTP status set is copied from {@code io.opentelemetry.exporter.internal.RetryUtil},
 * which is {@code @Internal} and so cannot be depended on. It was read from version 1.65.0 and
 * <strong>must be re-checked on an OTel upgrade</strong>. The gRPC set is the same one, and lives in
 * {@link GrpcStatus} because the gRPC senders need it too.
 */
final class RetrySchedule {

    /**
     * The default when a policy carries no exception predicate, mirroring
     * {@code RetryInterceptor.isRetryableException}. {@link ConnectException} is a
     * {@link SocketException} and so already covered, but it is named here as OTel names it, to keep
     * the two lists readable side by side.
     */
    private static final Predicate<IOException> DEFAULT_RETRYABLE_EXCEPTION = e ->
            e instanceof SocketTimeoutException
                    || e instanceof ConnectException
                    || e instanceof UnknownHostException
                    || e instanceof SocketException;

    private final int maxAttempts;

    /**
     * One bound per retry, so {@code maxAttempts - 1} of them: the wait after attempt {@code n} is drawn
     * against {@code backoffBoundsNanos[n - 1]}. Precomputed, because the policy cannot change and a
     * failing publish should not be doing arithmetic.
     */
    private final long[] backoffBoundsNanos;

    private final Predicate<IOException> retryExceptionPredicate;

    private final DoubleSupplier jitter;

    /**
     * @param policy the policy to read; its defaults are 5 attempts, 1 s initial backoff, 5 s maximum
     *               and a 1.5 multiplier
     */
    RetrySchedule(RetryPolicy policy) {
        this(policy, Math::random);
    }

    /**
     * @param policy the policy to read
     * @param jitter source of the jitter factor, expected in {@code [0, 1)}; a test passes a fixed one
     *               so that the delay it asserts is the bound rather than a sample of it
     */
    RetrySchedule(RetryPolicy policy, DoubleSupplier jitter) {
        this.maxAttempts = policy.getMaxAttempts();
        this.jitter = jitter;
        Predicate<IOException> configured = policy.getRetryExceptionPredicate();
        this.retryExceptionPredicate = configured == null ? DEFAULT_RETRYABLE_EXCEPTION : configured;

        this.backoffBoundsNanos = new long[Math.max(0, maxAttempts - 1)];
        double currentNanos = policy.getInitialBackoff().toNanos();
        long maxNanos = policy.getMaxBackoff().toNanos();
        for (int i = 0; i < backoffBoundsNanos.length; i++) {
            backoffBoundsNanos[i] = Math.min((long) currentNanos, maxNanos);
            currentNanos = currentNanos * policy.getBackoffMultiplier();
        }
    }

    /**
     * Whether an HTTP status is worth another attempt: 429, 502, 503 and 504, and nothing else. A 4xx
     * that is not 429 means the request itself is wrong and repeating it cannot help.
     *
     * @param statusCode the status the collector answered with
     * @return whether to try again
     */
    static boolean isRetryableHttp(int statusCode) {
        switch (statusCode) {
            case 429:
            case 502:
            case 503:
            case 504:
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether a gRPC status is worth another attempt.
     *
     * <p>Delegates to {@link GrpcStatus#isRetryable(int)} rather than keeping a second copy of the list:
     * OTel's set and the OTLP specification's agree, and two copies of the same seven numbers is one
     * copy too many.
     *
     * @param statusCode the {@code grpc-status} the collector answered with
     * @return whether to try again
     */
    static boolean isRetryableGrpc(int statusCode) {
        return GrpcStatus.isRetryable(statusCode);
    }

    /**
     * @param e the transport failure that ended an attempt
     * @return whether it is one the policy considers transient
     */
    boolean isRetryable(IOException e) {
        return retryExceptionPredicate.test(e);
    }

    /**
     * @return the total number of attempts allowed, the first one included. OTel's builder refuses
     * anything outside two to five, so there is always at least one retry, but the arithmetic here does
     * not rely on that
     */
    int maxAttempts() {
        return maxAttempts;
    }

    /**
     * The ceiling the wait after a given attempt is drawn against, before jitter.
     *
     * @param attempt the attempt that has just failed, counting from 1
     * @return the bound in nanoseconds
     * @throws IllegalArgumentException if that attempt has no retry after it, which is a caller that
     *                                  failed to check {@link #maxAttempts()} first
     */
    long backoffBoundNanos(int attempt) {
        if (attempt < 1 || attempt > backoffBoundsNanos.length) {
            throw new IllegalArgumentException("No retry follows attempt " + attempt
                    + " of a policy allowing " + maxAttempts + "; check maxAttempts() before asking");
        }
        return backoffBoundsNanos[attempt - 1];
    }

    /**
     * The wait to take before the attempt after this one: uniform in {@code [0, bound)}.
     *
     * @param attempt the attempt that has just failed, counting from 1
     * @return the delay in nanoseconds
     * @throws IllegalArgumentException if that attempt has no retry after it
     */
    long backoffNanos(int attempt) {
        return (long) (backoffBoundNanos(attempt) * jitter.getAsDouble());
    }

    /**
     * The longest a fully retried publish can spend waiting, which is every bound taken in full.
     *
     * <p>Excludes the attempts themselves, so the real ceiling is this plus {@link #maxAttempts()} times
     * the request timeout. Worth comparing against the exporter timeout the SDK holds a batch to: with
     * the OTel defaults this is 8.125 s, five attempts at a 1 s timeout add another 5, and the
     * {@code BatchSpanProcessor} in {@code OtelTracing} gives up at 10.
     *
     * @return the summed bounds
     */
    Duration worstCaseTotal() {
        long total = 0;
        for (long bound : backoffBoundsNanos) {
            total += bound;
        }
        return Duration.ofNanos(total);
    }
}
