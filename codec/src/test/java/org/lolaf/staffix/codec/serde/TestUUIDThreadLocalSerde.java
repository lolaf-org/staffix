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
import org.lolaf.staffix.api.ids.MsbLsb;
import org.lolaf.staffix.api.ids.UUIDv7;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestUUIDThreadLocalSerde {

    private static final UUID UUID_A = UUID.fromString("0190b3c0-9c7a-7b3e-8f21-123456789abc");
    private static final UUID UUID_B = UUID.fromString("0190b3c0-9c7a-7b3e-8f21-cba987654321");

    private final UUIDThreadLocalSerde serde = UUIDThreadLocalSerde.instance();

    private static SerDe.DeserializationContext ctxFor(UUID uuid) {
        return new TestingDeserializationContext().setup(uuid.toString().getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void testInstanceIsSingleton() {
        assertThat(UUIDThreadLocalSerde.instance()).isSameAs(UUIDThreadLocalSerde.instance());
    }

    @Test
    void testIsAUuidSerde() {
        // serialization and the text conversion are inherited, only the decoded instance differs
        assertThat(serde).isInstanceOf(UUIDSerde.class);
    }

    @Test
    void testParseToMsbLsbRoundTrip() {
        UUIDv7 generator = new UUIDv7();
        byte[] big = new byte[80];
        int offset = 9;
        MsbLsb dst = new MsbLsb();
        for (int i = 0; i < 1_000_000; i++) {
            UUID expected = generator.generateUuid();
            byte[] ascii = expected.toString().getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(ascii, 0, big, offset, UUIDSerde.TEXT_LENGTH);

            UUIDThreadLocalSerde.parse(dst, big, offset);

            assertThat(dst.getMsb()).isEqualTo(expected.getMostSignificantBits());
            assertThat(dst.getLsb()).isEqualTo(expected.getLeastSignificantBits());
        }
    }

    @Test
    void testParseToMsbLsbRejectsInvalidInput() {
        MsbLsb dst = new MsbLsb();
        byte[] valid = UUID_A.toString().getBytes(StandardCharsets.US_ASCII);

        byte[] badHex = valid.clone();
        badHex[0] = 'z';
        assertThatThrownBy(() -> UUIDThreadLocalSerde.parse(dst, badHex, 0))
                .isInstanceOf(IllegalFieldValueException.class);

        byte[] badHyphen = valid.clone();
        badHyphen[13] = 'a';
        assertThatThrownBy(() -> UUIDThreadLocalSerde.parse(dst, badHyphen, 0))
                .isInstanceOf(IllegalFieldValueException.class);

        byte[] tooShort = new byte[UUIDSerde.TEXT_LENGTH - 1];
        assertThatThrownBy(() -> UUIDThreadLocalSerde.parse(dst, tooShort, 0))
                .isInstanceOf(IllegalFieldValueException.class);
    }

    @Test
    void testSerializeWritesCanonicalAscii() {
        ByteBuffer out = ByteBuffer.allocate(UUIDSerde.TEXT_LENGTH);

        serde.serialize(out, UUID_A);

        assertThat(out.position()).isEqualTo(UUIDSerde.TEXT_LENGTH);
        assertThat(new String(out.array(), StandardCharsets.US_ASCII)).isEqualTo(UUID_A.toString());
    }

    @Test
    void testDeserializeReadsCanonicalAscii() {
        assertThat(serde.deserialize(ctxFor(UUID_A))).isEqualTo(UUID_A);
    }

    @Test
    void testDeserializeHonoursStartOffset() {
        int offset = 7;
        byte[] buffer = new byte[offset + UUIDSerde.TEXT_LENGTH + 5];
        byte[] ascii = UUID_A.toString().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, buffer, offset, ascii.length);

        SerDe.DeserializationContext ctx = new TestingDeserializationContext().setup(buffer, offset, UUIDSerde.TEXT_LENGTH);

        assertThat(serde.deserialize(ctx)).isEqualTo(UUID_A);
    }

    @Test
    void testSerializeDeserializeRoundTrip() {
        for (int i = 0; i < 1_000_000; i++) {
            UUID expected = UUIDv7.instance().generateUuid();

            ByteBuffer out = ByteBuffer.allocate(UUIDSerde.TEXT_LENGTH);
            serde.serialize(out, expected);

            assertThat(serde.deserialize(new TestingDeserializationContext().setup(out.array()))).isEqualTo(expected);
        }
    }

    @Test
    void testDeserializeReusesSameInstanceOnSameThread() {
        UUID first = serde.deserialize(ctxFor(UUID_A));
        assertThat(first).isEqualTo(UUID_A);

        UUID second = serde.deserialize(ctxFor(UUID_B));
        assertThat(second).isEqualTo(UUID_B);

        // THREAD_LOCAL contract: the returned UUID is a single reused instance per thread, so the earlier
        // reference now reflects the latest decoded value.
        assertThat(second).isSameAs(first);
        assertThat(first).isEqualTo(UUID_B);
    }

    @Test
    void testDeserializeUsesDistinctInstancePerThread() throws Exception {
        byte[] bytes = UUID_A.toString().getBytes(StandardCharsets.US_ASCII);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> f1 = pool.submit(() -> {
                barrier.await();
                return serde.deserialize(new TestingDeserializationContext().setup(bytes.clone()));
            });
            Future<UUID> f2 = pool.submit(() -> {
                barrier.await();
                return serde.deserialize(new TestingDeserializationContext().setup(bytes.clone()));
            });

            UUID a = f1.get();
            UUID b = f2.get();

            assertThat(a).isEqualTo(UUID_A);
            assertThat(b).isEqualTo(UUID_A);
            // each thread owns its own reused UUID instance
            assertThat(a).isNotSameAs(b);
        } finally {
            pool.shutdownNow();
        }
    }
}
