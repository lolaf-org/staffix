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
package org.lolaf.staffix.codec.serde;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

/**
 * UUID serde using JDK impl.
 *
 * <p>Also owns the conversion between a {@link UUID} and its canonical textual form - the lowercase
 * 36-character hyphenated representation {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx} of the 16 binary bytes in
 * big-endian (network) byte order. That conversion is stateless and exposed as static methods, inherited by
 * {@link UUIDThreadLocalSerde} which only replaces the {@link UUID} instance a decoded value lands in.
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UUIDSerde implements SerDe<UUID> {

    /**
     * The number of characters of the canonical textual form.
     */
    public static final int TEXT_LENGTH = 36;

    /**
     * The number of binary bytes of a UUID.
     */
    static final int BINARY_LENGTH = 16;

    private static final UUIDSerde INSTANCE = new UUIDSerde();

    private static final byte[] HEX_DIGITS = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    // ASCII -> nibble value, -1 for non hex characters. Accepts both lower and upper case.
    private static final byte[] HEX_VALUES = new byte[128];

    // positions of the four '-' separators in the 36-character textual form
    private static final int[] HYPHEN_POSITIONS = {8, 13, 18, 23};

    private static final byte SEPARATOR = (byte) '-';

    static {
        Arrays.fill(HEX_VALUES, (byte) -1);
        for (int i = 0; i < HEX_DIGITS.length; i++) {
            HEX_VALUES[HEX_DIGITS[i]] = (byte) i;
        }
        for (char c = 'A'; c <= 'F'; c++) {
            HEX_VALUES[c] = (byte) (10 + c - 'A');
        }
    }

    public static UUIDSerde instance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------------------------------
    // Canonical text <-> UUID (stateless)
    // ------------------------------------------------------------------------------------------

    /**
     * Writes the canonical 36 ASCII characters of {@code uuid} into {@code dst} at its current position,
     * advancing the position by {@value #TEXT_LENGTH}.
     *
     * @return the number of bytes written ({@value #TEXT_LENGTH})
     */
    public static int format(UUID uuid, ByteBuffer dst) {
        if (dst.remaining() < TEXT_LENGTH) {
            throw new IllegalFieldValueException("buffer has " + dst.remaining()
                    + " bytes remaining, cannot hold " + TEXT_LENGTH);
        }
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < BINARY_LENGTH; i++) {
            if (isSeparatorIndex(i)) {
                dst.put(SEPARATOR);
            }
            int v = (int) ((i < 8 ? msb >>> (56 - 8 * i) : lsb >>> (56 - 8 * (i - 8))) & 0xFF);
            dst.put(HEX_DIGITS[v >>> 4]);
            dst.put(HEX_DIGITS[v & 0x0F]);
        }
        return TEXT_LENGTH;
    }

    /**
     * Parses the 36 ASCII characters at {@code offset} into a JDK {@link UUID}.
     */
    public static UUID parse(byte[] src, int offset) {
        ensureCapacity(src, offset, TEXT_LENGTH);
        ensureHyphenPositions(src, offset);
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < BINARY_LENGTH; i++) {
            if (isSeparatorIndex(i)) {
                offset++; // skip the '-'
            }
            int b = (hexValue(src[offset++]) << 4) | hexValue(src[offset++]);
            if (i < 8) {
                msb = (msb << 8) | b;
            } else {
                lsb = (lsb << 8) | b;
            }
        }
        return new UUID(msb, lsb);
    }

    // the four helpers below are package private rather than private: UUIDThreadLocalSerde extends this class
    // and parses with them too

    static boolean isSeparatorIndex(int i) {
        return i == 4 || i == 6 || i == 8 || i == 10;
    }

    static void ensureHyphenPositions(byte[] src, int srcOffset) {
        for (int pos : HYPHEN_POSITIONS) {
            if (src[srcOffset + pos] != '-') {
                throw new IllegalFieldValueException("invalid UUID: missing '-' at position " + pos);
            }
        }
    }

    static int hexValue(byte ascii) {
        int idx = ascii & 0xFF;
        int v = idx < HEX_VALUES.length ? HEX_VALUES[idx] : -1;
        if (v < 0) {
            throw new IllegalFieldValueException((char) ascii + " is not a hex digit");
        }
        return v;
    }

    static void ensureCapacity(byte[] buffer, int offset, int length) {
        if (buffer == null) {
            throw new IllegalFieldValueException("buffer must not be null");
        }
        if (offset < 0 || offset + length > buffer.length) {
            throw new IllegalFieldValueException("buffer of length " + buffer.length
                    + " cannot hold " + length + " bytes at offset " + offset);
        }
    }

    @Override
    public void serialize(ByteBuffer out, UUID value) {
        format(value, out);
    }

    @Override
    public UUID deserialize(DeserializationContext serdeContext) {
        return parse(serdeContext.getDeserializationBuffer(), serdeContext.getStartOffset());
    }
}
