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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.ids.UUIDv7;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestUUIDSerde {

    private static final UUID UUID_A = UUID.fromString("0190b3c0-9c7a-7b3e-8f21-123456789abc");

    private final UUIDv7 generator = new UUIDv7();

    private static long readLongBE(byte[] src, int offset) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (src[offset + i] & 0xFFL);
        }
        return v;
    }

    @Test
    void testInstanceIsSingleton() {
        assertThat(UUIDSerde.instance()).isSameAs(UUIDSerde.instance());
    }

    @Test
    void testSerializeWritesCanonicalAscii() {
        ByteBuffer out = ByteBuffer.allocate(UUIDSerde.TEXT_LENGTH);

        UUIDSerde.instance().serialize(out, UUID_A);

        assertThat(out.position()).isEqualTo(UUIDSerde.TEXT_LENGTH);
        assertThat(new String(out.array(), StandardCharsets.US_ASCII)).isEqualTo(UUID_A.toString());
    }

    @Test
    void testDeserializeReadsCanonicalAscii() {
        SerDe.DeserializationContext ctx =
                new TestingDeserializationContext().setup(UUID_A.toString().getBytes(StandardCharsets.US_ASCII));

        assertThat(UUIDSerde.instance().deserialize(ctx)).isEqualTo(UUID_A);
    }

    @Test
    void testDeserializeHonoursStartOffset() {
        int offset = 7;
        byte[] buffer = new byte[offset + UUIDSerde.TEXT_LENGTH + 5];
        byte[] ascii = UUID_A.toString().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, buffer, offset, ascii.length);

        SerDe.DeserializationContext ctx =
                new TestingDeserializationContext().setup(buffer, offset, UUIDSerde.TEXT_LENGTH);

        assertThat(UUIDSerde.instance().deserialize(ctx)).isEqualTo(UUID_A);
    }

    @Test
    void testSerializeDeserializeRoundTrip() {
        UUIDSerde serde = UUIDSerde.instance();
        for (int i = 0; i < 1_000_000; i++) {
            UUID expected = generator.generateUuid();

            ByteBuffer out = ByteBuffer.allocate(UUIDSerde.TEXT_LENGTH);
            serde.serialize(out, expected);

            SerDe.DeserializationContext ctx = new TestingDeserializationContext().setup(out.array());
            assertThat(serde.deserialize(ctx)).isEqualTo(expected);
        }
    }

    @Test
    void testHexLayoutMatchesJavaUuid() {
        // random (non v7) values, so the whole 128-bit range is exercised against the JDK's own rendering
        Random rdm = new Random();
        byte[] binary = new byte[UUIDSerde.BINARY_LENGTH];
        ByteBuffer ascii = ByteBuffer.allocate(UUIDSerde.TEXT_LENGTH);
        for (int i = 0; i < 1_000_000; i++) {
            rdm.nextBytes(binary);
            UUID expected = new UUID(readLongBE(binary, 0), readLongBE(binary, 8));

            ascii.clear();
            UUIDSerde.format(expected, ascii);
            assertThat(new String(ascii.array(), StandardCharsets.US_ASCII)).isEqualTo(expected.toString());

            assertThat(UUIDSerde.parse(ascii.array(), 0)).isEqualTo(expected);
        }
    }

    @Test
    void testFormatWritesAtBufferPosition() {
        ByteBuffer buf = ByteBuffer.allocate(80);
        for (int i = 0; i < 1_000_000; i++) {
            UUID uuid = generator.generateUuid();
            buf.position(13);

            int written = UUIDSerde.format(uuid, buf);

            assertThat(written).isEqualTo(UUIDSerde.TEXT_LENGTH);
            assertThat(buf.position()).isEqualTo(13 + UUIDSerde.TEXT_LENGTH);
            byte[] ascii = new byte[UUIDSerde.TEXT_LENGTH];
            System.arraycopy(buf.array(), 13, ascii, 0, UUIDSerde.TEXT_LENGTH);
            assertThat(new String(ascii, StandardCharsets.US_ASCII)).isEqualTo(uuid.toString());
        }
    }

    @Test
    void testFormatRejectsInsufficientSpace() {
        ByteBuffer tooSmall = ByteBuffer.allocate(UUIDSerde.TEXT_LENGTH - 1);
        assertThatThrownBy(() -> UUIDSerde.format(UUID_A, tooSmall))
                .isInstanceOf(IllegalFieldValueException.class);
    }

    @Test
    void testParseToUuidRoundTrip() {
        byte[] big = new byte[80];
        int offset = 9;
        for (int i = 0; i < 1_000_000; i++) {
            UUID expected = generator.generateUuid();
            byte[] ascii = expected.toString().getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(ascii, 0, big, offset, UUIDSerde.TEXT_LENGTH);

            assertThat(UUIDSerde.parse(big, offset)).isEqualTo(expected);
        }
    }

    @Test
    void testParseToUuidRejectsInvalidInput() {
        byte[] valid = UUID_A.toString().getBytes(StandardCharsets.US_ASCII);

        byte[] badHex = valid.clone();
        badHex[0] = 'z';
        assertThatThrownBy(() -> UUIDSerde.parse(badHex, 0)).isInstanceOf(IllegalFieldValueException.class);

        byte[] badHyphen = valid.clone();
        badHyphen[8] = 'a';
        assertThatThrownBy(() -> UUIDSerde.parse(badHyphen, 0)).isInstanceOf(IllegalFieldValueException.class);

        byte[] tooShort = new byte[UUIDSerde.TEXT_LENGTH - 1];
        assertThatThrownBy(() -> UUIDSerde.parse(tooShort, 0)).isInstanceOf(IllegalFieldValueException.class);

        assertThatThrownBy(() -> UUIDSerde.parse(null, 0)).isInstanceOf(IllegalFieldValueException.class);
        assertThatThrownBy(() -> UUIDSerde.parse(valid, -1)).isInstanceOf(IllegalFieldValueException.class);
    }
}
