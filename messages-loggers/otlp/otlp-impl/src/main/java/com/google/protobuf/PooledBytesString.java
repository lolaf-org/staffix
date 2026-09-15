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
package com.google.protobuf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;

/**
 * A mutable, reusable {@link ByteString} backed by a growable {@code byte[]}. It behaves like
 * protobuf's internal {@code LiteralByteString} except that only the first {@link #length} bytes of
 * the backing array are considered valid, and the backing array is reused (and grown when needed)
 * across serializations to avoid per-message allocation.
 *
 * <p>Lives in the {@code com.google.protobuf} package so it can reach the package-private helpers
 * ({@link Utf8}, {@link Internal}, {@link ByteOutput}, {@link CodedInputStream}) used by the base
 * {@link ByteString} contract.
 */
public final class PooledBytesString extends ByteString {

    private byte[] bytes;
    private int length;

    public PooledBytesString(int initialCapacity) {
        this.bytes = new byte[initialCapacity];
        this.length = 0;
    }

    /**
     * Fills this byte string with the remaining bytes of {@code src}, growing the backing array if
     * required, and replaces every {@code fieldSeparator} byte with {@code replacement}.
     */
    public boolean setBytes(ByteBuffer src, byte fieldSeparator, byte replacement) {
        int remaining = src.remaining();
        boolean noResize = true;
        if (remaining > bytes.length) {
            bytes = new byte[remaining];
            noResize = false;
        }
        src.get(bytes, 0, remaining);
        this.length = remaining;
        if (replacement != fieldSeparator) {
            for (int i = 0; i < remaining; i++) {
                if (bytes[i] == fieldSeparator) {
                    bytes[i] = replacement;
                }
            }
        }
        return noResize;
    }

    @Override
    public int size() {
        return length;
    }

    @Override
    public byte byteAt(int index) {
        return bytes[index];
    }

    @Override
    byte internalByteAt(int index) {
        return bytes[index];
    }

    @Override
    void writeTo(ByteOutput byteOutput) throws IOException {
        // deliberately not writeLazy: the backing array is pooled and reused, it must be copied out
        byteOutput.write(bytes, 0, length);
    }

    @Override
    public boolean isValidUtf8() {
        return Utf8.isValidUtf8(bytes, 0, length);
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {
        out.write(bytes, 0, length);
    }

    @Override
    void writeToInternal(OutputStream out, int sourceOffset, int numberToWrite) throws IOException {
        out.write(bytes, sourceOffset, numberToWrite);
    }

    @Override
    void writeToReverse(ByteOutput byteOutput) throws IOException {
        for (int i = length - 1; i >= 0; i--) {
            byteOutput.write(bytes[i]);
        }
    }

    @Override
    public ByteString substring(int beginIndex, int endIndex) {
        int len = checkRange(beginIndex, endIndex, length);
        return len == 0 ? ByteString.EMPTY : ByteString.copyFrom(bytes, beginIndex, len);
    }

    @Override
    public ByteString substringNoCopy(int beginIndex, int endIndex) {
        return substring(beginIndex, endIndex);
    }

    @Override
    protected void copyToInternal(byte[] target, int sourceOffset, int targetOffset, int numberToCopy) {
        System.arraycopy(bytes, sourceOffset, target, targetOffset, numberToCopy);
    }

    @Override
    public void copyTo(ByteBuffer target) {
        target.put(bytes, 0, length);
    }

    @Override
    public ByteBuffer asReadOnlyByteBuffer() {
        return ByteBuffer.wrap(bytes, 0, length).slice().asReadOnlyBuffer();
    }

    @Override
    public List<ByteBuffer> asReadOnlyByteBufferList() {
        return Collections.singletonList(asReadOnlyByteBuffer());
    }

    @Override
    protected String toStringInternal(Charset charset) {
        return new String(bytes, 0, length, charset);
    }

    @Override
    protected boolean equalsInternal(ByteString other) {
        if (size() != other.size()) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (bytes[i] != other.byteAt(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public InputStream newInput() {
        return new ByteArrayInputStream(bytes, 0, length);
    }

    @Override
    public CodedInputStream newCodedInput() {
        return CodedInputStream.newInstance(bytes, 0, length, /* bufferIsImmutable= */ false);
    }

    @Override
    protected int partialHash(int h, int offset, int length) {
        return Internal.partialHash(h, bytes, offset, length);
    }

    @Override
    protected int getTreeDepth() {
        return 0;
    }

    @Override
    protected boolean isBalanced() {
        return true;
    }
}
