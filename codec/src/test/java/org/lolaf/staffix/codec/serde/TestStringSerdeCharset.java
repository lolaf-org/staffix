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
import org.lolaf.staffix.api.serde.Hashing;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSessionId;

import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The string serdes must move bytes, not interpret them.
 *
 * <p>They did not: every one of them converted through the <em>platform default</em> charset, which JDK 18 changed
 * to UTF-8 (JEP 400) and which came from {@code file.encoding} before that. staffix runs on JDK 11 through 25, so
 * the same message decoded to different values depending on the JDK underneath, and on a UTF-8 default every byte
 * above 0x7F became U+FFFD — silent corruption of exactly the data {@link SerDe#CHARSET} exists to preserve.
 *
 * <p>Nothing caught it because every existing test uses ASCII, where all these charsets agree. So these tests use
 * all 256 byte values, which is the only input that tells them apart.
 */
class TestStringSerdeCharset {

    /** Every byte a counterparty can put in a field, including the ones no charset but Latin-1 survives. */
    private static byte[] allByteValues() {
        byte[] bytes = new byte[256];
        for (int i = 0; i < 256; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    private static String allByteValuesAsString() {
        return new String(allByteValues(), SerDe.CHARSET);
    }

    private static Stream<Deserializer> deserializers() {
        return Stream.of(
                new Deserializer("StringSerde", ctx -> StringSerde.instance().deserialize(ctx)),
                new Deserializer("StringCachedSerde",
                        ctx -> StringCachedSerde.instance(mock(FixSessionId.class)).deserialize(ctx)),
                new Deserializer("StringThreadLocalSerde", ctx -> StringThreadLocalSerde.instance().deserialize(ctx)));
    }

    @Test
    void everyDeserializerReadsAllByteValuesBackUnchanged() {
        byte[] wire = allByteValues();
        String expected = allByteValuesAsString();

        deserializers().forEach(deserializer -> {
            SerDe.DeserializationContext ctx = new TestingDeserializationContext().setup(wire);
            String value = deserializer.deserialize(ctx);

            assertThat(value)
                    .as("%s must read every byte value back unchanged", deserializer.name)
                    .isEqualTo(expected);
            assertThat(value.getBytes(SerDe.CHARSET))
                    .as("%s must round-trip the wire bytes exactly", deserializer.name)
                    .isEqualTo(wire);
        });
    }

    /**
     * The asymmetry this started from: {@link StringSerde} extends {@link StringBaseSerde}, so it has to read back
     * what its own parent writes.
     */
    @Test
    void theSerializerAndTheDeserializerAgree() {
        String value = allByteValuesAsString();
        byte[] serialized = StringSerde.instance().serialize(value);

        assertThat(serialized).isEqualTo(allByteValues());

        deserializers().forEach(deserializer -> {
            SerDe.DeserializationContext ctx = new TestingDeserializationContext().setup(serialized);
            assertThat(deserializer.deserialize(ctx))
                    .as("%s must read back what StringBaseSerde wrote", deserializer.name)
                    .isEqualTo(value);
        });
    }

    /**
     * {@code Hashing.hash(String)} and {@code Hashing.hash(byte[], int, int)} exist to be compared with each other,
     * a configured value against a wire value, without materialising a String on the message path. They can only
     * agree if the String is measured in the charset the wire is written in.
     */
    @Test
    void hashingAStringMatchesHashingItsWireBytes() {
        byte[] wire = allByteValues();

        assertThat(Hashing.hash(allByteValuesAsString()))
                .as("a value's hash must not depend on whether it arrived as a String or as bytes")
                .isEqualTo(Hashing.hash(wire, 0, wire.length));
    }

    /** The high bytes are the whole point, so prove they are not incidentally passing on the ASCII half. */
    @Test
    void theHighBytesAreWhatDistinguishesTheCharsets() {
        byte[] highBytes = new byte[128];
        for (int i = 0; i < 128; i++) {
            highBytes[i] = (byte) (0x80 + i);
        }
        String value = new String(highBytes, SerDe.CHARSET);

        assertThat(value.chars().allMatch(c -> c >= 0x80 && c <= 0xFF))
                .as("each high byte must map to exactly one char in 0x80..0xFF, not to U+FFFD")
                .isTrue();

        SerDe.DeserializationContext ctx = new TestingDeserializationContext().setup(highBytes);
        assertThat(StringSerde.instance().deserialize(ctx)).isEqualTo(value);
        assertThat(Hashing.hash(value)).isEqualTo(Hashing.hash(highBytes, 0, highBytes.length));
    }

    private static final class Deserializer {

        private final String name;
        private final Function<SerDe.DeserializationContext, String> deserialize;

        private Deserializer(String name, Function<SerDe.DeserializationContext, String> deserialize) {
            this.name = name;
            this.deserialize = deserialize;
        }

        private String deserialize(SerDe.DeserializationContext ctx) {
            return deserialize.apply(ctx);
        }
    }
}
