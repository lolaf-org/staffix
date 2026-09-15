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

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.*;
import org.lolaf.staffix.api.http.Header;
import org.lolaf.staffix.api.http.HttpResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Publishes an OTel export through one of this library's
 * {@link org.lolaf.staffix.api.http.HttpSender} implementations.
 *
 * <p>The two interfaces disagree in three ways, and each disagreement is resolved here rather than
 * pushed onto the client implementations:
 *
 * <ul>
 *     <li><strong>OTel's is asynchronous, ours is not.</strong> Every attempt runs on the
 *     {@code ExecutorService} the config hands over - the same one {@code OtelTracing} creates as a
 *     single thread - and the outcome arrives through the callbacks. An exporter that supplied none
 *     gets a single daemon thread of this sender's own, shut down with it.</li>
 *     <li><strong>OTel expects the sender to retry, ours does not retry.</strong> {@link RetrySchedule}
 *     says how often and how long to wait; the wait is scheduled, never slept, because sleeping on a
 *     single-threaded executor stalls every export queued behind it.</li>
 *     <li><strong>OTel hands over a writer, ours takes a byte slice.</strong> {@link PayloadBuffers}
 *     marshals on the calling thread into a buffer held until the last attempt finishes. See its
 *     javadoc for why that is a correctness requirement and not a convenience.</li>
 * </ul>
 *
 * <p><strong>A non-2xx is reported through {@code onResponse}, not {@code onError}.</strong> OTel reads
 * the status itself and accounts for it; routing a 400 to the error callback would hide it from the
 * exporter's own bookkeeping. {@code onError} is for a failure that never produced a status at all.
 *
 * <p>What it allocates per publish, once warm: nothing. The response for a repeated status is reused,
 * the header array is rebuilt only when the headers actually change, and the payload buffer comes from
 * a pool.
 */
final class StaffixHttpSender implements HttpSender {

    private final org.lolaf.staffix.api.http.HttpSender delegate;

    private final PayloadBuffers buffers;

    private final RetrySchedule schedule;

    private final Compressor compressor;

    private final Supplier<Map<String, List<String>>> headersSupplier;

    private final ExecutorService executor;

    /**
     * Whether the executor is ours to shut down. It is not, when the exporter supplied one.
     */
    private final boolean ownsExecutor;

    private final int maxResponseBodySize;

    /**
     * Present when a compressor is configured, and then always the first entry of the header array.
     */
    private final Header contentEncoding;

    private volatile boolean closed;

    private volatile Header[] lastHeaders = new Header[0];

    private volatile StaffixHttpResponse lastSuccess =
            new StaffixHttpResponse(200, "", StaffixHttpResponse.NO_BODY);

    StaffixHttpSender(org.lolaf.staffix.api.http.HttpSender delegate, HttpSenderConfig config) {
        this(delegate, config, new PayloadBuffers(8192, 4), new RetrySchedule(config.getRetryPolicy()));
    }

    StaffixHttpSender(org.lolaf.staffix.api.http.HttpSender delegate, HttpSenderConfig config,
                      PayloadBuffers buffers, RetrySchedule schedule) {
        this.delegate = delegate;
        this.buffers = buffers;
        this.schedule = schedule;
        this.compressor = config.getCompressor();
        this.headersSupplier = config.getHeadersSupplier();
        ExecutorService configured = config.getExecutorService();
        // An exporter built without one leaves this null - OtelTracing always sets it, a bare
        // OtlpHttpSpanExporter.builder() does not. Publishing on the calling thread instead is not an
        // option: with a SimpleSpanProcessor that thread is the application's, ending a span.
        this.ownsExecutor = configured == null;
        this.executor = ownsExecutor ? newExportExecutor() : configured;
        this.maxResponseBodySize = (int) Math.min(Integer.MAX_VALUE, config.getMaxResponseBodySize());
        this.contentEncoding = compressor == null
                ? null : new Header("Content-Encoding", compressor.getEncoding());
    }

    private static ExecutorService newExportExecutor() {
        AtomicInteger count = new AtomicInteger();
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "staffix-otlp-sender-" + count.incrementAndGet());
            // Daemon, so a forgotten shutdown cannot keep a JVM alive over telemetry.
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * OTel's headers are multi-valued and ours are not, because our senders replace a bound header of
     * the same name rather than appending to it. Several values become one comma-separated value, as
     * RFC 7230 allows; OTLP headers are single-valued in practice, so this is a corner rather than a
     * path.
     */
    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        return String.join(", ", values);
    }

    /**
     * @return the client this publishes through, for a test that needs to know which one was chosen
     */
    org.lolaf.staffix.api.http.HttpSender delegate() {
        return delegate;
    }

    @Override
    public void send(MessageWriter writer, Consumer<HttpResponse> onResponse, Consumer<Throwable> onError) {
        PayloadBuffers.Lease lease;
        Header[] headers;
        try {
            // Both on the calling thread: the writer's bytes are only ours until it returns, and reading
            // the header supplier here keeps a publish from racing a configuration change mid-flight.
            lease = buffers.marshal(writer, compressor);
            headers = requestHeaders();
        } catch (IOException | RuntimeException e) {
            onError.accept(e);
            return;
        }
        submit(lease, headers, 1, onResponse, onError);
    }

    /**
     * Closes the delegate and stops any retry still waiting to run.
     *
     * <p>The executor is shut down only when it is ours, which is when the exporter supplied none. One
     * handed over by the exporter is left running: it may be shared with other exporters, and closing
     * somebody else's executor is not this sender's business.
     */
    @Override
    public CompletableResultCode shutdown() {
        if (closed) {
            return CompletableResultCode.ofSuccess();
        }
        closed = true;
        try {
            delegate.close();
            return CompletableResultCode.ofSuccess();
        } catch (RuntimeException e) {
            return CompletableResultCode.ofExceptionalFailure(e);
        } finally {
            if (ownsExecutor) {
                executor.shutdown();
            }
        }
    }

    /**
     * Hands the attempt to the executor, giving up if this sender has been shut down or the executor
     * will not take it. Either way the lease is released: a leaked buffer is a slow leak of the pool.
     */
    private void submit(PayloadBuffers.Lease lease, Header[] headers, int attempt,
                        Consumer<HttpResponse> onResponse, Consumer<Throwable> onError) {
        if (closed) {
            lease.close();
            onError.accept(new IOException("The OTLP sender was closed before this export could be sent"));
            return;
        }
        try {
            executor.execute(() -> attempt(lease, headers, attempt, onResponse, onError));
        } catch (RuntimeException e) {
            lease.close();
            onError.accept(e);
        }
    }

    private void attempt(PayloadBuffers.Lease lease, Header[] headers, int attempt,
                         Consumer<HttpResponse> onResponse, Consumer<Throwable> onError) {
        try {
            int status = delegate.send(lease.buffer(), 0, lease.length(), headers);
            lease.close();
            onResponse.accept(successResponse(status));
        } catch (HttpResponseException e) {
            if (worthRetrying(RetrySchedule.isRetryableHttp(e.getStatusCode()), attempt)) {
                retry(lease, headers, attempt, onResponse, onError);
            } else {
                lease.close();
                onResponse.accept(new StaffixHttpResponse(
                        e.getStatusCode(), e.getReasonPhrase(), responseBody(e.getBody())));
            }
        } catch (IOException e) {
            if (worthRetrying(schedule.isRetryable(e), attempt)) {
                retry(lease, headers, attempt, onResponse, onError);
            } else {
                lease.close();
                onError.accept(e);
            }
        } catch (RuntimeException e) {
            // Nothing here should throw one, but a client that does must not also leak the buffer.
            lease.close();
            onError.accept(e);
        }
    }

    private boolean worthRetrying(boolean retryableOutcome, int attempt) {
        return retryableOutcome && attempt < schedule.maxAttempts() && !closed;
    }

    /**
     * Schedules the next attempt after the policy's backoff.
     *
     * <p>The delay is taken on {@code CompletableFuture}'s shared timer rather than on the export
     * executor, which is single-threaded in the configuration this library ships: a {@code Thread.sleep}
     * there would hold up every export queued behind this one for the whole backoff.
     */
    private void retry(PayloadBuffers.Lease lease, Header[] headers, int attempt,
                       Consumer<HttpResponse> onResponse, Consumer<Throwable> onError) {
        CompletableFuture
                .delayedExecutor(schedule.backoffNanos(attempt), TimeUnit.NANOSECONDS)
                .execute(() -> submit(lease, headers, attempt + 1, onResponse, onError));
    }

    /**
     * Reuses the last response while the status keeps repeating, so a healthy exporter allocates nothing
     * to describe a success.
     */
    private StaffixHttpResponse successResponse(int status) {
        StaffixHttpResponse cached = lastSuccess;
        if (cached.getStatusCode() == status) {
            return cached;
        }
        StaffixHttpResponse response = new StaffixHttpResponse(status, "", StaffixHttpResponse.NO_BODY);
        lastSuccess = response;
        return response;
    }

    private byte[] responseBody(String body) {
        if (body == null || body.isEmpty()) {
            return StaffixHttpResponse.NO_BODY;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return bytes.length <= maxResponseBodySize ? bytes : Arrays.copyOf(bytes, maxResponseBodySize);
    }

    /**
     * Turns OTel's per-request header map into the array the delegate takes, reusing the last array
     * while the headers repeat - which they do, for the life of an exporter.
     */
    private Header[] requestHeaders() {
        Map<String, List<String>> headers = headersSupplier == null ? null : headersSupplier.get();
        int size = headers == null ? 0 : headers.size();
        Header[] cached = lastHeaders;
        if (matches(cached, headers, size)) {
            return cached;
        }

        Header[] built = new Header[size + (contentEncoding == null ? 0 : 1)];
        int i = 0;
        if (contentEncoding != null) {
            built[i++] = contentEncoding;
        }
        if (headers != null) {
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                built[i++] = new Header(header.getKey(), join(header.getValue()));
            }
        }
        lastHeaders = built;
        return built;
    }

    /**
     * Compares by lookup rather than by iterating the map, so a hit allocates no iterator. The leading
     * {@code Content-Encoding} is ours and cannot change, so it is skipped rather than looked up.
     */
    private boolean matches(Header[] cached, Map<String, List<String>> headers, int size) {
        int offset = contentEncoding == null ? 0 : 1;
        if (cached.length != size + offset) {
            return false;
        }
        for (int i = offset; i < cached.length; i++) {
            if (!cached[i].getValue().equals(join(headers.get(cached[i].getName())))) {
                return false;
            }
        }
        return true;
    }
}
