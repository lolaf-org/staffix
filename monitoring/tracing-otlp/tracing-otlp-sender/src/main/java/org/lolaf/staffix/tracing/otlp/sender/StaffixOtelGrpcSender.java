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
import io.opentelemetry.sdk.common.export.GrpcResponse;
import io.opentelemetry.sdk.common.export.GrpcSender;
import io.opentelemetry.sdk.common.export.GrpcSenderConfig;
import io.opentelemetry.sdk.common.export.MessageWriter;
import org.lolaf.staffix.api.grpc.GrpcReply;
import org.lolaf.staffix.api.grpc.GrpcStatus;
import org.lolaf.staffix.api.http.Header;

import java.io.IOException;
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
 * {@link org.lolaf.staffix.api.grpc.GrpcSender} implementations.
 *
 * <p>The gRPC counterpart of {@link StaffixHttpSender}, and it resolves the same three disagreements the
 * same way - asynchronous over synchronous, retry owned here, and a writer marshalled into a pooled
 * buffer held until the last attempt finishes. See that class for why each of those is what it is; only
 * the differences are worth restating here:
 *
 * <ul>
 *     <li><strong>Retry keys off the gRPC status, not an HTTP one.</strong> A gRPC call that fails still
 *     answers HTTP 200, so the status that matters is the one in the trailer.</li>
 *     <li><strong>Compression is the sender's, not the buffer's.</strong> gRPC compresses each message
 *     inside its frame and announces it in both the frame flag and {@code grpc-encoding}, so the payload
 *     is marshalled uncompressed here and the client module compresses it. Passing OTel's compressor to
 *     {@link PayloadBuffers} as well would compress it twice.</li>
 *     <li><strong>There is no proxy to map.</strong> {@link GrpcSenderConfig} carries no
 *     {@code ProxyOptions}, unlike its HTTP counterpart.</li>
 * </ul>
 */
public final class StaffixOtelGrpcSender implements GrpcSender {

    private final org.lolaf.staffix.api.grpc.GrpcSender delegate;

    private final PayloadBuffers buffers;

    private final RetrySchedule schedule;

    private final Supplier<Map<String, List<String>>> headersSupplier;

    private final ExecutorService executor;

    private final boolean ownsExecutor;

    private final int maxResponseBodySize;

    private volatile boolean closed;

    private volatile Header[] lastHeaders = new Header[0];

    private volatile StaffixGrpcResponse lastSuccess =
            new StaffixGrpcResponse(GrpcStatus.OK, "", StaffixGrpcResponse.NO_MESSAGE);

    /**
     * @param delegate the client to call through, already bound to the endpoint and method
     * @param config   what the exporter was configured with
     */
    public StaffixOtelGrpcSender(org.lolaf.staffix.api.grpc.GrpcSender delegate, GrpcSenderConfig config) {
        this(delegate, config, new PayloadBuffers(8192, 4), new RetrySchedule(config.getRetryPolicy()));
    }

    StaffixOtelGrpcSender(org.lolaf.staffix.api.grpc.GrpcSender delegate, GrpcSenderConfig config,
                          PayloadBuffers buffers, RetrySchedule schedule) {
        this.delegate = delegate;
        this.buffers = buffers;
        this.schedule = schedule;
        this.headersSupplier = config.getHeadersSupplier();
        ExecutorService configured = config.getExecutorService();
        this.ownsExecutor = configured == null;
        this.executor = ownsExecutor ? newExportExecutor() : configured;
        this.maxResponseBodySize = (int) Math.min(Integer.MAX_VALUE, config.getMaxResponseBodySize());
    }

    private static ExecutorService newExportExecutor() {
        AtomicInteger count = new AtomicInteger();
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "staffix-otlp-grpc-sender-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.size() == 1 ? values.get(0) : String.join(", ", values);
    }

    /**
     * @return the client this calls through, for a test that needs to know which one was chosen
     */
    org.lolaf.staffix.api.grpc.GrpcSender delegate() {
        return delegate;
    }

    @Override
    public void send(MessageWriter writer, Consumer<GrpcResponse> onResponse, Consumer<Throwable> onError) {
        PayloadBuffers.Lease lease;
        Header[] headers;
        try {
            // Marshalled uncompressed: gRPC compression happens inside the frame, in the client module.
            lease = buffers.marshal(writer, null);
            headers = requestHeaders();
        } catch (IOException | RuntimeException e) {
            onError.accept(e);
            return;
        }
        // One reply per export rather than a pooled one: it is a small object, an export happens every
        // few seconds, and tying its lifetime to the attempt that owns it is worth more than the saving.
        submit(lease, headers, new GrpcReply(), 1, onResponse, onError);
    }

    /**
     * Closes the delegate and stops any retry still waiting to run. An executor the exporter supplied is
     * left running; one this sender made for itself is shut down with it.
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

    private void submit(PayloadBuffers.Lease lease, Header[] headers, GrpcReply reply, int attempt,
                        Consumer<GrpcResponse> onResponse, Consumer<Throwable> onError) {
        if (closed) {
            lease.close();
            onError.accept(new IOException("The OTLP gRPC sender was closed before this export could be sent"));
            return;
        }
        try {
            executor.execute(() -> attempt(lease, headers, reply, attempt, onResponse, onError));
        } catch (RuntimeException e) {
            lease.close();
            onError.accept(e);
        }
    }

    private void attempt(PayloadBuffers.Lease lease, Header[] headers, GrpcReply reply, int attempt,
                         Consumer<GrpcResponse> onResponse, Consumer<Throwable> onError) {
        try {
            delegate.send(lease.buffer(), 0, lease.length(), headers, reply.reset());
            if (worthRetrying(RetrySchedule.isRetryableGrpc(reply.getStatusCode()), attempt)) {
                retry(lease, headers, reply, attempt, onResponse, onError);
                return;
            }
            lease.close();
            onResponse.accept(responseFor(reply));
        } catch (IOException e) {
            if (worthRetrying(schedule.isRetryable(e), attempt)) {
                retry(lease, headers, reply, attempt, onResponse, onError);
            } else {
                lease.close();
                onError.accept(e);
            }
        } catch (RuntimeException e) {
            lease.close();
            onError.accept(e);
        }
    }

    private boolean worthRetrying(boolean retryableOutcome, int attempt) {
        return retryableOutcome && attempt < schedule.maxAttempts() && !closed;
    }

    private void retry(PayloadBuffers.Lease lease, Header[] headers, GrpcReply reply, int attempt,
                       Consumer<GrpcResponse> onResponse, Consumer<Throwable> onError) {
        CompletableFuture
                .delayedExecutor(schedule.backoffNanos(attempt), TimeUnit.NANOSECONDS)
                .execute(() -> submit(lease, headers, reply, attempt + 1, onResponse, onError));
    }

    /**
     * Reuses the last response while a successful status keeps repeating, so a healthy exporter
     * allocates nothing to describe a success it has already described.
     */
    private StaffixGrpcResponse responseFor(GrpcReply reply) {
        if (reply.isOk() && reply.getMessageLength() == 0) {
            StaffixGrpcResponse cached = lastSuccess;
            if (cached.getStatusCode().getValue() == GrpcStatus.OK) {
                return cached;
            }
        }
        byte[] message = reply.getMessageLength() == 0
                ? StaffixGrpcResponse.NO_MESSAGE
                : Arrays.copyOf(reply.getMessage(), Math.min(reply.getMessageLength(), maxResponseBodySize));
        StaffixGrpcResponse response =
                new StaffixGrpcResponse(reply.getStatusCode(), reply.getStatusMessage(), message);
        if (reply.isOk() && message.length == 0) {
            lastSuccess = response;
        }
        return response;
    }

    /**
     * Turns OTel's per-request header map into the array the delegate takes, reusing the last array
     * while the headers repeat.
     */
    private Header[] requestHeaders() {
        Map<String, List<String>> headers = headersSupplier == null ? null : headersSupplier.get();
        int size = headers == null ? 0 : headers.size();
        Header[] cached = lastHeaders;
        if (matches(cached, headers, size)) {
            return cached;
        }
        Header[] built = new Header[size];
        int i = 0;
        if (headers != null) {
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                built[i++] = new Header(header.getKey(), join(header.getValue()));
            }
        }
        lastHeaders = built;
        return built;
    }

    private boolean matches(Header[] cached, Map<String, List<String>> headers, int size) {
        if (cached.length != size) {
            return false;
        }
        for (int i = 0; i < cached.length; i++) {
            if (!cached[i].getValue().equals(join(headers.get(cached[i].getName())))) {
                return false;
            }
        }
        return true;
    }
}
