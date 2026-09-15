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

import lombok.Setter;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper.ObjectInstanceStrategy;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.executor.MessageExecutor;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.jvmwarmup.fix.fields.*;
import org.lolaf.staffix.jvmwarmup.fix.msg.MessageTypes;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Decoder for the synthetic {@code JVMWarmup} message. Its sole purpose is to exercise as many
 * {@code mapXxxField(...)} branches of {@link FixFieldsDecoderMapper} as possible — body fields are
 * registered with a mix of lambda-Consumer and VarHandle styles, and the {@code NoWarmupEntries}
 * group entries are registered VarHandle-only so the JIT also warms the group-scoped dispatch.
 *
 * <p>Field values are stored back into instance fields purely so the engine has somewhere to write
 * them; the decoder itself doesn't read them. {@link #onDecoded} bumps the provided counter.
 */
@Setter
public class JvmWarmupMessageDecoder implements FixMessageDecoder {

    private final Consumer<MessageExecutor<Void, Void, Void, Void>> onDecodedHook;

    // Body fields — written via lambda setters or VarHandle setters depending on the mapping below.
    private String warmupString;
    private char warmupChar;
    private boolean warmupBoolean;
    private int warmupInt;
    private long warmupSeqNum;
    private double warmupPrice;
    private BigDecimal warmupQty;
    private DecimalFloat warmupAmt;
    private double warmupFloat;
    private double warmupPercentage;
    private double warmupPriceOffset;
    private String warmupCountry;
    private String warmupCurrency;
    private String warmupExchange;
    private String warmupMVString;
    private int warmupDataLen;
    private String warmupData;
    private int warmupLocalMktDate;
    private int warmupUtcDateOnly;
    private long warmupUtcTimeOnly;
    private UTCTime warmupUtcTimestamp;
    private WarmupCharEnum.WarmupCharEnumValues warmupCharEnum;
    private WarmupIntEnum.WarmupIntEnumValues warmupIntEnum;
    private WarmupStringEnum.WarmupStringEnumValues warmupStringEnum;
    private LocalTime warmupLocalMktTime;
    private OffsetDateTime warmupTzTimestamp;
    private OffsetTime warmupTzTime;
    private Boolean warmupBooleanObj;
    private DecimalFloat warmupDecimalNew;
    private SerDe.DeserializationContext warmupSerdeCtx;

    // Repeating-group entry fields — written via VarHandle setters, one entry at a time.
    private int warmupEntryId;
    private double warmupEntryPrice;
    private String warmupEntryText;

    private IntSupplier warmupIndexer;

    public JvmWarmupMessageDecoder(Consumer<MessageExecutor<Void, Void, Void, Void>> onDecodedHook) {
        this.onDecodedHook = onDecodedHook;
    }

    @Override
    public MessageType getMessageType() {
        return MessageTypes.JVMWarmup;
    }

    @Override
    public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
        // Body — mix lambda and VarHandle so both registration paths are JIT-warmed.
        fixFieldsDecoderMapper
                .indexer(WarmupString.get(), this::setWarmupIndexer)
                .mapStringField(WarmupString.get(), this::setWarmupString, null, ObjectInstanceStrategy.CACHED)
                .mapCharField(WarmupChar.get(), this::setWarmupChar, ' ')
                .mapBooleanField(WarmupBoolean.get(), this::setWarmupBoolean, false)
                .mapIntField(WarmupInt.get(), this::setWarmupInt, 0)
                .mapLongField(WarmupSeqNum.get(), this::setWarmupSeqNum, 0L)
                .mapDoubleField(WarmupPrice.get(), this::setWarmupPrice, Double.NaN)
                .mapBigDecimalField(WarmupQty.get(), this::setWarmupQty, null)
                .mapDecimalFloatField(WarmupAmt.get(), this::setWarmupAmt, null, ObjectInstanceStrategy.CACHED)
                .mapDoubleField(WarmupFloat.get(), "warmupFloat", () -> this, Double.NaN)
                .mapDoubleField(WarmupPercentage.get(), "warmupPercentage", () -> this, Double.NaN)
                .mapDoubleField(WarmupPriceOffset.get(), "warmupPriceOffset", () -> this, Double.NaN)
                .mapStringField(WarmupCountry.get(), this::setWarmupCountry, null, ObjectInstanceStrategy.NEW_INSTANCE)
                .mapStringField(WarmupCurrency.get(), "warmupCurrency", () -> this, null, ObjectInstanceStrategy.CACHED)
                .mapStringField(WarmupExchange.get(), this::setWarmupExchange, null, ObjectInstanceStrategy.NEW_INSTANCE)
                .mapStringField(WarmupMVString.get(), this::setWarmupMVString, null, ObjectInstanceStrategy.NEW_INSTANCE)
                .mapIntField(WarmupDataLen.get(), this::setWarmupDataLen, 0)
                .mapStringField(WarmupData.get(), this::setWarmupData, null, ObjectInstanceStrategy.NEW_INSTANCE)
                .mapUtcDateOnlyField(WarmupLocalMktDate.get(), this::setWarmupLocalMktDate, 0)
                .mapUtcDateOnlyField(WarmupUtcDateOnly.get(), "warmupUtcDateOnly", () -> this, 0)
                .mapUtcTimeOnlyField(WarmupUtcTimeOnly.get(), this::setWarmupUtcTimeOnly, 0L)
                .mapUtcDateTimeField(WarmupUtcTimestamp.get(), this::setWarmupUtcTimestamp, null)
                .mapCharValuesEnumField(WarmupCharEnum.get(), this::setWarmupCharEnum, null)
                .mapIntValuesEnumField(WarmupIntEnum.get(), this::setWarmupIntEnum, null)
                .mapStringValuesEnumField(WarmupStringEnum.get(), this::setWarmupStringEnum, null)
                // Extra branches: LocalMktTime, TZ timestamp/time, BooleanObject, DecimalFloat with
                // NEW_INSTANCE, and the raw SerDe deserialization-context consumer.
                .mapLocalMktTimeField(WarmupLocalMktTime.get(), this::setWarmupLocalMktTime, null)
                .mapTzDateTimeField(WarmupTzTimestamp.get(), "warmupTzTimestamp", () -> this, null)
                .mapTzTimeField(WarmupTzTime.get(), this::setWarmupTzTime, null)
                .mapBooleanObjectField(WarmupBooleanObj.get(), this::setWarmupBooleanObj, null)
                .mapDecimalFloatField(WarmupDecimalNew.get(), this::setWarmupDecimalNew, null, ObjectInstanceStrategy.NEW_INSTANCE)
                .mapSerdeCtxField(WarmupSerdeCtx.get(), this::setWarmupSerdeCtx);

        // Group — VarHandle-only + flip the auto-reset toggle so that branch is also exercised.
        fixFieldsDecoderMapper.forGroup(NoWarmupEntries.get())
                .mapIntField(WarmupEntryId.get(), "warmupEntryId", () -> this, 0)
                .mapDoubleField(WarmupEntryPrice.get(), "warmupEntryPrice", () -> this, Double.NaN)
                .withFieldsAutoResetDisabled()
                .mapStringField(WarmupEntryText.get(), "warmupEntryText", () -> this, null, ObjectInstanceStrategy.CACHED);
    }

    @Override
    public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
        onDecodedHook.accept(fixSession.getMessageExecutor(JvmWarmupMessageDecoder.class, warmupIndexer));
    }
}