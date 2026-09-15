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
package org.lolaf.staffix.monitoring.micrometer.otlp;

import io.micrometer.core.ipc.http.HttpSender;
import org.lolaf.staffix.api.http.Header;
import org.lolaf.staffix.api.http.HttpResponseException;
import org.lolaf.staffix.api.http.HttpSenderSettings;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

/**
 * Publishes micrometer's metrics through one of this library's {@link org.lolaf.staffix.api.http.HttpSender}
 * implementations.
 *
 * <p>Micrometer's own sender interface is built around a request object carrying its own URL, method and
 * headers, assembled per exchange. Ours binds all of that once, at construction, which is what lets an
 * implementation pool its connection and resolve its address a single time. This adapter is where the two
 * meet, and it is written so that the impedance costs nothing per publish:
 *
 * <ul>
 *     <li><strong>One response object for a repeated status.</strong> {@code Response} is immutable and
 *     a successful publish carries no body, so the same instance is handed back for as long as the
 *     collector keeps answering with the same status - which, in a healthy system, is forever.</li>
 *     <li><strong>One header array for an unchanged header set.</strong> Micrometer builds a fresh map
 *     per request, but its contents do not vary between publishes for a given registry. The array built
 *     from it is kept and checked against the map by lookup rather than by iteration, so a hit walks the
 *     array and allocates nothing.</li>
 *     <li><strong>The entity is passed through, not copied.</strong> Micrometer hands over an array it
 *     owns; it goes to the delegate as a whole slice.</li>
 * </ul>
 *
 * <p>The endpoint is checked rather than followed. A registry publishes to {@code OtlpConfig.url()} for
 * its lifetime and the delegate is bound to that same address, so a request for anywhere else means the
 * two have drifted apart - a misconfiguration this fails loudly on rather than silently publishing
 * metrics to the wrong place. The check compares the URL's stored fields; deliberately not
 * {@link URL#equals(Object)}, which resolves the host through DNS and can block the publishing thread.
 *
 * <p>A non-2xx status comes back from the delegate as an {@link HttpResponseException} and is mapped
 * back into the {@code Response} micrometer expects, losing nothing:
 * {@code OtlpHttpMetricsSender} reads the body only on that branch.
 */
public class MicrometerHttpSenderAdapter implements HttpSender, AutoCloseable {

    private final org.lolaf.staffix.api.http.HttpSender delegate;

    private final String endpointUrl;

    private final String protocol;

    private final String authority;

    private final String file;

    /**
     * The last successful response handed back, reused while the status keeps repeating.
     */
    private volatile Response lastSuccess = new Response(200, null);

    /**
     * The header array built from the last request's headers, reused while they keep repeating.
     */
    private volatile Header[] lastHeaders = new Header[0];

    /**
     * @param delegate    the sender to publish through, bound to {@code endpointUrl}
     * @param endpointUrl the address the registry publishes to, which every request must match
     */
    public MicrometerHttpSenderAdapter(org.lolaf.staffix.api.http.HttpSender delegate, String endpointUrl) {
        this.delegate = delegate;
        this.endpointUrl = endpointUrl;
        URL url = parse(endpointUrl);
        this.protocol = url.getProtocol();
        this.authority = url.getAuthority();
        this.file = url.getFile();
    }

    /**
     * A sender publishing through {@code delegate}, taking the endpoint from the settings the delegate
     * itself was built from.
     *
     * @param delegate the sender to publish through
     * @param settings the settings that delegate was built from
     */
    public MicrometerHttpSenderAdapter(org.lolaf.staffix.api.http.HttpSender delegate,
                                       HttpSenderSettings settings) {
        this(delegate, settings.getEndpointUrl());
    }

    /**
     * Compares by lookup rather than by iterating the map, so checking a hit costs no iterator. The size
     * check is what catches a header having been added.
     */
    private static boolean matches(Header[] cached, Map<String, String> headers) {
        if (cached.length != headers.size()) {
            return false;
        }
        for (int i = 0; i < cached.length; i++) {
            if (!cached[i].getValue().equals(headers.get(cached[i].getName()))) {
                return false;
            }
        }
        return true;
    }

    private static URL parse(String endpointUrl) {
        try {
            return new URL(endpointUrl);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Not a usable OTLP endpoint: " + endpointUrl, e);
        }
    }

    @Override
    public Response send(Request request) throws IOException {
        if (request.getMethod() != Method.POST) {
            throw new UnsupportedOperationException("This sender only publishes with POST, not "
                    + request.getMethod() + "; it is bound to " + endpointUrl);
        }
        requireBoundEndpoint(request.getUrl());
        byte[] entity = request.getEntity();
        try {
            int status = delegate.send(entity, 0, entity.length, headersOf(request.getRequestHeaders()));
            return successResponse(status);
        } catch (HttpResponseException e) {
            return new Response(e.getStatusCode(), e.getBody());
        }
    }

    /**
     * Closes the delegate. Micrometer's interface has no lifecycle of its own, so whoever built the
     * sender closes it through this.
     */
    @Override
    public void close() {
        delegate.close();
    }

    /**
     * Hands back the response for a status already seen, so a healthy publisher allocates nothing to
     * describe a success it has described before.
     */
    private Response successResponse(int status) {
        Response cached = lastSuccess;
        if (cached.code() == status) {
            return cached;
        }
        Response response = new Response(status, null);
        lastSuccess = response;
        return response;
    }

    /**
     * Converts micrometer's per-request header map into the array the delegate takes, reusing the last
     * array while the headers repeat.
     */
    private Header[] headersOf(Map<String, String> headers) {
        Header[] cached = lastHeaders;
        if (matches(cached, headers)) {
            return cached;
        }
        Header[] built = new Header[headers.size()];
        int i = 0;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            built[i++] = new Header(header.getKey(), header.getValue());
        }
        lastHeaders = built;
        return built;
    }

    /**
     * Compares the URL on its stored fields, which together are what its external form is built from.
     * Not {@link URL#equals(Object)}: that resolves the host.
     */
    private void requireBoundEndpoint(URL url) {
        if (!protocol.equals(url.getProtocol()) || !authority.equals(url.getAuthority())
                || !file.equals(url.getFile())) {
            throw new IllegalStateException("The registry asked to publish to " + url
                    + " but this sender is bound to " + endpointUrl);
        }
    }
}
