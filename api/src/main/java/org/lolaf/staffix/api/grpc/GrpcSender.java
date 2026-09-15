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

import org.lolaf.staffix.api.http.Header;

import java.io.IOException;

/**
 * Calls one fixed gRPC method, over and over, for the lifetime of the sender.
 *
 * <p>The gRPC counterpart of {@link org.lolaf.staffix.api.http.HttpSender}, and the same shape wherever
 * the protocols allow it: the endpoint, the method and the headers that never change bind once, at
 * construction, and the payload is a slice of a buffer the caller owns.
 *
 * <p><strong>Only some clients can implement this.</strong> gRPC reports its outcome in HTTP/2 trailers -
 * a failed call still answers HTTP 200 - so a client that cannot read trailers cannot serve gRPC at all,
 * whatever else it does. That rules out {@code java.net.http.HttpClient}, whose {@code HttpResponse}
 * exposes no trailers, and so rules out the {@code jdk} module. The {@code okhttp} and {@code jetty}
 * modules implement this; the {@code jdk} one does not, and there is no configuration that would let it.
 *
 * <p>Instances are expected to be safe for concurrent use, which is why the reply object is supplied by
 * the caller rather than held here.
 *
 * @see GrpcSenderSettings
 * @see GrpcReply
 */
public interface GrpcSender extends AutoCloseable {

    /**
     * Calls the bound method with {@code length} bytes of {@code data}, starting at {@code offset}.
     *
     * <p>The payload is the message itself, unframed: adding gRPC's five-byte header is the sender's job,
     * as is compressing it when the settings ask for that.
     *
     * <p>A non-{@link GrpcStatus#OK} status is <strong>not</strong> an exception. It is the server's
     * considered answer, both callers of this interface inspect it, and turning it into a throw would
     * make the ordinary case of a busy collector look like a broken one. An {@link IOException} means the
     * call did not produce a status at all.
     *
     * @param data         buffer holding the message
     * @param offset       index of the first byte to send
     * @param length       number of bytes to send
     * @param extraHeaders headers to add to the ones bound at construction, or {@code null} - which is
     *                     the normal case. An array rather than a map so that walking it allocates
     *                     nothing
     * @param into         a reply the caller owns; it is filled in and returned
     * @return {@code into}, carrying the status, the message and any response payload
     * @throws IOException if the call failed in transport, or the server answered without a
     *                     {@code grpc-status} at all
     */
    GrpcReply send(byte[] data, int offset, int length, Header[] extraHeaders, GrpcReply into)
            throws IOException;

    /**
     * Calls the bound method with the first {@code length} bytes of {@code data} and no per-call headers.
     *
     * @param data   buffer holding the message
     * @param length number of bytes to send from the start of the buffer
     * @param into   a reply the caller owns
     * @return {@code into}, carrying the outcome
     * @throws IOException if the call failed in transport
     */
    default GrpcReply send(byte[] data, int length, GrpcReply into) throws IOException {
        return send(data, 0, length, null, into);
    }

    /**
     * Releases whatever the underlying client holds - its connection pool, and any thread it owns.
     *
     * <p>Declared without a checked exception, as {@link org.lolaf.staffix.api.http.HttpSender#close()}
     * is and for the same reason: shutting a client down has nothing useful to report.
     */
    @Override
    void close();
}
