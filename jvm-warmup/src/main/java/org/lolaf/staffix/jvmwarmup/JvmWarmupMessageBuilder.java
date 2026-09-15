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
package org.lolaf.staffix.jvmwarmup;

import lombok.Getter;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.jvmwarmup.fix.encoders.JVMWarmupEncoder;
import org.lolaf.staffix.jvmwarmup.fix.fields.WarmupCharEnum;
import org.lolaf.staffix.jvmwarmup.fix.fields.WarmupIntEnum;
import org.lolaf.staffix.jvmwarmup.fix.fields.WarmupMVString;
import org.lolaf.staffix.jvmwarmup.fix.fields.WarmupStringEnum;

import java.time.*;
import java.util.concurrent.TimeUnit;

/**
 * Populates a reusable {@link JVMWarmupEncoder} with deterministic-but-varied data covering every
 * payload field of the dictionary plus a couple of repeating-group entries. The values rotate via
 * the call counter so equality-cached paths and field-reset paths are both exercised.
 */
final class JvmWarmupMessageBuilder {

    private static final String[] STRINGS = {"WARM", "FAST", "JIT-", "C1C2"};
    private static final char[] CHARS = {'W', 'A', 'R', 'M'};
    private static final WarmupCharEnum.WarmupCharEnumValues[] CHAR_ENUMS = WarmupCharEnum.WarmupCharEnumValues.values();
    private static final WarmupIntEnum.WarmupIntEnumValues[] INT_ENUMS = WarmupIntEnum.WarmupIntEnumValues.values();
    private static final WarmupStringEnum.WarmupStringEnumValues[] STRING_ENUMS = WarmupStringEnum.WarmupStringEnumValues.values();
    private static final WarmupMVString.WarmupMVStringValues[] MVSTRING_VALUES = WarmupMVString.WarmupMVStringValues.values();

    @Getter
    private final JVMWarmupEncoder encoder;
    private long counter;

    JvmWarmupMessageBuilder(FixSession fixSession) {
        this.encoder = fixSession.newEncoder(JVMWarmupEncoder.class).asReusable();
    }

    void populate() {
        long n = counter++;
        int i = (int) (n & 0x3);
        LocalDate today = LocalDate.now();
        UTCTime nowUtc = UTCTime.of(System.currentTimeMillis() * 1_000_000L);
        encoder.begin()
                .setWarmupString(STRINGS[i])
                .setWarmupChar(String.valueOf(CHARS[i]))
                .setWarmupBoolean((n & 1L) == 0L)
                .setWarmupInt((int) (n & 0xFFFF))
                .setWarmupSeqNum(n)
                .setWarmupPrice(100.0 + i * 0.25)
                .setWarmupQty(10.0 + i)
                .setWarmupAmt(1_000.0 + n)
                .setWarmupFloat(0.001 + i * 0.0005)
                .setWarmupPercentage(0.5)
                .setWarmupPriceOffset(0.01 * i)
                .setWarmupCountry("US")
                .setWarmupCurrency("USD")
                .setWarmupExchange("NASD")
                .setWarmupMVString(MVSTRING_VALUES[i % MVSTRING_VALUES.length])
                .setWarmupDataLen(4)
                .setWarmupData("DATA")
                .setWarmupLocalMktDate(today)
                .setWarmupUtcDateOnly(today)
                .setWarmupUtcTimeOnly(LocalTime.NOON, TimeUnit.MICROSECONDS)
                .setWarmupUtcTimestamp(nowUtc, TimeUnit.MICROSECONDS)
                .setWarmupCharEnum(CHAR_ENUMS[i % CHAR_ENUMS.length])
                .setWarmupIntEnum(INT_ENUMS[i % INT_ENUMS.length])
                .setWarmupStringEnum(STRING_ENUMS[i % STRING_ENUMS.length])
                .setWarmupLocalMktTime(LocalTime.NOON.plusSeconds(i), TimeUnit.MICROSECONDS)
                .setWarmupTzTimestamp(OffsetDateTime.now(ZoneOffset.UTC), TimeUnit.MICROSECONDS)
                .setWarmupTzTime(OffsetTime.of(LocalTime.NOON.plusSeconds(i), ZoneOffset.UTC))
                .setWarmupBooleanObj((n & 2L) == 0L)
                .setWarmupDecimalNew(1.5 + i * 0.25)
                .setWarmupSerdeCtx(STRINGS[(i + 2) & 0x3]);

        // Always emit 2 group entries to keep forGroup(...) dispatch on the hot path.
        encoder.addNoWarmupEntries(2)
                .setWarmupEntryId((int) n)
                .setWarmupEntryPrice(1.0 + i)
                .setWarmupEntryText(STRINGS[(i + 1) & 0x3])
                .setWarmupEntryId((int) n + 1)
                .setWarmupEntryPrice(2.0 + i)
                .setWarmupEntryText(STRINGS[(i + 2) & 0x3]);
    }
}