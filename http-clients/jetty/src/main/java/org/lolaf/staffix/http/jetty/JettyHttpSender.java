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
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.lolaf.staffix.api.http.*;
import org.lolaf.staffix.api.http.HttpResponseException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link HttpSender} backed by the Jetty {@link HttpClient}.
 *
 * <p><strong>The cheapest over plaintext.</strong> Measured on a 16 KB payload over loopback it
 * allocates about 5.9 KB per publish, against 8.3 KB for OkHttp and 17 KB for the JDK client, with
 * OkHttp matching it on time. Jetty pools the byte buffers it does I/O through, where the JDK client
 * allocates a fresh pair per TLS operation and the legacy stack behind {@code HttpUrlConnectionSender}
 * copies the entity through a stream. An in-cluster collector reached over cleartext is exactly what
 * this is for.
 *
 * <p><strong>Only second over TLS</strong>, at about 28 KB per publish: better than the JDK client's
 * 56 KB, but 2.1 times OkHttp's 13 KB, and the slowest of the three there in time. For an HTTPS
 * collector, prefer OkHttp.
 *
 * <p><strong>Requires Java 17.</strong> Jetty 12 is compiled for it, while the rest of this library
 * runs from Java 11, which is why this client lives in a module of its own with a raised baseline
 * rather than behind an optional dependency that would fail to link at runtime with no warning at build
 * time. The dependency is seven jars and about 1.9 MB.
 *
 * <p>Three points where this honours {@link HttpSenderSettings} differently from the others, because
 * Jetty's model differs:
 *
 * <ul>
 *     <li>Only an HTTP proxy is taken from the {@code ProxySelector}, selected once for the bound
 *     endpoint. A SOCKS proxy is reported and ignored.</li>
 *     <li>The {@code Authenticator} is consulted once at construction rather than on each challenge,
 *     because Jetty's authentication store is populated up front. Only {@code Basic} is served.</li>
 *     <li>The response body is buffered by Jetty's blocking API whether or not it is wanted, though a
 *     {@code String} is only built from it on the failure branch. Draining it deliberately: abandoning
 *     an unread body aborts the exchange and drops the pooled connection, which would cost far more
 *     per publish than the empty body an OTLP receiver answers with.</li>
 * </ul>
 *
 * <p>The client is started in the constructor and must be released with {@link #close()}, which stops
 * Jetty's selector, thread pool and buffer pool.
 *
 * <p>Instances are safe for concurrent use.
 */
@Slf4j
public class JettyHttpSender implements HttpSender {

    private final HttpClient client;

    private final URI endpoint;

    private final String contentType;

    private final long requestTimeoutMillis;

    /**
     * The headers that never change, flattened at construction so that publishing walks an array rather
     * than a map's entry set.
     */
    private final Header[] boundHeaders;

    /**
     * A sender on the given settings, letting Jetty build its own thread pool.
     *
     * @param settings where to publish, and how
     * @throws IllegalStateException if the Jetty client cannot be started
     */
    public JettyHttpSender(HttpSenderSettings settings) {
        this(settings, null);
    }

    /**
     * A sender on the given settings, with the one knob this client has that the shared settings do not
     * express.
     *
     * @param settings where to publish, and how
     * @param executor executor for Jetty's own tasks. Defaults to the queued thread pool Jetty builds
     *                 for itself
     * @throws IllegalStateException if the Jetty client cannot be started
     */
    public JettyHttpSender(HttpSenderSettings settings, Executor executor) {
        this(settings, executor, null);
    }

    /**
     * A sender on the given settings, pinned to an HTTP version.
     *
     * @param settings    where to publish, and how
     * @param executor    executor for Jetty's own tasks. Defaults to the queued thread pool Jetty
     *                    builds for itself
     * @param httpVersion the version to speak, or {@code null} for {@link HttpVersion#HTTP_1_1}.
     *                    {@link HttpVersion#HTTP_2} adds the HTTP/2 connection factory to the dynamic
     *                    transport, which costs no new jars here - the module already carries them for
     *                    {@link JettyGrpcSender}
     * @throws IllegalStateException if the Jetty client cannot be started
     */
    public JettyHttpSender(HttpSenderSettings settings, Executor executor, HttpVersion httpVersion) {
        this.endpoint = URI.create(settings.getEndpointUrl());
        this.contentType = settings.getContentType();
        this.requestTimeoutMillis = settings.getRequestTimeout().toMillis();
        this.boundHeaders = settings.getHeaders().entrySet().stream()
                .map(header -> new Header(header.getKey(), header.getValue()))
                .toArray(Header[]::new);

        ClientConnector connector = new ClientConnector();
        if (settings.getSslContext() != null) {
            SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
            sslContextFactory.setSslContext(settings.getSslContext());
            connector.setSslContextFactory(sslContextFactory);
        }
        if (executor != null) {
            connector.setExecutor(executor);
        }
        this.client = new HttpClient(transportFor(connector, httpVersion));
        this.client.setConnectTimeout(settings.getConnectTimeout().toMillis());
        HttpProxy proxy = proxyOf(settings.getProxySelector(), endpoint);
        if (proxy != null) {
            client.getProxyConfiguration().getProxies().add(proxy);
        }
        if (settings.getAuthenticator() != null) {
            addBasicAuthentication(settings.getAuthenticator());
        }
        try {
            this.client.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start the Jetty HTTP client", e);
        }
    }

    private static HttpClientTransportDynamic transportFor(ClientConnector connector,
                                                           HttpVersion httpVersion) {
        if (httpVersion != HttpVersion.HTTP_2) {
            return new HttpClientTransportDynamic(connector);
        }
        return new HttpClientTransportDynamic(connector,
                new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(connector)),
                HttpClientConnectionFactory.HTTP11);
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
    public int send(byte[] data, int offset, int length, Header[] extraHeaders) throws IOException {
        Request request = client.newRequest(endpoint)
                .method(HttpMethod.POST)
                .timeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
                .body(new ByteBufferRequestContent(contentType, ByteBuffer.wrap(data, offset, length)));
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
            throw new InterruptedIOException("Interrupted while publishing to " + endpoint);
        } catch (TimeoutException e) {
            throw new IOException("Timed out publishing to " + endpoint, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof IOException
                    ? (IOException) cause : new IOException("Failed to publish to " + endpoint, cause);
        }
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            // The only branch that has a use for the body, and so the only one that builds a String.
            throw new HttpResponseException(response.getStatus(), response.getReason(),
                    response.getContentAsString());
        }
        return response.getStatus();
    }

    @Override
    public void close() {
        try {
            client.stop();
        } catch (Exception e) {
            log.warn("Failed to stop the Jetty HTTP client", e);
        }
    }

    /**
     * Asks the authenticator for the endpoint's credentials now, because Jetty's authentication store is
     * populated up front rather than consulted on a challenge.
     */
    private void addBasicAuthentication(Authenticator authenticator) {
        PasswordAuthentication credentials = authenticator.requestPasswordAuthenticationInstance(
                endpoint.getHost(), null, endpoint.getPort(), endpoint.getScheme(), null, "Basic", null,
                Authenticator.RequestorType.SERVER);
        if (credentials == null) {
            log.warn("The authenticator offered no credentials for {}; requests will be sent unauthenticated.", endpoint);
            return;
        }
        client.getAuthenticationStore().addAuthentication(new BasicAuthentication(endpoint,
                Authentication.ANY_REALM, credentials.getUserName(), new String(credentials.getPassword())));
    }
}
