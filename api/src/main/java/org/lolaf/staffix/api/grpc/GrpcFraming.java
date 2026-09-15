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
 * The five-byte header gRPC puts in front of every message: one byte saying whether the message that
 * follows is compressed, then its length as a big-endian unsigned 32-bit integer.
 *
 * <p>This is the whole of gRPC's framing, and it is the same on the way out and on the way back, which is
 * why it lives here rather than in one client module: both the OkHttp and the Jetty senders need it, and
 * neither should own it.
 *
 * <p>Reading is deliberately strict. A length field is unsigned and so can describe a message longer than
 * a Java array can hold; a reader that quietly took the low 31 bits would turn a corrupt frame into a
 * plausible one, so {@link #messageLength(byte[], int)} refuses instead.
 */
public final class GrpcFraming {

    /**
     * Bytes a frame header occupies, in front of the message itself.
     */
    public static final int HEADER_LENGTH = 5;

    private GrpcFraming() {
    }

    /**
     * Writes a frame header into {@code buffer} at {@code offset}.
     *
     * @param buffer        the buffer to write into, with at least {@link #HEADER_LENGTH} bytes free at
     *                      {@code offset}
     * @param offset        index of the first header byte
     * @param compressed    whether the message that follows is compressed, which is what
     *                      {@code grpc-encoding} then names
     * @param messageLength length of the message that follows, not counting this header
     * @throws IllegalArgumentException if the header does not fit, or the length is negative
     */
    public static void writeHeader(byte[] buffer, int offset, boolean compressed, int messageLength) {
        if (messageLength < 0) {
            throw new IllegalArgumentException("A gRPC message cannot be " + messageLength + " bytes long");
        }
        if (offset < 0 || buffer.length - offset < HEADER_LENGTH) {
            throw new IllegalArgumentException("A gRPC frame header needs " + HEADER_LENGTH
                    + " bytes at offset " + offset + " of a " + buffer.length + " byte buffer");
        }
        buffer[offset] = (byte) (compressed ? 1 : 0);
        buffer[offset + 1] = (byte) (messageLength >>> 24);
        buffer[offset + 2] = (byte) (messageLength >>> 16);
        buffer[offset + 3] = (byte) (messageLength >>> 8);
        buffer[offset + 4] = (byte) messageLength;
    }

    /**
     * @param buffer the buffer holding a frame
     * @param offset index of the first header byte
     * @return whether the message that follows is compressed
     * @throws IllegalArgumentException if the header does not fit
     */
    public static boolean isCompressed(byte[] buffer, int offset) {
        requireHeader(buffer, offset);
        return buffer[offset] != 0;
    }

    /**
     * @param buffer the buffer holding a frame
     * @param offset index of the first header byte
     * @return length of the message that follows, not counting the header
     * @throws IllegalArgumentException if the header does not fit, or the length field describes a
     *                                  message longer than {@link Integer#MAX_VALUE} - which no OTLP
     *                                  export is, so it means a corrupt or misaligned frame
     */
    public static int messageLength(byte[] buffer, int offset) {
        requireHeader(buffer, offset);
        long length = ((long) (buffer[offset + 1] & 0xFF) << 24)
                | ((long) (buffer[offset + 2] & 0xFF) << 16)
                | ((long) (buffer[offset + 3] & 0xFF) << 8)
                | (buffer[offset + 4] & 0xFF);
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("A gRPC frame announced " + length
                    + " bytes, more than an array can hold; the frame is corrupt or misaligned");
        }
        return (int) length;
    }

    private static void requireHeader(byte[] buffer, int offset) {
        if (offset < 0 || buffer.length - offset < HEADER_LENGTH) {
            throw new IllegalArgumentException("A gRPC frame header needs " + HEADER_LENGTH
                    + " bytes at offset " + offset + " of a " + buffer.length + " byte buffer");
        }
    }
}
