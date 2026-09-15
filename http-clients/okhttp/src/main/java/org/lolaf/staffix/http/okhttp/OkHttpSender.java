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
import org.lolaf.staffix.api.http.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link HttpSender} backed by OkHttp.
 *
 * <p><strong>The one to use for an HTTPS collector.</strong> Measured on a 16 KB payload over loopback
 * it allocates about 13 KB per publish over TLS, where the JDK client spends 56 KB and micrometer's
 * default 64 KB, and it is the fastest of the three senders in this library on both transports. Over
 * plaintext it allocates about 8 KB, behind only Jetty and by little. The gap is largest exactly where a
 * real deployment sits, because OkHttp pools and reuses its buffers across exchanges rather than
 * allocating them per operation.
 *
 * <p>Those figures hold as the export grows: plaintext allocation is flat in the payload, and over TLS
 * this sender's margin widens rather than narrowing - 30 KB per publish at 128 KB against Jetty's 159 KB
 * and the JDK client's 184 KB. That depends on {@link SegmentedRequestBody}, which is what keeps okio
 * from materialising the whole body as segments before any of it is flushed; see its javadoc.
 *
 * <p>It also allocates about 2.2 KB per publish less than micrometer's own OkHttp sender, on both
 * transports, because the endpoint and the static headers bind here once rather than being reassembled
 * per request.
 *
 * <p><strong>The cost is the dependency.</strong> OkHttp 5 is written in Kotlin, so this module drags
 * {@code kotlin-stdlib} onto the classpath: four jars and about 2.9 MB in all, of which 1.7 MB is a
 * second language runtime that exists only to publish telemetry. That is a real thing to weigh in a
 * tightly controlled deployment, and it is invisible in the allocation numbers above.
 *
 * <p>Two behaviours here are corrections of what the senders this replaces did:
 *
 * <ul>
 *     <li>Sockets come from a {@link NoDelaySocketFactory}. A stock {@code OkHttpClient} leaves Nagle
 *     on and every publish stalls on the peer's delayed-ACK timer - 42 ms per publish against 0.7 ms.
 *     This is not optional, and it is why a caller should use this class rather than building an
 *     {@code OkHttpClient} of their own.</li>
 *     <li>The content type goes on the wire exactly as configured. Micrometer's own OkHttp sender
 *     appends {@code ; charset=utf-8} to it, which a collector may reject on a binary payload, and
 *     which callers were working around at the call site.</li>
 * </ul>
 *
 * <p>Note that OkHttp offers {@code [h2, http/1.1]} over TLS, so a collector that speaks HTTP/2 will be
 * taken up on it here where the other senders pin HTTP/1.1. Every setting in {@link HttpSenderSettings}
 * is honoured; the request timeout becomes OkHttp's call timeout, which covers the whole exchange.
 *
 * <p>Instances are safe for concurrent use.
 */
public class OkHttpSender implements HttpSender {

    private final OkHttpClient client;
    private final HttpUrl endpoint;
    private final MediaType contentType;
    private final Headers boundHeaders;

    /**
     * A sender on the given settings. If the settings carry an {@link SSLContext}, certificate chains
     * are cleaned against the platform's default trust manager; use
     * {@link #OkHttpSender(HttpSenderSettings, X509TrustManager)} to supply the one that matches a
     * custom context.
     *
     * @param settings where to publish, and how
     */
    public OkHttpSender(HttpSenderSettings settings) {
        this(settings, null);
    }

    /**
     * A sender on the given settings, with the trust manager OkHttp wants alongside an
     * {@link SSLContext}.
     *
     * <p>OkHttp does not use it to decide trust - the context's own socket factory does that during the
     * handshake - but to clean the certificate chain it reports and pins against. Passing the manager
     * that matches the context is therefore only necessary when certificate pinning is in play; the
     * platform default is used otherwise.
     *
     * @param settings     where to publish, and how
     * @param trustManager the trust manager backing {@link HttpSenderSettings#getSslContext()}, or
     *                     {@code null} for the platform's default
     */
    public OkHttpSender(HttpSenderSettings settings, X509TrustManager trustManager) {
        this(settings, trustManager, null);
    }

    /**
     * A sender on the given settings, pinned to an HTTP version.
     *
     * @param settings     where to publish, and how
     * @param trustManager the trust manager backing {@link HttpSenderSettings#getSslContext()}, or
     *                     {@code null} for the platform's default
     * @param httpVersion  the version to speak, or {@code null} for {@link HttpVersion#HTTP_1_1}.
     *                     OkHttp offers {@code [h2, http/1.1]} left to itself, so a TLS collector that
     *                     speaks HTTP/2 would silently get it; pinning makes that a choice
     */
    public OkHttpSender(HttpSenderSettings settings, X509TrustManager trustManager,
                        HttpVersion httpVersion) {
        HttpUrl url = HttpUrl.get(settings.getEndpointUrl());
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(settings.getConnectTimeout())
                .callTimeout(settings.getRequestTimeout())
                .socketFactory(new NoDelaySocketFactory())
                // The endpoint's scheme, not whether an SSLContext was supplied: a context set on a
                // cleartext endpoint is simply unused, and reading TLS from it picks ALPN semantics for
                // a connection where ALPN never runs.
                .protocols(protocolsFor(httpVersion, url.isHttps()));
        if (settings.getSslContext() != null) {
            builder.sslSocketFactory(settings.getSslContext().getSocketFactory(),
                    trustManager != null ? trustManager : platformTrustManager());
        }
        if (settings.getProxySelector() != null) {
            builder.proxySelector(settings.getProxySelector());
        }
        if (settings.getAuthenticator() != null) {
            builder.proxyAuthenticator(JdkAuthenticatorBridge.forProxy(settings.getAuthenticator()));
            builder.authenticator(JdkAuthenticatorBridge.forServer(settings.getAuthenticator()));
        }
        this.client = builder.build();
        this.endpoint = url;
        this.contentType = MediaType.get(settings.getContentType());
        Headers.Builder headers = new Headers.Builder();
        settings.getHeaders().forEach(headers::set);
        this.boundHeaders = headers.build();
    }

    /**
     * OkHttp rejects {@code [HTTP_2]} alone, so over TLS HTTP/2 is a preference rather than a pin.
     */
    private static List<Protocol> protocolsFor(HttpVersion httpVersion, boolean tls) {
        if (httpVersion != HttpVersion.HTTP_2) {
            return Collections.singletonList(Protocol.HTTP_1_1);
        }
        return tls
                ? Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
                : Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE);
    }

    private static String bodyOf(Response response) {
        try {
            return response.body().string();
        } catch (IOException e) {
            return null;
        }
    }

    static X509TrustManager platformTrustManager() {
        try {
            TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager trustManager : factory.getTrustManagers()) {
                if (trustManager instanceof X509TrustManager) {
                    return (X509TrustManager) trustManager;
                }
            }
            throw new IllegalStateException("The platform's default trust managers hold no X509TrustManager");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to load the platform's default trust manager", e);
        }
    }

    @Override
    public int send(byte[] data, int offset, int length, Header[] extraHeaders) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(endpoint)
                .headers(boundHeaders)
                .post(new SegmentedRequestBody(contentType, data, offset, length));
        if (extraHeaders != null) {
            // Indexed rather than enhanced-for: this runs on every publish and the array is here so
            // that walking it allocates nothing.
            for (int i = 0; i < extraHeaders.length; i++) {
                builder.header(extraHeaders[i].getName(), extraHeaders[i].getValue());
            }
        }
        try (Response response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new HttpResponseException(response.code(), response.message(), bodyOf(response));
            }
            // The body is left unread and closed here, which is what keeps a successful publish cheap.
            return response.code();
        }
    }

    @Override
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
