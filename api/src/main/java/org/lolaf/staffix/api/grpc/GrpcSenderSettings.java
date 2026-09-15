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
package org.lolaf.staffix.api.grpc;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import javax.net.ssl.SSLContext;
import java.net.Authenticator;
import java.net.ProxySelector;
import java.time.Duration;
import java.util.Map;

/**
 * What a {@link GrpcSender} binds once, when it is built.
 *
 * <p>Deliberately the same shape as
 * {@link org.lolaf.staffix.api.http.HttpSenderSettings}, so the two read alike, with two differences that
 * gRPC forces:
 *
 * <ul>
 *     <li>a <strong>full method name</strong> instead of a path, because gRPC addresses a method rather
 *     than a resource - the endpoint is the authority and this is what follows it;</li>
 *     <li>no content type, because gRPC fixes it at {@code application/grpc}.</li>
 * </ul>
 *
 * @see GrpcSender
 */
@Getter
@Builder(toBuilder = true)
public class GrpcSenderSettings {

    /**
     * The server to call, as a URL: scheme, host and port. Any path on it is ignored - the path a gRPC
     * call uses is {@link #fullMethodName}.
     */
    /**
     * The collector's address.
     */
    private final String endpointUrl;

    /**
     * The method to call, as gRPC spells it on the wire: a leading slash, the fully qualified service
     * name, another slash, the method. For OTLP logs that is
     * {@code /opentelemetry.proto.collector.logs.v1.LogsService/Export}.
     *
     * <p>The leading slash is optional here and added if missing, because callers disagree about it:
     * OpenTelemetry's {@code GrpcSenderConfig.getFullMethodName()} omits it, while gRPC's own wire
     * format requires it. Normalising once here spares every implementation from getting it right
     * separately - and from the failure being a rejected path rather than a misrouted call.
     */
    /**
     * The gRPC method to call, as {@code package.Service/Method}.
     */
    private final String fullMethodName;

    /**
     * Headers sent on every call, bound once. Per-call headers go to
     * {@link GrpcSender#send(byte[], int, int, org.lolaf.staffix.api.http.Header[], GrpcReply)} instead.
     */
    @Singular("header")
    private final Map<String, String> headers;

    /**
     * Whether to compress each message, which sets {@code grpc-encoding} and the frame's compressed
     * flag.
     */
    @Builder.Default
    private final GrpcCompression compression = GrpcCompression.NONE;

    /**
     * How long to wait for the connection itself.
     */
    @Builder.Default
    private final Duration connectTimeout = Duration.ofSeconds(1);

    /**
     * Deadline for one call, sent to the server as {@code grpc-timeout} as well as applied locally.
     */
    /**
     * How long to wait for a response once connected.
     */
    @Builder.Default
    private final Duration requestTimeout = Duration.ofSeconds(10);

    /**
     * TLS for the collector connection; null uses the JVM's default, which is what a collector with a
     * publicly-trusted certificate needs.
     */
    @Builder.Default
    private final SSLContext sslContext = null;

    /**
     * Proxy to reach the collector through; null connects directly.
     */
    @Builder.Default
    private final ProxySelector proxySelector = null;

    /**
     * Credentials for the collector, if it requires them.
     */
    @Builder.Default
    private final Authenticator authenticator = null;

    /**
     * @return the method path with its leading slash, whether or not the caller supplied one
     */
    public String getFullMethodName() {
        if (fullMethodName == null || fullMethodName.isEmpty() || fullMethodName.charAt(0) == '/') {
            return fullMethodName;
        }
        return "/" + fullMethodName;
    }

    /**
     * How a message is compressed, which is the value of {@code grpc-encoding} on the wire.
     */
    public enum GrpcCompression {

        /**
         * No compression; {@code grpc-encoding: identity}.
         */
        NONE("identity"),

        /**
         * gzip, which every OTLP collector accepts.
         */
        GZIP("gzip");

        /**
         * The content encoding requested of the collector.
         */
        private final String encoding;

        GrpcCompression(String encoding) {
            this.encoding = encoding;
        }

        /**
         * @return the name this compression carries in {@code grpc-encoding}
         */
        public String getEncoding() {
            return encoding;
        }
    }
}
