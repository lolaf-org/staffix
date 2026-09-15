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
package org.lolaf.staffix.tracing.otlp.sender;

import io.opentelemetry.sdk.common.export.Compressor;
import io.opentelemetry.sdk.common.export.MessageWriter;
import org.lolaf.staffix.api.http.FastByteArrayOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Marshals an export into a byte buffer the caller borrows for as long as it needs it, and pools those
 * buffers between exports.
 *
 * <p><strong>Why this exists at all, rather than a byte array per export.</strong> {@code OtelTracing}
 * builds its exporters with {@code MemoryMode.REUSABLE_DATA}, so the SDK's marshaller reuses its own
 * buffers between batches and the bytes it writes are only ours while we are inside
 * {@link MessageWriter#writeMessage(OutputStream)}. The obvious way to make an asynchronous sender -
 * marshal, hand the array to an executor, return - is therefore a correctness bug: the SDK is free to
 * start the next batch while the previous payload is still in flight.
 *
 * <p>So {@link #marshal} runs on the calling thread, into a buffer nobody else holds, and the caller
 * keeps that buffer until it is done with it - which for a retrying sender is until the last attempt has
 * finished, since a retry means sending the same bytes again. A pool rather than one buffer per sender,
 * because a retry can still be holding the buffer for batch N when the SDK offers batch N+1.
 *
 * <p>Buffers grow to fit and stay grown, which is the point: a steady export size reaches its capacity
 * once and reallocates never. The pool is bounded, so a burst of concurrent exports allocates buffers
 * that are dropped on release rather than accumulated.
 *
 * <p>Safe for concurrent use. Each lease owns its buffer outright until it is closed.
 */
final class PayloadBuffers {

    private final int initialCapacity;

    private final BlockingQueue<FastByteArrayOutputStream> pool;

    /**
     * @param initialCapacity the size a freshly allocated buffer starts at, before it grows to fit
     * @param poolBound       how many buffers to keep for reuse; a release past this drops the buffer
     */
    PayloadBuffers(int initialCapacity, int poolBound) {
        this.initialCapacity = initialCapacity;
        this.pool = new ArrayBlockingQueue<>(poolBound);
    }

    /**
     * Writes one export into a borrowed buffer.
     *
     * <p>Must be called on the thread the SDK handed the writer to, before that call returns. See the
     * class javadoc for why.
     *
     * @param writer     the export to marshal
     * @param compressor the compressor to wrap the payload in, or {@code null} for none
     * @return the buffer holding it, which the caller closes when the last attempt to send it is done
     * @throws IOException if marshalling or compressing failed
     */
    Lease marshal(MessageWriter writer, Compressor compressor) throws IOException {
        FastByteArrayOutputStream buffer = pool.poll();
        if (buffer == null) {
            // getContentLength() is the marshalled size, so it sizes the buffer exactly when nothing is
            // compressing it and generously when something is. Either way it beats growing into place.
            buffer = new FastByteArrayOutputStream(Math.max(initialCapacity, writer.getContentLength()));
        }
        buffer.reset();

        if (compressor == null) {
            writer.writeMessage(buffer);
        } else {
            // Closing the wrapper is what flushes a compressor's trailer. It closes the buffer beneath
            // it too, which costs nothing: FastByteArrayOutputStream does not override close().
            try (OutputStream compressed = compressor.compress(buffer)) {
                writer.writeMessage(compressed);
            }
        }
        return new Lease(buffer);
    }

    /**
     * @return how many buffers are held for reuse, which is what a test asserts the bound against
     */
    int pooled() {
        return pool.size();
    }

    /**
     * A buffer borrowed from the pool, and the slice of it that was written.
     *
     * <p>The array is the live one, not a copy - handing it to
     * {@link org.lolaf.staffix.api.http.HttpSender#send(byte[], int, int, org.lolaf.staffix.api.http.Header[])}
     * costs nothing. It is valid until {@link #close()}, and reading it afterwards reads a buffer
     * somebody else is now writing.
     */
    final class Lease implements AutoCloseable {

        private final FastByteArrayOutputStream buffer;

        private final int length;

        private boolean closed;

        private Lease(FastByteArrayOutputStream buffer) {
            this.buffer = buffer;
            this.length = buffer.getWriteOffset();
        }

        /**
         * @return the live array holding the payload, from index 0
         */
        byte[] buffer() {
            return buffer.getBuffer();
        }

        /**
         * @return how many bytes of it are the payload
         */
        int length() {
            return length;
        }

        /**
         * Returns the buffer for reuse, or drops it if the pool is already full. Calling this twice is
         * harmless and does not pool the buffer twice.
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            pool.offer(buffer);
        }
    }
}