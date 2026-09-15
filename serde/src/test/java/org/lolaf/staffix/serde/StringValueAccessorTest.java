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
package org.lolaf.staffix.serde;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StringValueAccessorTest {

    private static final List<String> FIX_ASCII_VALUES =
            List.of("", "D", "35=D", "ClOrdID-000123456789", "0.00010001", "AAPL/USD", "1234567890");

    @Test
    void copyingAccessorReturnsIso8859Bytes() {
        StringBaseSerde.CopyingStringValueAccessor copying = new StringBaseSerde.CopyingStringValueAccessor();
        for (String value : FIX_ASCII_VALUES) {
            assertThat(copying.value(value)).isEqualTo(value.getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    @Test
    void copyingAccessorIsByteIdenticalToUnsafeForFixAsciiValues() {
        // The serde test JVM runs with --add-opens java.base/jdk.internal.misc=ALL-UNNAMED, so the zero-copy
        // Unsafe accessor is available here and can be compared against the copying fallback.
        StringBaseSerde.UnsafeStringValueAccessor unsafe = new StringBaseSerde.UnsafeStringValueAccessor();
        StringBaseSerde.CopyingStringValueAccessor copying = new StringBaseSerde.CopyingStringValueAccessor();
        for (String value : FIX_ASCII_VALUES) {
            assertThat(copying.value(value))
                    .as("value=%s", value)
                    .isEqualTo(unsafe.value(value));
        }
    }
}
