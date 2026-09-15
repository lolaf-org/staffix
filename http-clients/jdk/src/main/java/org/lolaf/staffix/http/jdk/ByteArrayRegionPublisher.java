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
package org.lolaf.staffix.http.jdk;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

/**
 * Publishes a slice of a caller's array as the request body without copying it.
 *
 * <p>{@link HttpRequest.BodyPublishers#ofByteArray(byte[], int, int)} duplicates the payload into a
 * fresh buffer on every subscribe - measured at 16.4 KB per publish on a 16 KB payload, on plaintext
 * and over TLS alike, which was 44% of what this client allocated on a plaintext publish before it was
 * written. Handing out a {@link ByteBuffer#wrap(byte[], int, int)} over the caller's own array instead
 * costs one small wrapper and no copy at all.
 *
 * <p>The buffer is built per subscribe rather than once per publisher, so a request the client
 * re-subscribes to - a retry, or a redirect - sends the body from the start again rather than from a
 * buffer already drained.
 *
 * <p>Safe because a publish is synchronous: {@code send} does not return until the body has been
 * written, so the caller cannot be refilling the array while the client is reading it.
 */
final class ByteArrayRegionPublisher implements HttpRequest.BodyPublisher {

    private final byte[] data;

    private final int offset;

    private final int length;

    ByteArrayRegionPublisher(byte[] data, int offset, int length) {
        // Fail here rather than inside the client's selector thread, where the cause is far less clear.
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException(
                    "offset " + offset + " and length " + length + " do not fit an array of " + data.length);
        }
        this.data = data;
        this.offset = offset;
        this.length = length;
    }

    @Override
    public long contentLength() {
        return length;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
        subscriber.onSubscribe(new SingleBufferSubscription(subscriber, ByteBuffer.wrap(data, offset, length)));
    }

    /**
     * Delivers the one buffer this body is made of, then completes. Written by hand rather than reused
     * from the JDK because every publisher there that would do this copies first.
     */
    private static final class SingleBufferSubscription implements Flow.Subscription {

        private final Flow.Subscriber<? super ByteBuffer> subscriber;

        /**
         * The body, cleared once delivered or cancelled. Volatile because the client may request from a
         * different thread than the one it subscribed on.
         */
        private volatile ByteBuffer buffer;

        SingleBufferSubscription(Flow.Subscriber<? super ByteBuffer> subscriber, ByteBuffer buffer) {
            this.subscriber = subscriber;
            this.buffer = buffer;
        }

        @Override
        public void request(long n) {
            ByteBuffer body = buffer;
            if (body == null) {
                return;
            }
            buffer = null;
            if (n <= 0) {
                subscriber.onError(new IllegalArgumentException("A subscriber requested " + n + " items"));
                return;
            }
            subscriber.onNext(body);
            subscriber.onComplete();
        }

        @Override
        public void cancel() {
            buffer = null;
        }
    }
}
