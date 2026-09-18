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

import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.ringos.threading.FastThreadLocal;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;

import static java.lang.Double.doubleToRawLongBits;
import static java.lang.Long.numberOfLeadingZeros;
import static java.lang.Math.multiplyHigh;
import static org.lolaf.staffix.codec.serde.MathUtils.*;

/**
 * Renders a double as the shortest decimal that reads back as the same double, using Schubfach.
 *
 * <p>Not {@link Double#toString} because that allocates a String and may produce scientific notation, which is
 * not a FIX float. The algorithm is Giulietti's, referenced in the source; it is here rather than taken from
 * the JDK because the JDK's is not reachable and does not write into a caller's buffer.
 */
public class DoubleToDecimal {
    /*
     * For full details about this code see the following references:
     *
     * [1] Giulietti, "The Schubfach way to render doubles",
     *     https://drive.google.com/file/d/1gp5xv4CAa78SVgCeWfGqqI4FfYYYuNFb
     *
     * [2] IEEE Computer Society, "IEEE Standard for Floating-Point Arithmetic"
     *
     * [3] Bouvier & Zimmermann, "Division-Free Binary-to-Decimal Conversion"
     *
     * Divisions are avoided altogether for the benefit of those architectures
     * that do not provide specific machine instructions or where they are slow.
     * This is discussed in section 10 of [1].
     */

    // see https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/jdk/internal/math/DoubleToDecimal.java for original code

    private static final int MAX_CACHED_BYTE_ARRAY_SIZE = Integer.parseInt(System.getProperty("org.lolaf.staffix.codec.serde.DoubleToDecimal.cached.bytes.array.max.size", "64"));
    private static final int INITIAL_CACHED_BYTE_ARRAY_SIZE = Integer.parseInt(System.getProperty("org.lolaf.staffix.codec.serde.DoubleToDecimal.cached.bytes.array.initial.size", "32"));

    private static final FastThreadLocal<ByteArraysCache> BYTE_ARRAYS_CACHE_FAST_THREAD_LOCAL = FastThreadLocal.withInitial(() -> new ByteArraysCache(MAX_CACHED_BYTE_ARRAY_SIZE));

    private static final int P = 53; // see java.lang.Double.PRECISION

    /* Exponent width in bits. */
    private static final int W = (Double.SIZE - 1) - (P - 1);

    /* Minimum value of the exponent: -(2^(W-1)) - P + 3. */
    private static final int Q_MIN = (-1 << (W - 1)) - P + 3;

    /* Minimum value of the significand of a normal value: 2^(P-1). */
    private static final long C_MIN = 1L << (P - 1);

    /*
     * Threshold to detect tiny values, as in section 8.2.1 of [1].
     *      C_TINY = ceil(2^(-Q_MIN) 10^(K_MIN+1))
     */
    private static final long C_TINY = 3;

    /*
     * H is as in section 8.1 of [1].
     *      H = max{e : 10^(e-2) <= 2^P}
     */
    private static final int H = 17;

    /* Mask to extract the biased exponent. */
    private static final int BQ_MASK = (1 << W) - 1;

    /* Mask to extract the fraction bits. */
    private static final long T_MASK = (1L << (P - 1)) - 1;

    /* Used in rop(). */
    private static final long MASK_63 = (1L << 63) - 1;

    /* Used for left-to-right digit extraction (algorithm 1 in [3], k = 8, n = 28). */
    private static final int MASK_28 = (1 << 28) - 1;

    /*
     * Pack the type tag in bits 16+ so the size in the low 16 bits can represent
     * the full plain-decimal length (up to MAX_CHARS).
     */
    private static final int NON_SPECIAL = 0 << 16;
    private static final int PLUS_ZERO = 1 << 16;
    private static final int MINUS_ZERO = 2 << 16;
    private static final int PLUS_INF = 3 << 16;
    private static final int MINUS_INF = 4 << 16;
    private static final int NAN = 5 << 16;

    private static final byte[] ZERO_BYTES = "0".getBytes(SerDe.CHARSET);

    private DoubleToDecimal() {

    }

    /**
     * Encodes {@code v} as the FIX float plain-decimal form (at most 15 significant
     * digits, banker's-rounded, no scientific notation) and returns an exactly-sized
     * byte array borrowed from {@code cache}. Both {@code 0.0} and {@code -0.0}
     * encode as {@code "0"}.
     *
     * <p>Starts with a @{@link #INITIAL_CACHED_BYTE_ARRAY_SIZE}-byte buffer and doubles on overflow until the value fits.
     *
     * @throws IllegalFieldValueException if {@code v} is {@code NaN} or
     *                                    {@code ±Infinity} — these have no legal FIX wire representation
     *                                    (spec alphabet is digits, {@code '-'}, {@code '.'} only).
     */
    public static byte[] toDecimal(double v) throws IllegalFieldValueException {
        ByteArraysCache cache = BYTE_ARRAYS_CACHE_FAST_THREAD_LOCAL.get();
        byte[] dst = cache.forSize(INITIAL_CACHED_BYTE_ARRAY_SIZE);
        while (true) {
            try {
                int pair = toDecimal(dst, v);
                int type = pair & 0xFFFF0000;
                int size;
                switch (type) {
                    case NON_SPECIAL:
                        size = pair & 0xFFFF;
                        break;
                    case PLUS_ZERO:
                    case MINUS_ZERO:
                        System.arraycopy(ZERO_BYTES, 0, dst, 0, ZERO_BYTES.length);
                        size = ZERO_BYTES.length;
                        break;
                    case PLUS_INF:
                    case MINUS_INF:
                        throw new IllegalFieldValueException("FIX float: Infinity is not a legal wire value");
                    default:
                        throw new IllegalFieldValueException("FIX float: NaN is not a legal wire value");
                }
                if (size == dst.length) {
                    return dst;
                }
                byte[] result = cache.forSize(size);
                System.arraycopy(dst, 0, result, 0, size);
                return result;
            } catch (ArrayIndexOutOfBoundsException e) {
                int newLength = dst.length * 2;
                if (newLength > 512) {
                    throw new IllegalFieldValueException("FIX float: Unable to serialize number, abnormal situation");
                }
                dst = cache.forSize(newLength);
            }
        }
    }

    /*
     * Returns size in the lower byte, type in the high byte, where type is
     *     PLUS_ZERO       iff v is 0.0
     *     MINUS_ZERO      iff v is -0.0
     *     PLUS_INF        iff v is POSITIVE_INFINITY
     *     MINUS_INF       iff v is NEGATIVE_INFINITY
     *     NAN             iff v is NaN
     *     otherwise NON_SPECIAL
     */
    private static int toDecimal(byte[] str, double v) throws IllegalFieldValueException {
        /*
         * For full details see references [2] and [1].
         *
         * For finite v != 0, determine integers c and q such that
         *     |v| = c 2^q    and
         *     Q_MIN <= q <= Q_MAX    and
         *         either    2^(P-1) <= c < 2^P                 (normal)
         *         or        0 < c < 2^(P-1)  and  q = Q_MIN    (subnormal)
         */
        int index = 0;
        long bits = doubleToRawLongBits(v);
        long t = bits & T_MASK;
        int bq = (int) (bits >>> P - 1) & BQ_MASK;
        if (bq < BQ_MASK) {
            int start = index;
            if (bits < 0) {
                /*
                 * fd != null implies str == null and bits >= 0
                 * Thus, when fd != null, control never reaches here.
                 */
                str[index++] = NumberSerdeUtils.NEGATIVE_CHAR;
            }
            if (bq != 0) {
                /* normal value. Here mq = -q */
                int mq = -Q_MIN + 1 - bq;
                long c = C_MIN | t;
                /* The fast path discussed in section 8.3 of [1] */
                if (0 < mq && mq < P) {
                    long f = c >> mq;
                    if (f << mq == c) {
                        /*
                         * f is the exact integer value of |v|. Emit its digits
                         * followed by ".0" directly, bypassing the H=17 canonical
                         * normalization and trailing-zero trimming in toChars.
                         */
                        return writeIntegerDotZero(str, index, f) - start;
                    }
                }
                return toDecimal(str, index, -mq, c, 0) - start;
            }
            if (t != 0) {
                /* subnormal value */
                return (t < C_TINY
                        ? toDecimal(str, index, Q_MIN, 10 * t, -1)
                        : toDecimal(str, index, Q_MIN, t, 0)) - start;
            }
            return bits == 0 ? PLUS_ZERO : MINUS_ZERO;
        }
        if (t != 0) {
            return NAN;
        }
        return bits > 0 ? PLUS_INF : MINUS_INF;
    }

    private static int toDecimal(byte[] str, int index, int q, long c, int dk) {
        /*
         * The skeleton corresponds to figure 7 of [1].
         * The efficient computations are those summarized in figure 9.
         *
         * Here's a correspondence between Java names and names in [1],
         * expressed as approximate LaTeX source code and informally.
         * Other names are identical.
         * cb:     \bar{c}     "c-bar"
         * cbr:    \bar{c}_r   "c-bar-r"
         * cbl:    \bar{c}_l   "c-bar-l"
         *
         * vb:     \bar{v}     "v-bar"
         * vbr:    \bar{v}_r   "v-bar-r"
         * vbl:    \bar{v}_l   "v-bar-l"
         *
         * rop:    r_o'        "r-o-prime"
         */
        int out = (int) c & 0x1;
        long cb = c << 2;
        long cbr = cb + 2;
        long cbl;
        int k;
        /*
         * flog10pow2(e) = floor(log_10(2^e))
         * flog10threeQuartersPow2(e) = floor(log_10(3/4 2^e))
         * flog2pow10(e) = floor(log_2(10^e))
         */
        if (c != C_MIN || q == Q_MIN) {
            /* regular spacing */
            cbl = cb - 2;
            k = flog10pow2(q);
        } else {
            /* irregular spacing */
            cbl = cb - 1;
            k = flog10threeQuartersPow2(q);
        }
        int h = q + flog2pow10(-k) + 2;

        /* g1 and g0 are as in section 9.8.3 of [1], so g = g1 2^63 + g0 */
        long g1 = g1(-k);
        long g0 = g0(-k);

        long vb = rop(g1, g0, cb << h);
        long vbl = rop(g1, g0, cbl << h);
        long vbr = rop(g1, g0, cbr << h);

        long s = vb >> 2;
        if (s >= 100) {
            /*
             * For n = 17, m = 1 the table in section 10 of [1] shows
             *     s' = floor(s / 10) = floor(s 115_292_150_460_684_698 / 2^60)
             *        = floor(s 115_292_150_460_684_698 2^4 / 2^64)
             *
             * sp10 = 10 s'
             * tp10 = 10 t'
             * upin    iff    u' = sp10 10^k in Rv
             * wpin    iff    w' = tp10 10^k in Rv
             * See section 9.3 of [1].
             *
             * Also,
             * d_v = v      iff     4 sp10 = vb
             */
            long sp10 = 10 * multiplyHigh(s, 115_292_150_460_684_698L << 4);
            long tp10 = sp10 + 10;
            boolean upin = vbl + out <= sp10 << 2;
            boolean wpin = (tp10 << 2) + out <= vbr;
            if (upin != wpin) {
                /* Exactly one of u' or w' lies in Rv */
                return toChars(str, index, upin ? sp10 : tp10, k);
            }
        }

        /*
         * 10 <= s < 100    or    s >= 100  and  u', w' not in Rv
         * uin    iff    u = s 10^k in Rv
         * win    iff    w = t 10^k in Rv
         * See section 9.3 of [1].
         *
         * Also,
         * d_v = v      iff     4 s = vb
         */
        long t = s + 1;
        boolean uin = vbl + out <= s << 2;
        boolean win = (t << 2) + out <= vbr;
        if (uin != win) {
            /* Exactly one of u or w lies in Rv */
            return toChars(str, index, uin ? s : t, k + dk);
        }
        /*
         * Both u and w lie in Rv: determine the one closest to v.
         * See section 9.3 of [1].
         */
        long cmp = vb - (s + t << 1);
        boolean away = cmp > 0 || cmp == 0 && (s & 0x1) != 0;
        return toChars(str, index, away ? t : s, k + dk);
    }

    /*
     * Computes rop(cp g 2^(-127)), where g = g1 2^63 + g0
     * See section 9.9 and figure 8 of [1].
     */
    private static long rop(long g1, long g0, long cp) {
        long x1 = multiplyHigh(g0, cp);
        long y0 = g1 * cp;
        long y1 = multiplyHigh(g1, cp);
        long z = (y0 >>> 1) + x1;
        long vbp = y1 + (z >>> 63);
        return vbp | (z & MASK_63) + MASK_63 >>> 63;
    }

    /*
     * Formats the decimal f 10^e as plain decimal (no exponent notation).
     */
    private static int toChars(byte[] str, int index, long f, int e) {
        int len = flog10pow2(Long.SIZE - numberOfLeadingZeros(f));
        if (f >= pow10(len)) {
            len += 1;
        }

        /*
         * Normalize to H = 17 digits in [10^16, 10^17) so banker's rounding
         * to 15 sig digits is a simple /100 + half-to-even on the dropped pair.
         * fp 10^ep = f 10^(e-H) = 0.f 10^e
         */
        f *= pow10(H - len);
        e += len;

        long fH = f / 100L;
        long rem = f - fH * 100L;
        if (rem > 50L || (rem == 50L && (fH & 1L) == 1L)) {
            fH++;
        }
        int sig = 15;
        if (fH >= 1_000_000_000_000_000L) {
            /* Rolled over from 999...9e14 to 10^15: shed one digit, bump exponent. */
            fH /= 10L;
            e += 1;
        }
        /*
         * Strip trailing decimal zeros from fH up front so we never emit-then-trim.
         * Log-scan in 8/4/2/1 covers any count up to 15. fH is guaranteed to be in
         * [10^14, 10^15), so it can never strip down to 0.
         */
        if (fH % 100_000_000L == 0L) {
            fH /= 100_000_000L;
            sig -= 8;
        }
        if (fH % 10_000L == 0L) {
            fH /= 10_000L;
            sig -= 4;
        }
        if (fH % 100L == 0L) {
            fH /= 100L;
            sig -= 2;
        }
        if (fH % 10L == 0L) {
            fH /= 10L;
            sig -= 1;
        }

        return e > 0
                ? toChars1(str, index, fH, sig, e)
                : toChars2(str, index, fH, sig, e);
    }

    private static int toChars1(byte[] str, int index, long fH, int sig, int e) {
        /* e > 0: plain decimal, no leading zero before '.'. */
        if (e >= sig) {
            /* Integer-shape: sig digits + (e - sig) trailing zeros + ".0". */
            int end = index + e + 2;
            str[end - 1] = NumberSerdeUtils.ASCII_ZERO;
            str[end - 2] = NumberSerdeUtils.POINT_CHAR;
            int afterSig = emitDigitsLR(str, index, fH, sig);
            for (int p = afterSig; p < index + e; p++) {
                str[p] = NumberSerdeUtils.ASCII_ZERO;
            }
            return end;
        }
        /* Decimal-shape: e digits + '.' + (sig - e) digits.
         * Pre-place '.' at dotPos, then emit digits LR, skipping that slot. */
        int dotPos = index + e;
        str[dotPos] = NumberSerdeUtils.POINT_CHAR;
        return emitDigitsLRWithDot(str, index, fH, sig, dotPos);
    }

    private static int toChars2(byte[] str, int index, long fH, int sig, int e) {
        /* e <= 0: "0." + (-e) leading zeros + sig digits. */
        str[index] = NumberSerdeUtils.ASCII_ZERO;
        str[index + 1] = NumberSerdeUtils.POINT_CHAR;
        int pos = index + 2;
        int zerosEnd = pos - e;
        for (; pos < zerosEnd; pos++) {
            str[pos] = NumberSerdeUtils.ASCII_ZERO;
        }
        return emitDigitsLR(str, pos, fH, sig);
    }

    /*
     * Emits `sig` digits of `fH` left-to-right at positions [start, start + sig).
     * fH must have exactly `sig` significant digits (leading non-zero), sig in [1, 15].
     * sig <= 8: single int chunk via y(). sig > 8: hi (sig - 8 digits) + lo (8 digits) chunks.
     */
    private static int emitDigitsLR(byte[] str, int start, long fH, int sig) {
        if (sig <= 8) {
            return emitChunk(str, start, (int) fH, sig);
        }
        int hi = (int) (fH / 100_000_000L);
        int lo = (int) (fH - hi * 100_000_000L);
        int pos = emitChunk(str, start, hi, sig - 8);
        return emitChunk(str, pos, lo, 8);
    }

    /*
     * Same as emitDigitsLR but skips position dotPos (where '.' has been pre-written).
     */
    private static int emitDigitsLRWithDot(byte[] str, int start, long fH, int sig, int dotPos) {
        if (sig <= 8) {
            return emitChunkWithDot(str, start, (int) fH, sig, dotPos);
        }
        int hi = (int) (fH / 100_000_000L);
        int lo = (int) (fH - hi * 100_000_000L);
        int pos = emitChunkWithDot(str, start, hi, sig - 8, dotPos);
        return emitChunkWithDot(str, pos, lo, 8, dotPos);
    }

    /*
     * Emits `n` digits left-to-right from `chunk` (which has exactly `n` significant
     * digits, n in [1, 8]) at positions [pos, pos + n). Algorithm 1 in [3].
     */
    private static int emitChunk(byte[] str, int pos, int chunk, int n) {
        int y = y(chunk);
        /* y() pads `chunk` to 8 digits; skip the (8 - n) leading-zero extractions. */
        for (int i = 8 - n; i > 0; i--) {
            y = (10 * y) & MASK_28;
        }
        for (int i = n; i > 0; i--) {
            int t = 10 * y;
            str[pos++] = (byte) (NumberSerdeUtils.ASCII_ZERO + (t >>> 28));
            y = t & MASK_28;
        }
        return pos;
    }

    /*
     * Same as emitChunk but skips position dotPos (where '.' has been pre-written).
     */
    private static int emitChunkWithDot(byte[] str, int pos, int chunk, int n, int dotPos) {
        int y = y(chunk);
        for (int i = 8 - n; i > 0; i--) {
            y = (10 * y) & MASK_28;
        }
        for (int i = n; i > 0; i--) {
            if (pos == dotPos) pos++;
            int t = 10 * y;
            str[pos++] = (byte) (NumberSerdeUtils.ASCII_ZERO + (t >>> 28));
            y = t & MASK_28;
        }
        return pos;
    }

    private static int y(int a) {
        /*
         * Algorithm 1 in [3]: floor((a + 1) 2^n / b^k) - 1
         * with a < 10^8, b = 10, k = 8, n = 28.
         */
        return (int) (multiplyHigh(
                (long) (a + 1) << 28,
                193_428_131_138_340_668L) >>> 20) - 1;
    }

    /*
     * Emit the decimal digits of the strictly-positive integer |v| = f followed
     * by ".0" into str starting at index. Used by the integer fast path, where f
     * is known to be the exact integer value of a non-negative integer-valued
     * double with |v| < 2^52 (i.e. at most 16 decimal digits).
     */
    private static int writeIntegerDotZero(byte[] str, int index, long f) {
        int size = positiveDigitCount(f);
        int endIndex = index + size;
        int pos = endIndex;
        long q;
        int r;
        /* Two digits per iteration for larger values */
        while (f >= 65536) {
            q = f / 100;
            r = (int) (f - ((q << 6) + (q << 5) + (q << 2)));
            f = q;
            str[--pos] = NumberSerdeUtils.DIGIT_ONES[r];
            str[--pos] = NumberSerdeUtils.DIGIT_TENS[r];
        }
        /* One digit per iteration for the remaining <= 5 digits */
        do {
            q = f * 52429L >>> 19;
            r = (int) (f - ((q << 3) + (q << 1)));
            str[--pos] = (byte) (NumberSerdeUtils.ASCII_ZERO + r);
            f = q;
        } while (f != 0);
        str[endIndex] = NumberSerdeUtils.POINT_CHAR;
        str[endIndex + 1] = NumberSerdeUtils.ASCII_ZERO;
        return endIndex + 2;
    }

    private static int positiveDigitCount(long v) {
        /* v in [1, 2^52), so at most 16 decimal digits */
        if (v < 100000000L) {
            if (v < 10000L) {
                if (v < 100L) {
                    return v < 10L ? 1 : 2;
                }
                return v < 1000L ? 3 : 4;
            }
            if (v < 1000000L) {
                return v < 100000L ? 5 : 6;
            }
            return v < 10000000L ? 7 : 8;
        }
        if (v < 1000000000000L) {
            if (v < 10000000000L) {
                return v < 1000000000L ? 9 : 10;
            }
            return v < 100000000000L ? 11 : 12;
        }
        if (v < 100000000000000L) {
            return v < 10000000000000L ? 13 : 14;
        }
        return v < 1000000000000000L ? 15 : 16;
    }

}
