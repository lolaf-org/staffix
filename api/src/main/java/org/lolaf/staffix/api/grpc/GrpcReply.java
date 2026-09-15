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

import lombok.Getter;

/**
 * What one gRPC call came back as: its status, the message describing it, and whatever payload the
 * server returned.
 *
 * <p><strong>The caller owns this and passes it in.</strong> That is unusual enough to be worth the
 * explanation. A gRPC outcome is richer than an HTTP one - the status is not the HTTP status, and OTLP
 * genuinely reads the response payload, since {@code ExportLogsServiceResponse} carries a
 * {@code partial_success} saying how many records the collector dropped. Returning a fresh object per
 * call would allocate on the steady path; keeping one inside the sender and handing it back would break
 * the promise that a sender is safe for concurrent use. So the caller supplies one, and reuses it, the
 * same way it already owns the payload buffer it sends from.
 *
 * <p>Not safe for concurrent use, deliberately: one of these belongs to one in-flight call.
 */
@Getter
public final class GrpcReply {

    private static final byte[] EMPTY = new byte[0];

    private int statusCode;
    private String statusMessage = "";
    private byte[] message = EMPTY;
    private int messageLength;

    /**
     * Clears this for reuse, keeping the payload buffer already allocated.
     *
     * @return this, so a caller can pass the result straight into a send
     */
    public GrpcReply reset() {
        statusCode = GrpcStatus.OK;
        statusMessage = "";
        messageLength = 0;
        return this;
    }

    /**
     * @return {@code true} if the call succeeded
     */
    public boolean isOk() {
        return statusCode == GrpcStatus.OK;
    }

    /**
     * Records the outcome. Called by a sender, not by whoever is reading the reply.
     *
     * @param statusCode    the {@code grpc-status}
     * @param statusMessage the {@code grpc-message}, or {@code null} for none
     */
    public void status(int statusCode, String statusMessage) {
        this.statusCode = statusCode;
        this.statusMessage = statusMessage == null ? "" : statusMessage;
    }

    /**
     * Hands back a buffer to write the response payload into, growing the one already held rather than
     * allocating when it is big enough - which, for a collector answering the same shape every time, it
     * is after the first call.
     *
     * @param capacity bytes the sender is about to write
     * @return a buffer of at least that length
     */
    public byte[] messageBuffer(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("A response payload cannot be " + capacity + " bytes long");
        }
        if (message.length < capacity) {
            message = new byte[capacity];
        }
        return message;
    }

    /**
     * Records how much of {@link #messageBuffer(int)} was filled. Called by a sender.
     *
     * @param messageLength bytes written, from index 0
     */
    public void messageLength(int messageLength) {
        if (messageLength < 0 || messageLength > message.length) {
            throw new IllegalArgumentException("A reply cannot hold " + messageLength
                    + " bytes in a buffer of " + message.length);
        }
        this.messageLength = messageLength;
    }

    @Override
    public String toString() {
        return "GrpcReply{" + GrpcStatus.nameOf(statusCode)
                + (statusMessage.isEmpty() ? "" : ": " + statusMessage)
                + ", " + messageLength + " byte payload}";
    }
}
