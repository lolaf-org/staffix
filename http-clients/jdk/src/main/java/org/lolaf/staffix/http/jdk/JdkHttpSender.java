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
package org.lolaf.staffix.http.jdk;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.http.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * {@link HttpSender} backed by the JDK's own {@link HttpClient}, present since Java 11.
 *
 * <p>This is the sender that needs nothing on the classpath, which is why it is the default everywhere
 * a sender is configured. It is not the fastest, and on an HTTPS endpoint it is the slowest and the
 * most wasteful of the implementations here.
 *
 * <p><strong>Good over plaintext.</strong> Measured on a 16 KB payload over loopback it allocates about
 * 17 KB per publish, against 59 KB for micrometer's {@code HttpUrlConnectionSender}: the JDK client's
 * request and response machinery is leaner than the legacy {@code sun.net.www} stack behind
 * {@link java.net.HttpURLConnection}, and this sender discards the response body unread on a successful
 * status, buffering it into a {@code String} only on the failure branch that actually reports it.
 * {@link ByteArrayRegionPublisher} takes off a further 16.4 KB that
 * {@link HttpRequest.BodyPublishers#ofByteArray(byte[], int, int)} would spend duplicating the payload
 * on every subscribe - on both transports, since that copy happens before any encryption.
 *
 * <p><strong>Bad over TLS.</strong> About 56 KB per publish, the worst of the three senders in this
 * library and worse than its own plaintext figure by more than the encryption itself costs. Roughly
 * three fifths of it is {@code SSLFlowDelegate}, which allocates fresh wrap and unwrap buffers per
 * operation where a blocking {@code SSLSocket} reuses its own. That is inside the JDK client and no
 * caller can tune it, so for an HTTPS collector - which is what a production one is - prefer the OkHttp
 * sender, which allocates about 13 KB there and is nearly twice as fast.
 *
 * <p>It is also the slowest of the three on both transports - 86 µs plaintext and 83 µs over TLS,
 * against about 33 µs and 46 µs for OkHttp - and the only one whose timings vary by tens of
 * microseconds between runs.
 *
 * <p>It is <em>not</em> a fix for connection churn: it keeps the connection alive between publishes,
 * but so does every alternative. Note that its pool drops an idle connection after
 * {@code jdk.httpclient.keepalive.timeout} seconds, 30 by default, so a publishing step longer than
 * that reconnects regardless of the client chosen.
 *
 * <p>Every setting in {@link HttpSenderSettings} is honoured. HTTP/1.1 is negotiated by default rather
 * than the JDK's own HTTP/2 default; see
 * {@link #JdkHttpSender(HttpSenderSettings, HttpVersion, Executor)} for why.
 *
 * <p>Instances are safe for concurrent use.
 */
@Slf4j
public class JdkHttpSender implements HttpSender {

    /**
     * Header names already reported as restricted, so a rejected header is logged once rather than on
     * every publish.
     */
    private static final Set<String> REPORTED_RESTRICTED_HEADERS = ConcurrentHashMap.newKeySet();

    /**
     * Response bodies are read only when the exchange failed, so a successful publish allocates none.
     */
    private static final HttpResponse.BodyHandler<String> BODY_HANDLER =
            responseInfo -> isSuccessful(responseInfo.statusCode())
                    ? HttpResponse.BodySubscribers.replacing(null)
                    : HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);

    private final HttpClient client;

    /**
     * The endpoint, its timeout and every header that never changes, resolved and bound once. Copied
     * per publish rather than mutated, so that concurrent senders cannot tread on each other.
     */
    private final HttpRequest.Builder requestTemplate;

    private final URI endpoint;

    /**
     * A sender on the given settings, negotiating HTTP/1.1 and letting the JDK build its own executor.
     *
     * @param settings where to publish, and how
     */
    public JdkHttpSender(HttpSenderSettings settings) {
        this(settings, null, null);
    }

    /**
     * A sender on the given settings, with the two knobs this client has that the shared settings do
     * not express.
     *
     * @param settings    where to publish, and how
     * @param httpVersion HTTP version to negotiate, or {@code null} for {@link HttpVersion#HTTP_1_1}
     *                    rather than the JDK's own {@code HTTP_2}. A declined {@code h2c} offer costs
     *                    2.6 KB per publish on cleartext and 0.5 KB over TLS; against a receiver that
     *                    does speak HTTP/2 the version costs 1.17x-1.51x the allocation. Pinning also
     *                    withdraws the ALPN offer
     * @param executor    executor for the client's asynchronous tasks. Defaults to the one the JDK
     *                    builds for itself, a cached pool of daemon threads
     */
    public JdkHttpSender(HttpSenderSettings settings, HttpVersion httpVersion, Executor executor) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(settings.getConnectTimeout())
                .version(httpVersion == HttpVersion.HTTP_2
                        ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1);
        if (settings.getProxySelector() != null) {
            builder.proxy(settings.getProxySelector());
        }
        if (settings.getAuthenticator() != null) {
            builder.authenticator(settings.getAuthenticator());
        }
        if (settings.getSslContext() != null) {
            builder.sslContext(settings.getSslContext());
        }
        if (executor != null) {
            builder.executor(executor);
        }
        this.client = builder.build();
        this.endpoint = URI.create(settings.getEndpointUrl());
        this.requestTemplate = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(settings.getRequestTimeout())
                .header("Content-Type", settings.getContentType());
        settings.getHeaders().forEach((name, value) -> setHeader(requestTemplate, name, value));
    }

    private static void setHeader(HttpRequest.Builder builder, String name, String value) {
        try {
            // setHeader, not header: the latter appends a second value where a per-request header is
            // meant to replace the bound one of the same name.
            builder.setHeader(name, value);
        } catch (IllegalArgumentException e) {
            // The JDK client refuses to let a caller set some headers itself, and which ones varies by
            // JDK version, so let it decide rather than keeping a list of them here.
            if (REPORTED_RESTRICTED_HEADERS.add(name.toLowerCase())) {
                log.warn("The JDK HTTP client rejected the header '{}' as one it sets itself; it will not be sent. "
                                + "Run with -Djdk.httpclient.allowRestrictedHeaders={} to send it anyway.",
                        name, name.toLowerCase());
            }
        }
    }

    private static boolean isSuccessful(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    @Override
    public int send(byte[] data, int offset, int length, Header[] extraHeaders) throws IOException {
        HttpRequest.Builder builder = requestTemplate.copy()
                .POST(new ByteArrayRegionPublisher(data, offset, length));
        if (extraHeaders != null) {
            // Indexed rather than enhanced-for on principle here: this runs on every publish, and the
            // array exists precisely so that nothing is allocated to walk it.
            for (Header extraHeader : extraHeaders) {
                setHeader(builder, extraHeader.getName(), extraHeader.getValue());
            }
        }
        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), BODY_HANDLER);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while publishing to " + endpoint);
        }
        if (!isSuccessful(response.statusCode())) {
            // No reason phrase: java.net.http.HttpResponse exposes the status code and the headers
            // and nothing of the status line beyond them, so this client has none to report.
            throw new HttpResponseException(response.statusCode(), null, response.body());
        }
        return response.statusCode();
    }

    /**
     * Releases the underlying client. Before Java 21 {@link HttpClient} has no way to be shut down and
     * its selector thread lives until the client is collected, so this does nothing there.
     */
    @Override
    public void close() {
        try {
            HttpClient.class.getMethod("close").invoke(client);
        } catch (NoSuchMethodException e) {
            // Java < 21: nothing to close, the client goes when it is collected.
        } catch (ReflectiveOperationException e) {
            log.warn("Failed to close the JDK HTTP client", e);
        }
    }
}
