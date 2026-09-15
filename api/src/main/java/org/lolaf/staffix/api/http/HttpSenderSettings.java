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
package org.lolaf.staffix.api.http;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import javax.net.ssl.SSLContext;
import java.net.Authenticator;
import java.net.ProxySelector;
import java.time.Duration;
import java.util.Map;

/**
 * Everything an {@link HttpSender} binds when it is built: where it publishes, and how.
 *
 * <p>These are the settings that hold for the sender's whole lifetime, which is what lets an
 * implementation resolve the endpoint once and keep a connection open to it. Anything that varies per
 * request goes through the {@code extraHeaders} argument of
 * {@link HttpSender#send(byte[], int, int, Header[])} instead.
 *
 * <p>Every implementation honours what its underlying client can express and <strong>documents in its
 * class javadoc what it ignores</strong>; a setting silently dropped is worse than one refused, so an
 * implementation that cannot honour a setting says so rather than pretending.
 */
@Getter
@Builder(toBuilder = true)
public class HttpSenderSettings {

    private static final String DEFAULT_CONTENT_TYPE = "application/x-protobuf";

    /**
     * The address to publish to, in full, for example
     * {@code https://otlp.example.com/v1/metrics}. Required.
     */
    private final String endpointUrl;

    /**
     * Value of the {@code Content-Type} header, {@code application/x-protobuf} by default.
     *
     * <p>Sent exactly as given. An implementation must not decorate it - appending a charset to a
     * binary content type is rejected by some collectors, and working around that in the caller is the
     * kind of defect this abstraction exists to stop.
     */
    @Builder.Default
    private final String contentType = DEFAULT_CONTENT_TYPE;

    /**
     * Headers sent on every request, such as an {@code Authorization} for the collector. Empty by
     * default.
     *
     * <p>A map here, where {@link HttpSender#send(byte[], int, int, Header[])} takes an array: these are
     * read once, when the sender binds them, so nothing is gained by making a caller build the array.
     *
     * <p>Note that some clients refuse to let a caller set headers they manage themselves, and which
     * ones varies by version; an implementation reports such a header rather than dropping it silently.
     */
    @Singular("header")
    private final Map<String, String> headers;

    /**
     * Timeout for establishing a connection to the endpoint, one second by default.
     */
    @Builder.Default
    private final Duration connectTimeout = Duration.ofSeconds(1);

    /**
     * Timeout for a whole exchange, ten seconds by default.
     */
    @Builder.Default
    private final Duration requestTimeout = Duration.ofSeconds(10);

    /**
     * SSL context for an {@code https} endpoint. Null by default, which takes the JVM's own default
     * context; ignored entirely for a plaintext endpoint.
     */
    @Builder.Default
    private final SSLContext sslContext = null;

    /**
     * Proxy to reach the endpoint through. Null by default, meaning a direct connection.
     */
    @Builder.Default
    private final ProxySelector proxySelector = null;

    /**
     * Authenticator for proxy and server challenges. Null by default.
     *
     * <p>Static credentials belong in an {@link #getHeaders() Authorization header} instead: an
     * authenticator only answers a challenge, so it costs a rejected round trip per exchange that a
     * header sent up front does not.
     */
    @Builder.Default
    private final Authenticator authenticator = null;
}
