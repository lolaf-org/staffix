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

import org.lolaf.ringos.threading.FastThreadLocal;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;

import java.math.BigDecimal;
import java.nio.ByteBuffer;

/**
 * Reads and writes a {@link BigDecimal}, for applications that already work in one.
 *
 * <p>Allocates - a BigDecimal is an object and there is no way round it. On the message path prefer
 * {@link DecimalFloatSerde}, which carries the same digits without the allocation.
 */
public class BigDecimalSerde implements SerDe<BigDecimal> {

    private static final int MAX_SIGNIFICANT_DIGITS = 15;

    private static final BigDecimalSerde INSTANCE = new BigDecimalSerde();
    private final StringSerde stringSerde;
    private final FastThreadLocal<CharArraysCache> charArraysCache;

    private BigDecimalSerde() {
        stringSerde = StringSerde.instance();
        charArraysCache = FastThreadLocal.withInitial(() -> new CharArraysCache(24));
    }

    public static BigDecimalSerde instance() {
        return INSTANCE;
    }

    @Override
    public void serialize(ByteBuffer out, BigDecimal value) {
        // stripTrailingZeros is very costly
        if (value.stripTrailingZeros().precision() > MAX_SIGNIFICANT_DIGITS) {
            throw new IllegalFieldValueException(
                    "FIX float: value exceeds 15 significant digits: " + value.toPlainString());
        }
        stringSerde.serialize(out, value.toPlainString());
    }

    @Override
    public BigDecimal deserialize(DeserializationContext serdeContext) {
        int length = serdeContext.getLength();
        int startOffset = serdeContext.getStartOffset();
        if (length == 0) {
            throw new IllegalFieldValueException("Unable to parse number: <empty>");
        }
        char[] cachedChars = charArraysCache.get().forSize(length);
        byte[] toCopy = serdeContext.getDeserializationBuffer();
        for (int i = 0; i < length; i++) {
            byte decimalPart = toCopy[startOffset + i];
            if (decimalPart == NumberSerdeUtils.EXPONENT_CHAR || decimalPart == NumberSerdeUtils.EXPONENT_UPPER_CHAR) {
                throw new IllegalFieldValueException("Unable to parse number: " + serdeContext.contentToString());
            }
            cachedChars[i] = (char) decimalPart;
        }

        BigDecimal parsed;
        try {
            parsed = new BigDecimal(cachedChars, 0, length);
        } catch (Exception ex) {
            throw new IllegalFieldValueException("Unable to parse number: " + serdeContext.contentToString(), ex);
        }
        if (length > MAX_SIGNIFICANT_DIGITS && parsed.stripTrailingZeros().precision() > MAX_SIGNIFICANT_DIGITS) {
            throw new IllegalFieldValueException(
                    "FIX float: value exceeds 15 significant digits: " + serdeContext.contentToString() + " (" + parsed.stripTrailingZeros().precision() + ")");
        }
        return parsed;
    }
}
