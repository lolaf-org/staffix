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

import lombok.Getter;

import java.io.OutputStream;
import java.util.Arrays;

/**
 * A growable byte buffer that hands out the array it wrote into, for callers that marshal a payload and
 * then send a prefix of it.
 *
 * <p>The two differences from {@link java.io.ByteArrayOutputStream} are the whole reason this exists.
 * Nothing here is synchronized, and {@link #getBuffer()} returns the live array rather than a copy of
 * it - which is what lets a caller marshal into this and pass the result straight to
 * {@link HttpSender#send(byte[], int, int, Header[])} without duplicating the payload on every publish.
 * The buffer is therefore longer than the content: {@link #getWriteOffset()} is the length that was
 * written, and the two together are the slice to send.
 *
 * <p>{@link #reset()} rewinds the offset without releasing the array, so a caller that publishes on a
 * schedule allocates once and reuses it for the lifetime of the sender. Growth is by the requested size
 * plus 20%, so a payload that creeps upwards settles rather than reallocating on every write.
 *
 * <p>Not safe for concurrent use, deliberately. A caller that publishes from more than one thread needs
 * one of these per thread, or one per in-flight payload.
 */
@Getter
public class FastByteArrayOutputStream extends OutputStream {

    private byte[] buffer;
    private int writeOffset;

    /**
     * @param size the initial capacity, which grows on demand
     */
    public FastByteArrayOutputStream(int size) {
        buffer = new byte[size];
    }

    @Override
    public void write(int b) {
        ensureCapacity(writeOffset + 1);
        buffer[writeOffset] = (byte) b;
        writeOffset++;
    }

    @Override
    public void write(byte[] bytes, int off, int len) {
        ensureCapacity(writeOffset + len);
        System.arraycopy(bytes, off, buffer, writeOffset, len);
        writeOffset += len;
    }

    private void ensureCapacity(int minCapacity) {
        int oldCapacity = buffer.length;
        int minGrowth = minCapacity - oldCapacity;
        if (minGrowth > 0) {
            int newCapacity = minCapacity + (int) (minCapacity * 0.2);
            buffer = Arrays.copyOf(buffer, newCapacity);
        }
    }

    /**
     * Rewinds to an empty buffer, keeping the array already allocated.
     *
     * @return this, so a caller can write straight into the result
     */
    public FastByteArrayOutputStream reset() {
        writeOffset = 0;
        return this;
    }
}
