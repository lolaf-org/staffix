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


import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.unsafe.UnsafeOperations;
import org.lolaf.ringos.unsafe.UnsafeOperationsApi;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;

import java.nio.ByteBuffer;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Slf4j
abstract class StringBaseSerde implements SerDe<String> {


    // Reads String.value (the internal byte[]): zero-copy via Unsafe when available, otherwise a copying
    // accessor that needs no JVM flags. Chosen once at class load, so getStringBytes never fails just because
    // Unsafe is missing.
    private static final StringValueAccessor VALUE_ACCESSOR = selectValueAccessor();

    private final BiConsumer<ByteBuffer, String> serializer;
    private final Function<String, byte[]> serializerToByteArray;

    protected StringBaseSerde() {
        serializer = (out, value) -> out.put(getStringBytes(value));
        serializerToByteArray = this::getStringBytes;
    }

    private static StringValueAccessor selectValueAccessor() {
        if (UnsafeOperationsApi.isAvailable()) {
            try {
                return new UnsafeStringValueAccessor();
            } catch (RuntimeException ex) {
                log.warn("Unable to set up Unsafe String value access; using a copying byte[] accessor", ex);
            }
        }
        log.info("UnsafeOperations is not available; String serialization will use a copying byte[] accessor. "
                + "Add --add-opens java.base/jdk.internal.misc=ALL-UNNAMED for the zero-copy Unsafe fast path.");
        return new CopyingStringValueAccessor();
    }

    private static void ensureStringNotNull(String value) {
        if (value == null) {
            throw new IllegalFieldValueException("Cannot serialize null string");
        }
    }

    public byte[] getStringBytes(String value) {
        return VALUE_ACCESSOR.value(value);
    }

    @Override
    public void serialize(ByteBuffer out, String value) {
        ensureStringNotNull(value);
        serializer.accept(out, value);
    }

    public byte[] serialize(String value) {
        ensureStringNotNull(value);
        return serializerToByteArray.apply(value);
    }

    interface StringValueAccessor {

        /**
         * @return the bytes of {@code s}. Depending on the implementation this may be the string's internal array
         * (zero-copy, must be treated as read-only) or a fresh copy.
         */
        byte[] value(String s);
    }

    static final class CopyingStringValueAccessor implements StringValueAccessor {

        @Override
        public byte[] value(String s) {
            return s.getBytes(SerDe.CHARSET);
        }
    }

    static final class UnsafeStringValueAccessor implements StringValueAccessor {

        private static final UnsafeOperations UNSAFE_OPERATIONS = UnsafeOperationsApi.get();

        private final long stringValueFieldOffset;

        UnsafeStringValueAccessor() {
            this.stringValueFieldOffset = UNSAFE_OPERATIONS.objectFieldOffset(String.class, "value");
            if (stringValueFieldOffset == UnsafeOperations.UNKNOWN_FIELD_OFFSET) {
                throw new IllegalStateException("Unable to find String value field offset");
            }
        }

        @Override
        public byte[] value(String s) {
            return UNSAFE_OPERATIONS.getReference(s, stringValueFieldOffset);
        }
    }

}