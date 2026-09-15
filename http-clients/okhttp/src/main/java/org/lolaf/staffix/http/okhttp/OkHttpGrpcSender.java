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
package org.lolaf.staffix.http.okhttp;

import okhttp3.*;
import okio.BufferedSource;
import org.lolaf.staffix.api.grpc.GrpcFraming;
import org.lolaf.staffix.api.grpc.GrpcReply;
import org.lolaf.staffix.api.grpc.GrpcSender;
import org.lolaf.staffix.api.grpc.GrpcSenderSettings;
import org.lolaf.staffix.api.http.Header;

import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.zip.GZIPOutputStream;

/**
 * {@link GrpcSender} on OkHttp.
 *
 * <p>One of only two clients in this library that can serve gRPC at all. gRPC reports its outcome in
 * HTTP/2 trailers - a failed call still answers HTTP 200 - and OkHttp exposes them through
 * {@link Response#trailers()}. The JDK's client does not expose them at any price, which is why there is
 * no {@code JdkGrpcSender} and never will be.
 *
 * <p>Three things it does that a stock client would not:
 *
 * <ul>
 *     <li><strong>HTTP/2 with prior knowledge on cleartext.</strong> gRPC has no {@code h2c} upgrade
 *     handshake, so a plaintext endpoint must be spoken to in HTTP/2 from the first byte. Over TLS the
 *     usual ALPN offer of {@code [h2, http/1.1]} is made instead.</li>
 *     <li><strong>{@code TCP_NODELAY}.</strong> Left to itself OkHttp writes request head and body as
 *     separate segments with Nagle enabled, and every call stalls on the peer's 40 ms delayed-ACK timer.
 *     {@link NoDelaySocketFactory} is the same fix this module's HTTP sender applies, for the same
 *     measured reason.</li>
 *     <li><strong>It reads the status from the headers when there are no trailers.</strong> A call that
 *     fails before producing a message answers "Trailers-Only", with {@code grpc-status} among the
 *     headers. A sender that looked only in the trailers would call that a peer with no status.</li>
 * </ul>
 */
public class OkHttpGrpcSender implements GrpcSender {

    private static final MediaType CONTENT_TYPE = MediaType.get("application/grpc");

    private static final String STATUS = "grpc-status";

    private static final String MESSAGE = "grpc-message";

    private final OkHttpClient client;

    private final HttpUrl endpoint;

    private final Headers boundHeaders;

    private final boolean compress;

    /**
     * A sender on the given settings.
     *
     * @param settings which method to call, and how
     */
    public OkHttpGrpcSender(GrpcSenderSettings settings) {
        this(settings, null);
    }

    /**
     * A sender on the given settings, with the trust manager OkHttp wants alongside an
     * {@code SSLContext}.
     *
     * @param settings     which method to call, and how
     * @param trustManager the trust manager backing {@link GrpcSenderSettings#getSslContext()}, or
     *                     {@code null} for the platform's default
     */
    public OkHttpGrpcSender(GrpcSenderSettings settings, X509TrustManager trustManager) {
        this.endpoint = HttpUrl.get(settings.getEndpointUrl()).newBuilder()
                .encodedPath(settings.getFullMethodName())
                .build();
        this.compress = settings.getCompression() == GrpcSenderSettings.GrpcCompression.GZIP;

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(settings.getConnectTimeout())
                .callTimeout(settings.getRequestTimeout())
                .socketFactory(new NoDelaySocketFactory());
        if (settings.getSslContext() != null) {
            builder.sslSocketFactory(settings.getSslContext().getSocketFactory(),
                    trustManager != null ? trustManager : OkHttpSender.platformTrustManager());
        }
        if (!this.endpoint.isHttps()) {
            // Cleartext gRPC is HTTP/2 from the first byte; there is no upgrade dance to fall back on,
            // and offering http/1.1 here would let the client negotiate something gRPC cannot use.
            // Keyed off the endpoint's scheme rather than off the presence of an SSLContext, which on a
            // cleartext endpoint is unused - reading TLS from it would leave this on ALPN semantics for
            // a connection where ALPN never runs, and the call would go out as HTTP/1.1.
            builder.protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE));
        }
        if (settings.getProxySelector() != null) {
            builder.proxySelector(settings.getProxySelector());
        }
        if (settings.getAuthenticator() != null) {
            builder.proxyAuthenticator(JdkAuthenticatorBridge.forProxy(settings.getAuthenticator()));
            builder.authenticator(JdkAuthenticatorBridge.forServer(settings.getAuthenticator()));
        }
        this.client = builder.build();

        Headers.Builder headers = new Headers.Builder()
                .set("te", "trailers")
                .set("grpc-encoding", settings.getCompression().getEncoding())
                .set("grpc-accept-encoding", "identity,gzip")
                .set("grpc-timeout", grpcTimeout(settings));
        settings.getHeaders().forEach(headers::set);
        this.boundHeaders = headers.build();
    }

    /**
     * gRPC spells a deadline as a number and a unit.
     */
    private static String grpcTimeout(GrpcSenderSettings settings) {
        return Math.max(1, settings.getRequestTimeout().toMillis()) + "m";
    }

    private static boolean readFully(BufferedSource source, byte[] into, int length)
            throws IOException {
        int read = 0;
        while (read < length) {
            int count = source.read(into, read, length - read);
            if (count == -1) {
                return false;
            }
            read += count;
        }
        return true;
    }

    @Override
    public GrpcReply send(byte[] data, int offset, int length, Header[] extraHeaders, GrpcReply into)
            throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(endpoint)
                .headers(boundHeaders)
                .post(body(data, offset, length));
        if (extraHeaders != null) {
            for (int i = 0; i < extraHeaders.length; i++) {
                builder.header(extraHeaders[i].getName(), extraHeaders[i].getValue());
            }
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            readMessage(response, into);
            // The trailers first, then the headers: a Trailers-Only response carries the status among
            // the headers and sends no trailers at all.
            String status = response.trailers().get(STATUS);
            String message = response.trailers().get(MESSAGE);
            if (status == null) {
                status = response.header(STATUS);
                message = response.header(MESSAGE);
            }
            if (status == null) {
                throw new IOException("The gRPC endpoint " + endpoint + " answered HTTP "
                        + response.code() + " with no grpc-status, in neither trailers nor headers");
            }
            into.status(parseStatus(status), message);
            return into;
        }
    }

    @Override
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    private int parseStatus(String status) throws IOException {
        try {
            return Integer.parseInt(status.trim());
        } catch (NumberFormatException e) {
            throw new IOException("The gRPC endpoint " + endpoint + " answered with a grpc-status of '"
                    + status + "', which is not a number", e);
        }
    }

    /**
     * Uncompressed, the payload is not copied; compressed, it must be, gRPC framing needing its length.
     */
    private RequestBody body(byte[] data, int offset, int length) throws IOException {
        if (!compress) {
            return new SegmentedRequestBody(CONTENT_TYPE, header(length), data, offset, length);
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(length);
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(data, offset, length);
        }
        byte[] message = compressed.toByteArray();
        return new SegmentedRequestBody(CONTENT_TYPE, header(message.length), message, 0, message.length);
    }

    private byte[] header(int messageLength) {
        byte[] header = new byte[GrpcFraming.HEADER_LENGTH];
        GrpcFraming.writeHeader(header, 0, compress, messageLength);
        return header;
    }

    /**
     * Reads the frame into the caller's reusable buffer rather than draining the body into a new one.
     */
    private void readMessage(Response response, GrpcReply into) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            into.messageLength(0);
            return;
        }
        BufferedSource source = body.source();
        byte[] header = new byte[GrpcFraming.HEADER_LENGTH];
        if (!readFully(source, header, header.length)) {
            // No frame at all, which is what a Trailers-Only response carries; the status says why.
            into.messageLength(0);
            return;
        }
        int length = GrpcFraming.messageLength(header, 0);
        if (!readFully(source, into.messageBuffer(length), length)) {
            throw new IOException("The gRPC endpoint " + endpoint + " announced a " + length
                    + " byte message but sent fewer");
        }
        into.messageLength(length);
    }
}
