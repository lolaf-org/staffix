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
package org.lolaf.staffix.tracing.otlp.sender.grpc;

import io.opentelemetry.sdk.common.export.Compressor;
import io.opentelemetry.sdk.common.export.GrpcSenderConfig;
import io.opentelemetry.sdk.common.export.RetryPolicy;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * A settable {@link GrpcSenderConfig}, because the SDK's own implementation of it lives in
 * {@code opentelemetry-exporter-otlp} - a module this one deliberately does not depend on.
 */
class TestGrpcSenderConfig implements GrpcSenderConfig {

    private final ExecutorService executorService;

    URI endpoint = URI.create("http://127.0.0.1:4317");

    String fullMethodName = "/opentelemetry.proto.collector.trace.v1.TraceService/Export";

    Compressor compressor;

    Duration timeout = Duration.ofSeconds(1);

    Duration connectTimeout = Duration.ofSeconds(1);

    Supplier<Map<String, List<String>>> headersSupplier = Collections::emptyMap;

    RetryPolicy retryPolicy = RetryPolicy.builder()
            .setMaxAttempts(3)
            .setInitialBackoff(Duration.ofMillis(5))
            .setMaxBackoff(Duration.ofMillis(20))
            .setBackoffMultiplier(2.0)
            .build();

    SSLContext sslContext;

    long maxResponseBodySize = 4 * 1024 * 1024;

    TestGrpcSenderConfig(ExecutorService executorService) {
        this.executorService = executorService;
    }

    static Map<String, List<String>> headers(String name, String value) {
        return Collections.singletonMap(name, Collections.singletonList(value));
    }

    @Override
    public URI getEndpoint() {
        return endpoint;
    }

    @Override
    public String getFullMethodName() {
        return fullMethodName;
    }

    @Override
    public Compressor getCompressor() {
        return compressor;
    }

    @Override
    public Duration getTimeout() {
        return timeout;
    }

    @Override
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    @Override
    public Supplier<Map<String, List<String>>> getHeadersSupplier() {
        return headersSupplier;
    }

    @Override
    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    @Override
    public SSLContext getSslContext() {
        return sslContext;
    }

    @Override
    public X509TrustManager getTrustManager() {
        return null;
    }

    @Override
    public ExecutorService getExecutorService() {
        return executorService;
    }

    @Override
    public long getMaxResponseBodySize() {
        return maxResponseBodySize;
    }
}
