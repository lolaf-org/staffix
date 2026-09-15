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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The buffer lease, which is where the {@code MemoryMode.REUSABLE_DATA} hazard is actually pinned down:
 * a payload still in flight must never share an array with the batch behind it.
 */
class PayloadBuffersTest {

    private static final Compressor GZIP = new Compressor() {
        @Override
        public String getEncoding() {
            return "gzip";
        }

        @Override
        public OutputStream compress(OutputStream out) throws IOException {
            return new GZIPOutputStream(out);
        }
    };

    private static MessageWriter writing(byte[] payload) {
        return new MessageWriter() {
            @Override
            public void writeMessage(OutputStream out) throws IOException {
                out.write(payload);
            }

            @Override
            public int getContentLength() {
                return payload.length;
            }
        };
    }

    private static MessageWriter writing(String payload) {
        return writing(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] payloadOf(PayloadBuffers.Lease lease) {
        return Arrays.copyOf(lease.buffer(), lease.length());
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[512];
            int read;
            while ((read = in.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        }
    }

    @Test
    void writesTheExportIntoTheBorrowedBuffer() throws IOException {
        PayloadBuffers buffers = new PayloadBuffers(64, 2);

        try (PayloadBuffers.Lease lease = buffers.marshal(writing("a batch of spans"), null)) {
            assertThat(payloadOf(lease)).isEqualTo("a batch of spans".getBytes(StandardCharsets.UTF_8));
            assertThat(lease.length()).isEqualTo(16);
        }
    }

    /**
     * The reason this class exists. Holding a lease models a retry still in flight; the export behind it
     * must land somewhere else entirely.
     */
    @Test
    void doesNotHandOutTheSameArrayToAnExportWhileTheLastOneIsStillHeld() throws IOException {
        PayloadBuffers buffers = new PayloadBuffers(64, 2);

        try (PayloadBuffers.Lease inFlight = buffers.marshal(writing("first batch"), null)) {
            try (PayloadBuffers.Lease next = buffers.marshal(writing("second batch"), null)) {
                assertThat(next.buffer()).isNotSameAs(inFlight.buffer());
            }
            assertThat(payloadOf(inFlight))
                    .as("the held payload must survive the export that followed it")
                    .isEqualTo("first batch".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void reusesTheArrayOnceTheLeaseIsReleased() throws IOException {
        PayloadBuffers buffers = new PayloadBuffers(64, 2);

        byte[] first;
        try (PayloadBuffers.Lease lease = buffers.marshal(writing("a batch of spans"), null)) {
            first = lease.buffer();
        }

        try (PayloadBuffers.Lease lease = buffers.marshal(writing("another batch!!"), null)) {
            assertThat(lease.buffer()).isSameAs(first);
            assertThat(payloadOf(lease)).isEqualTo("another batch!!".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void dropsABufferReleasedPastThePoolBoundRatherThanAccumulating() throws IOException {
        PayloadBuffers buffers = new PayloadBuffers(64, 1);

        PayloadBuffers.Lease first = buffers.marshal(writing("first"), null);
        PayloadBuffers.Lease second = buffers.marshal(writing("second"), null);
        assertThat(buffers.pooled()).isZero();

        first.close();
        second.close();

        assertThat(buffers.pooled())
                .as("the pool is bounded at one, so the second buffer is dropped rather than kept")
                .isEqualTo(1);
    }

    @Test
    void closingALeaseTwiceDoesNotPoolItsBufferTwice() throws IOException {
        PayloadBuffers buffers = new PayloadBuffers(64, 4);

        PayloadBuffers.Lease lease = buffers.marshal(writing("a batch of spans"), null);
        lease.close();
        lease.close();

        assertThat(buffers.pooled()).isEqualTo(1);
    }

    @Test
    void compressesIntoTheBufferAndReportsTheCompressedLength() throws IOException {
        PayloadBuffers buffers = new PayloadBuffers(64, 2);
        // Repetitive enough that gzip is a clear win, so the length assertion is not a coin toss.
        byte[] payload = new byte[4096];
        Arrays.fill(payload, (byte) 'x');

        try (PayloadBuffers.Lease lease = buffers.marshal(writing(payload), GZIP)) {
            assertThat(lease.length())
                    .as("the compressed body is what gets sent, not the marshalled length")
                    .isLessThan(payload.length);
            assertThat(gunzip(payloadOf(lease))).isEqualTo(payload);
        }
    }

    @Test
    void growsABorrowedBufferToFitAPayloadBiggerThanItsCapacity() throws IOException {
        PayloadBuffers buffers = new PayloadBuffers(8, 2);
        byte[] payload = new byte[10_000];
        Arrays.fill(payload, (byte) 'y');

        try (PayloadBuffers.Lease lease = buffers.marshal(writing(payload), null)) {
            assertThat(payloadOf(lease)).isEqualTo(payload);
        }
    }
}
