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
package org.lolaf.staffix.ha.encryption;

import lombok.experimental.UtilityClass;

import java.nio.ByteBuffer;

/**
 * Copies between a frame buffer and the arrays the JCE works on fastest: its {@code ByteBuffer} methods copy a direct
 * buffer through a temporary array they allocate per call. Position and limit are restored, so the buffer reads as
 * untouched; Java 11 has no absolute bulk copy.
 */
@UtilityClass
class FrameBytes {

    static void get(ByteBuffer frames, int index, byte[] dst, int dstOffset, int length) {
        if (frames.hasArray()) {
            System.arraycopy(frames.array(), frames.arrayOffset() + index, dst, dstOffset, length);
            return;
        }
        int position = frames.position();
        int limit = frames.limit();
        frames.limit(frames.capacity()).position(index);
        frames.get(dst, dstOffset, length);
        frames.limit(limit).position(position);
    }

    static void put(ByteBuffer frames, int index, byte[] src, int srcOffset, int length) {
        if (frames.hasArray()) {
            System.arraycopy(src, srcOffset, frames.array(), frames.arrayOffset() + index, length);
            return;
        }
        int position = frames.position();
        int limit = frames.limit();
        frames.limit(frames.capacity()).position(index);
        frames.put(src, srcOffset, length);
        frames.limit(limit).position(position);
    }

    static void putLong(byte[] bytes, int offset, long value) {
        for (int i = 0; i < Long.BYTES; i++) {
            bytes[offset + i] = (byte) (value >>> (Long.SIZE - Byte.SIZE * (i + 1)));
        }
    }

    /**
     * XORs {@code value}, big-endian, into the 8 bytes from {@code offset}.
     */
    static void xorLong(byte[] bytes, int offset, long value) {
        for (int i = 0; i < Long.BYTES; i++) {
            bytes[offset + i] ^= (byte) (value >>> (Long.SIZE - Byte.SIZE * (i + 1)));
        }
    }
}
