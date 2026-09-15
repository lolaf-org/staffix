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

/**
 * The gRPC status codes, as they arrive in the {@code grpc-status} trailer.
 *
 * <p>Constants rather than an enum because the wire carries an integer a sender must report whatever it
 * is: the trailer is parsed with {@code Integer.parseInt}, so a server can answer a code outside the
 * sixteen below and {@link #nameOf(int)} renders it verbatim. Both operations here are therefore total
 * functions over {@code int}.
 *
 * <p>Note that a gRPC call reports failure here and not in the HTTP status: a failed call still answers
 * <strong>HTTP 200</strong>, with the real outcome in a trailer. That is the whole reason this library's
 * gRPC senders need a client that can read HTTP/2 trailers.
 */
public final class GrpcStatus {

    /**
     * The call succeeded. The only non-failure code.
     */
    public static final int OK = 0;

    /**
     * The call was cancelled, typically by the caller.
     */
    public static final int CANCELLED = 1;

    /**
     * An error whose cause the server could not classify.
     */
    public static final int UNKNOWN = 2;

    /**
     * The request was rejected as malformed; repeating it unchanged cannot help.
     */
    public static final int INVALID_ARGUMENT = 3;

    /**
     * The deadline passed before the call completed.
     */
    public static final int DEADLINE_EXCEEDED = 4;

    /**
     * The named method or resource does not exist.
     */
    public static final int NOT_FOUND = 5;

    /**
     * The resource the call would create already exists.
     */
    public static final int ALREADY_EXISTS = 6;

    /**
     * The caller is authenticated but not permitted to make this call.
     */
    public static final int PERMISSION_DENIED = 7;

    /**
     * A quota or per-server resource is exhausted; a collector under load answers this.
     */
    public static final int RESOURCE_EXHAUSTED = 8;

    /**
     * The system is in a state the call cannot be served from.
     */
    public static final int FAILED_PRECONDITION = 9;

    /**
     * The call was aborted, typically by a concurrency conflict.
     */
    public static final int ABORTED = 10;

    /**
     * The call was attempted past the valid range.
     */
    public static final int OUT_OF_RANGE = 11;

    /**
     * The server does not implement this method.
     */
    public static final int UNIMPLEMENTED = 12;

    /**
     * The server broke an invariant of its own.
     */
    public static final int INTERNAL = 13;

    /**
     * The server is unavailable - down, restarting, or unreachable. The common transient failure.
     */
    public static final int UNAVAILABLE = 14;

    /**
     * Data was lost or corrupted irrecoverably.
     */
    public static final int DATA_LOSS = 15;

    /**
     * The call carried no valid credentials.
     */
    public static final int UNAUTHENTICATED = 16;

    private static final String[] NAMES = {
            "OK", "CANCELLED", "UNKNOWN", "INVALID_ARGUMENT", "DEADLINE_EXCEEDED", "NOT_FOUND",
            "ALREADY_EXISTS", "PERMISSION_DENIED", "RESOURCE_EXHAUSTED", "FAILED_PRECONDITION",
            "ABORTED", "OUT_OF_RANGE", "UNIMPLEMENTED", "INTERNAL", "UNAVAILABLE", "DATA_LOSS",
            "UNAUTHENTICATED"
    };

    private GrpcStatus() {
    }

    /**
     * Whether a status is worth sending the same request again.
     *
     * <p>The set the OTLP specification calls retryable: {@link #CANCELLED}, {@link #DEADLINE_EXCEEDED},
     * {@link #RESOURCE_EXHAUSTED}, {@link #ABORTED}, {@link #OUT_OF_RANGE}, {@link #UNAVAILABLE} and
     * {@link #DATA_LOSS}. Everything else describes a request that will be rejected the same way however
     * many times it is sent.
     *
     * @param statusCode the {@code grpc-status} the server answered with
     * @return whether to try again
     */
    public static boolean isRetryable(int statusCode) {
        switch (statusCode) {
            case CANCELLED:
            case DEADLINE_EXCEEDED:
            case RESOURCE_EXHAUSTED:
            case ABORTED:
            case OUT_OF_RANGE:
            case UNAVAILABLE:
            case DATA_LOSS:
                return true;
            default:
                return false;
        }
    }

    /**
     * @param statusCode any integer, including one this library does not know
     * @return the gRPC name of that code, or the code itself rendered as text if it has no name
     */
    public static String nameOf(int statusCode) {
        return statusCode >= 0 && statusCode < NAMES.length ? NAMES[statusCode] : Integer.toString(statusCode);
    }
}
