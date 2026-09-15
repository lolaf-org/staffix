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
package org.lolaf.staffix.http.jetty;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.client.*;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.lolaf.staffix.api.grpc.GrpcFraming;
import org.lolaf.staffix.api.grpc.GrpcReply;
import org.lolaf.staffix.api.grpc.GrpcSender;
import org.lolaf.staffix.api.grpc.GrpcSenderSettings;
import org.lolaf.staffix.api.http.Header;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;

/**
 * {@link GrpcSender} on Jetty's HTTP client.
 *
 * <p>The second of the two clients in this library that can serve gRPC. gRPC reports its outcome in
 * HTTP/2 trailers - a failed call still answers HTTP 200 - and Jetty exposes them through
 * {@link org.eclipse.jetty.client.Response#getTrailers()}. The JDK's client exposes none, which is why
 * there is no equivalent in that module.
 *
 * <p>Unlike {@link JettyHttpSender}, which negotiates its protocol, this one is pinned to
 * {@link HttpClientTransportOverHTTP2}: gRPC is HTTP/2 or nothing, and on cleartext there is no
 * {@code h2c} upgrade handshake to fall back to. That transport is the reason this module gained
 * {@code jetty-http2-client} and {@code jetty-http2-client-transport} as dependencies; see the module
 * README for what they cost.
 *
 * <p>The blocking {@link ContentResponse} is kept, for the same reason {@code JettyHttpSender} keeps it:
 * abandoning an unread body through a streaming listener aborts the exchange and drops the pooled
 * connection, which costs more per call than buffering the small response an OTLP collector returns.
 */
@Slf4j
public class JettyGrpcSender implements GrpcSender {

    private static final String CONTENT_TYPE = "application/grpc";

    private static final String STATUS = "grpc-status";

    private static final String MESSAGE = "grpc-message";

    private final HttpClient client;

    private final URI endpoint;

    private final Header[] boundHeaders;

    private final long requestTimeoutMillis;

    private final boolean compress;

    /**
     * A sender on the given settings.
     *
     * @param settings which method to call, and how
     * @throws IllegalStateException if the Jetty client cannot be started
     */
    public JettyGrpcSender(GrpcSenderSettings settings) {
        URI base = URI.create(settings.getEndpointUrl());
        this.endpoint = base.resolve(settings.getFullMethodName());
        this.requestTimeoutMillis = settings.getRequestTimeout().toMillis();
        this.compress = settings.getCompression() == GrpcSenderSettings.GrpcCompression.GZIP;

        this.boundHeaders = boundHeaders(settings);

        ClientConnector connector = new ClientConnector();
        if (settings.getSslContext() != null) {
            SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
            sslContextFactory.setSslContext(settings.getSslContext());
            connector.setSslContextFactory(sslContextFactory);
        }
        this.client = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client(connector)));
        this.client.setConnectTimeout(settings.getConnectTimeout().toMillis());
        HttpProxy proxy = proxyOf(settings.getProxySelector(), endpoint);
        if (proxy != null) {
            client.getProxyConfiguration().getProxies().add(proxy);
        }
        try {
            this.client.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start the Jetty HTTP/2 client", e);
        }
    }

    private static Header[] boundHeaders(GrpcSenderSettings settings) {
        Header[] required = {
                new Header("te", "trailers"),
                new Header("grpc-encoding", settings.getCompression().getEncoding()),
                new Header("grpc-accept-encoding", "identity,gzip"),
                // gRPC spells a deadline as a number and a unit; milliseconds is precise enough for an
                // export and short enough to stay inside the eight digits the wire format allows.
                new Header("grpc-timeout", Math.max(1, settings.getRequestTimeout().toMillis()) + "m")
        };
        Header[] headers = Arrays.copyOf(required, required.length + settings.getHeaders().size());
        int i = required.length;
        for (java.util.Map.Entry<String, String> header : settings.getHeaders().entrySet()) {
            headers[i++] = new Header(header.getKey(), header.getValue());
        }
        return headers;
    }

    private static HttpProxy proxyOf(ProxySelector proxySelector, URI endpoint) {
        if (proxySelector == null) {
            return null;
        }
        List<Proxy> proxies = proxySelector.select(endpoint);
        for (Proxy proxy : proxies) {
            if (proxy.type() == Proxy.Type.DIRECT) {
                return null;
            }
            if (proxy.type() == Proxy.Type.HTTP && proxy.address() instanceof InetSocketAddress) {
                InetSocketAddress address = (InetSocketAddress) proxy.address();
                return new HttpProxy(address.getHostString(), address.getPort());
            }
            log.warn("Ignoring the {} proxy selected for {}: this sender only supports an HTTP proxy.",
                    proxy.type(), endpoint);
        }
        return null;
    }

    @Override
    public GrpcReply send(byte[] data, int offset, int length, Header[] extraHeaders, GrpcReply into)
            throws IOException {
        Request request = client.newRequest(endpoint)
                .method(HttpMethod.POST)
                .timeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
                .body(body(data, offset, length));
        request.headers(fields -> {
            for (int i = 0; i < boundHeaders.length; i++) {
                fields.put(boundHeaders[i].getName(), boundHeaders[i].getValue());
            }
            if (extraHeaders != null) {
                for (int i = 0; i < extraHeaders.length; i++) {
                    fields.put(extraHeaders[i].getName(), extraHeaders[i].getValue());
                }
            }
        });

        ContentResponse response;
        try {
            response = request.send();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while calling " + endpoint);
        } catch (TimeoutException e) {
            throw new IOException("Timed out calling " + endpoint, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof IOException
                    ? (IOException) cause : new IOException("Failed to call " + endpoint, cause);
        }

        readMessage(response, into);
        // The trailers first, then the headers: a Trailers-Only response carries the status among the
        // headers and sends no trailers at all.
        HttpFields trailers = response.getTrailers();
        String status = trailers == null ? null : trailers.get(STATUS);
        String message = trailers == null ? null : trailers.get(MESSAGE);
        if (status == null) {
            status = response.getHeaders().get(STATUS);
            message = response.getHeaders().get(MESSAGE);
        }
        if (status == null) {
            throw new IOException("The gRPC endpoint " + endpoint + " answered HTTP " + response.getStatus()
                    + " with no grpc-status, in neither trailers nor headers");
        }
        into.status(parseStatus(status), message);
        return into;
    }

    /**
     * Stops the Jetty client, releasing its selector, thread pool and buffer pool.
     */
    @Override
    public void close() {
        try {
            client.stop();
        } catch (Exception e) {
            log.warn("Failed to stop the Jetty HTTP/2 client for {}", endpoint, e);
        }
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
     * Uncompressed, the payload is not copied: Jetty slices what it is given and leaves it unread.
     */
    private ByteBufferRequestContent body(byte[] data, int offset, int length) throws IOException {
        if (!compress) {
            return new ByteBufferRequestContent(CONTENT_TYPE,
                    ByteBuffer.wrap(header(length)), ByteBuffer.wrap(data, offset, length));
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(length);
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(data, offset, length);
        }
        byte[] message = compressed.toByteArray();
        return new ByteBufferRequestContent(CONTENT_TYPE,
                ByteBuffer.wrap(header(message.length)), ByteBuffer.wrap(message));
    }

    private byte[] header(int messageLength) {
        byte[] header = new byte[GrpcFraming.HEADER_LENGTH];
        GrpcFraming.writeHeader(header, 0, compress, messageLength);
        return header;
    }

    private void readMessage(ContentResponse response, GrpcReply into) throws IOException {
        byte[] framed = response.getContent();
        if (framed == null || framed.length < GrpcFraming.HEADER_LENGTH) {
            into.messageLength(0);
            return;
        }
        int length = GrpcFraming.messageLength(framed, 0);
        if (framed.length - GrpcFraming.HEADER_LENGTH < length) {
            throw new IOException("The gRPC endpoint " + endpoint + " announced a " + length
                    + " byte message but sent " + (framed.length - GrpcFraming.HEADER_LENGTH));
        }
        System.arraycopy(framed, GrpcFraming.HEADER_LENGTH, into.messageBuffer(length), 0, length);
        into.messageLength(length);
    }
}
