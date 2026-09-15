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

import lombok.*;

/**
 * Fixed-point decimal value encoded as an unscaled {@code long} and a signed
 * {@code byte} scale, where the numeric value is
 * {@code unscaledValue * 10^(-scale)} (same convention as
 * {@link java.math.BigDecimal}).
 *
 * <p>Enforces the FIX float 15-significant-digit precision cap. Per the spec,
 * trailing zeros are not significant — so an input like
 * {@code (10^15, 0)} (which would otherwise violate the raw magnitude bound) is
 * normalized on construction by stripping trailing zeros from the unscaled value
 * into a smaller scale, yielding {@code (10^14, -1)}. Inputs whose stripped form
 * still has {@code |unscaled| >= 10^15} (i.e. &gt; 15 significant digits) are rejected.
 * Leading zeros implied by a large positive {@code scale} (e.g. {@code (1, 5)}
 * &rarr; {@code "0.00001"}) are likewise not significant.
 */
public interface DecimalFloat {

    /**
     * Exclusive upper bound on {@code |unscaledValue|} after trailing-zero normalization: 10^15.
     */
    long MAX_UNSCALED_MAGNITUDE = (long) Math.pow(10, 15);

    /**
     * Creates a {@code DecimalFloat} whose numeric value is
     * {@code unscaledValue × 10^(-scale)}.
     *
     * <p>Trailing zeros are not significant (FIX Reading A): if the raw magnitude
     * exceeds {@link #MAX_UNSCALED_MAGNITUDE}, trailing zeros are folded into the
     * scale before the magnitude check is applied (e.g.
     * {@code of(1_000_000_000_000_000L, 0)} is stored as
     * {@code (100_000_000_000_000L, -1)}).
     *
     * @param unscaledValue the integer mantissa; after trailing-zero normalization
     *                      {@code |unscaledValue|} must be strictly less than
     *                      {@link #MAX_UNSCALED_MAGNITUDE} (10^15)
     * @param scale         the number of decimal places; must fit in a signed byte
     *                      ({@code -128 ≤ scale ≤ 127}) after normalization
     * @return a {@code DecimalFloat} representing the given value
     * @throws IllegalFieldValueException if the normalized {@code |unscaledValue|}
     *                                    is ≥ {@link #MAX_UNSCALED_MAGNITUDE}, or
     *                                    if {@code scale} is outside {@code [-128, 127]}
     */
    static DecimalFloat of(long unscaledValue, byte scale) {
        return validate(new DecimalFloatImpl(unscaledValue, scale));
    }

    /**
     * Normalizes and validates that a {@code DecimalFloat} satisfies the FIX float
     * constraints: {@code |unscaledValue| < }{@link #MAX_UNSCALED_MAGNITUDE} and
     * {@code scale} within {@code [-128, 127]}.
     *
     * <p>If the raw {@code |unscaledValue|} exceeds {@link #MAX_UNSCALED_MAGNITUDE},
     * trailing zeros are folded into the scale before the magnitude check is applied.
     * The returned instance reflects the normalized form and may differ from the input.
     *
     * @param value the value to normalize and validate
     * @return the normalized {@code DecimalFloat}, which may be a different instance
     * than {@code value} if trailing-zero folding was applied
     * @throws IllegalFieldValueException if the normalized {@code |unscaledValue| ≥ 10^15}
     *                                    or {@code scale} is outside {@code [-128, 127]}
     */
    static DecimalFloat validate(DecimalFloat value) throws IllegalFieldValueException {
        // Reading A: trailing zeros in unscaledValue are not significant. When the raw
        // magnitude would exceed the 15-sig-digit cap, fold trailing zeros into scale
        // until either the magnitude fits or a non-zero trailing digit proves the value
        // truly has >15 sig digits.
        long unscaledValue = value.getUnscaledValue();
        byte scale = value.getScale();
        if (unscaledValue >= MAX_UNSCALED_MAGNITUDE || unscaledValue <= -MAX_UNSCALED_MAGNITUDE) {
            while ((unscaledValue >= MAX_UNSCALED_MAGNITUDE || unscaledValue <= -MAX_UNSCALED_MAGNITUDE)
                    && (unscaledValue % 10) == 0) {
                unscaledValue /= 10;
                scale--;
            }
            if (value instanceof DecimalFloatImpl) {
                ((DecimalFloatImpl) value).update(unscaledValue, scale);
            } else {
                value = new DecimalFloatImpl(unscaledValue, scale);
            }
        }
        if (unscaledValue <= -MAX_UNSCALED_MAGNITUDE || unscaledValue >= MAX_UNSCALED_MAGNITUDE) {
            throw new IllegalFieldValueException("unscaledValue exceeds 15 significant digits: " + unscaledValue);
        }
        return value;
    }

    /**
     * Returns the integer mantissa of this decimal. Combined with {@link #getScale()}, the numeric value is
     * {@code unscaledValue * 10^(-scale)}.
     *
     * <p>After construction/normalization, {@code |unscaledValue|} is strictly less than
     * {@link #MAX_UNSCALED_MAGNITUDE} (10^15), i.e. fits in at most 15 significant digits.
     *
     * @return the signed unscaled mantissa
     */
    long getUnscaledValue();

    /**
     * Returns the decimal scale: the number of digits to the right of the decimal point, or, equivalently, the
     * negated power of ten applied to {@link #getUnscaledValue()} to recover the numeric value
     * ({@code value = unscaledValue * 10^(-scale)}). A negative scale denotes trailing zeros (e.g. scale {@code -3}
     * means the value is the mantissa multiplied by {@code 1000}).
     *
     * <p>Always within signed-byte range {@code [-128, 127]}; enforced at construction.
     *
     * @return the signed scale
     */
    byte getScale();

    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor(access = AccessLevel.PROTECTED)
    @ToString
    @EqualsAndHashCode
    class DecimalFloatImpl implements DecimalFloat {

        private long unscaledValue;
        private byte scale;

        protected DecimalFloat update(long unscaled, byte scale) {
            this.unscaledValue = unscaled;
            this.scale = scale;
            return this;
        }
    }
}