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
package org.lolaf.staffix.api.serde;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Reads and writes one FIX field type to and from the wire.
 *
 * <p>Everything here works on a {@link ByteBuffer} and an offset rather than returning bytes, because the
 * message path allocates nothing per field: the length-specialised implementations parse and format in place.
 *
 * <p>{@link DeserializationContext} is the field's window into the read buffer, handed in rather than
 * constructed, and is valid only for the call it is passed to.
 *
 * @param <T> the Java type the field carries
 */
public interface SerDe<T> {

    /**
     * The charset every FIX field value is read and written with.
     * <p>
     * ISO-8859-1 rather than the US-ASCII the specification describes, for two reasons. It is the only total,
     * lossless byte-to-char map, so a counterparty's bytes round-trip exactly - including the out-of-spec UTF-8
     * some of them put in ordinary String fields, which US-ASCII would turn into {@code ?} on the way out and
     * U+FFFD on the way back. And a Latin-1 String is backed by the very bytes that came off the wire, which is
     * what lets the string serdes decode by copying an array rather than running a decoder, and lets the
     * thread-local one reuse a String's backing array and allocate nothing at all.
     * <p>
     * Never the platform default: it is UTF-8 from JDK 18 (JEP 400) and {@code file.encoding} before that, so a
     * message would decode differently depending on which JDK the engine happens to run on.
     */
    Charset CHARSET = StandardCharsets.ISO_8859_1;

    /**
     * Serialize a field
     *
     * @param out   the serialization output
     * @param value the value to serialize
     */
    void serialize(ByteBuffer out, T value);

    /**
     * Deserialize a field for a given filed context object
     *
     * @param serdeContext the deserializer context
     * @return the deserialized field value instance
     */
    T deserialize(DeserializationContext serdeContext);

    interface DeserializationContext {

        /**
         * The deserialization bytes buffer
         */
        byte[] getDeserializationBuffer();

        /**
         * The start offset to read data from the deserialization buffer
         */
        int getStartOffset();

        /**
         * The length of data to read data from the deserialization buffer start offset
         */
        int getLength();

        DeserializationContext setup(byte[] serdeBuffer, int startOffset, int length);

        DeserializationContext setup(byte[] serdeBuffer);

        void clean();

        String contentToString();
    }

}
