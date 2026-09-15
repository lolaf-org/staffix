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

import java.io.IOException;

/**
 * Posts a byte payload to one fixed endpoint, over and over, for the lifetime of the sender.
 *
 * <p>This is the shape every OTLP exporter in this library needs and the only shape any of them needs:
 * the endpoint, the content type and the headers that never change are bound once, when the sender is
 * built from its {@link HttpSenderSettings}, which is what lets an implementation pool the connection
 * and resolve the address a single time rather than on every publish.
 *
 * <p>Implementations live one per client library, each in its own module, so that a consumer depends on
 * exactly the HTTP client it has chosen. They differ substantially in what they allocate per exchange
 * and in what they drag onto the classpath; each module's {@code README.md} and each implementation's
 * class javadoc state both, including where that implementation is the wrong choice.
 *
 * <p>Instances are expected to be safe for concurrent use.
 *
 * @see HttpSenderSettings
 * @see HttpResponseException
 */
public interface HttpSender extends AutoCloseable {

    /**
     * Sends {@code length} bytes of {@code data}, starting at {@code offset}, to the bound endpoint.
     *
     * <p>The payload is read from a slice rather than a whole array because callers write into a
     * reusable buffer and send a prefix of it; taking the array whole would force a copy on their hot
     * path.
     *
     * @param data         buffer holding the payload
     * @param offset       index of the first byte to send
     * @param length       number of bytes to send
     * @param extraHeaders headers to add to the ones bound at construction, or {@code null} - which is
     *                     the normal case. It exists for callers that vary a header per request, such as
     *                     a {@code Content-Encoding} that depends on whether this particular payload was
     *                     compressed. An array rather than a {@code Map} so that walking it allocates
     *                     nothing; a caller whose headers do not change should build it once and pass
     *                     the same array on every publish
     * @return the HTTP status of the exchange, which is always a successful one
     * @throws HttpResponseException if the endpoint answered with a non-2xx status, carrying that status
     *                               and the response body
     * @throws IOException           if the exchange failed in transport
     */
    int send(byte[] data, int offset, int length, Header[] extraHeaders) throws IOException;

    /**
     * Sends the first {@code length} bytes of {@code data} with no per-request headers.
     *
     * @param data   buffer holding the payload
     * @param length number of bytes to send from the start of the buffer
     * @return the HTTP status of the exchange, which is always a successful one
     * @throws HttpResponseException if the endpoint answered with a non-2xx status
     * @throws IOException           if the exchange failed in transport
     */
    default int send(byte[] data, int length) throws IOException {
        return send(data, 0, length, null);
    }

    /**
     * Releases whatever the underlying client holds - its connection pool, and any thread it owns.
     *
     * <p>Declared without a checked exception, unlike {@link AutoCloseable#close()}: shutting a client
     * down has nothing useful to report, and a caller closing an exporter should not have to handle an
     * exception it cannot act on.
     */
    @Override
    void close();
}
