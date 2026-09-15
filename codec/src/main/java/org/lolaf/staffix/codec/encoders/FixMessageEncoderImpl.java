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
package org.lolaf.staffix.codec.encoders;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.unsafe.UnsafeOperations;
import org.lolaf.ringos.unsafe.UnsafeOperationsApi;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixFieldsEncoder;
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.codec.FixMessageEncodingListener;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.serde.*;

import java.math.BigDecimal;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * The base every generated encoder extends: writes the header, the body a subclass fills in, and the trailer.
 *
 * <p>BodyLength(9) and CheckSum(10) can only be known once the body exists, so the header leaves room for them
 * and they are written back in place rather than by re-encoding.
 *
 * <p>An encoder is reusable: {@code begin()} resets it for the next message, which is what makes a pooled
 * encoder cheaper than a fresh one on the message path.
 */
@Slf4j
public abstract class FixMessageEncoderImpl<T extends FixMessageEncoder<?>> implements FixMessageEncoder<T> {

    private static final String DIRECT_BB_ALLOCATOR = "direct";
    private static final String HEAP_BB_ALLOCATOR = "heap";

    private static final byte FIELD_SEPARATOR_BYTE = '\001';
    private static final int FIELD_SEPARATOR_INT = '\001';

    private static final Consumer<FixMessageEncoder<?>> VOID_RELEASER = encoder -> {
        // nothing to do
    };

    /**
     * checksum = 10=128\001 // 7 chars max
     * fix begin string = 8=FIX.x.x\001 10 chars max
     * body length = 9=123456789\001 12 char max
     */
    private static final int ADDITIONAL_BUFFER_SIZE = 30;
    private static final int DEFAULT_BODY_BUFFER_SIZE = Integer.parseInt(System.getProperty("staffix.FixMessageEncoder.body.buffer.size", "1024"));
    private static final int DEFAULT_HEADER_BUFFER_SIZE = Integer.parseInt(System.getProperty("staffix.FixMessageEncoder.header.buffer.size", "256"));
    private static final int DEFAULT_TRAILER_BUFFER_SIZE = Integer.parseInt(System.getProperty("staffix.FixMessageEncoder.trailer.buffer.size", "32"));
    private static final IntFunction<ByteBuffer> DEFAULT_BYTE_BUFFER_ALLOCATOR = getBBAllocator(System.getProperty("staffix.FixMessageEncoder.byte.buffer.allocator", HEAP_BB_ALLOCATOR));

    private static final FixField BEGIN_STRING = FixField.of(8, FieldType.STRING, FieldLocation.HEADER);
    private static final FixField BODY_LENGTH = FixField.of(9, FieldType.INT, FieldLocation.HEADER);
    private static final FixField CHECKSUM = FixField.of(10, FieldType.INT, FieldLocation.TRAILER);
    private static final FixField MSG_TYPE = FixField.of(35, FieldType.STRING, FieldLocation.HEADER);
    private static final FixField SEQ_NUMBER = FixField.of(34, FieldType.INT, FieldLocation.HEADER);
    private static final FixField SENDING_TIME = FixField.of(CoreFields.SENDING_TIME, FieldType.UTCTIMESTAMP, FieldLocation.HEADER);

    private final IntFunction<ByteBuffer> allocator;
    private final MessageType messageType;
    private final ByteArraysCache byteArraysCache;
    private final Supplier<FixFieldsEncoder> headersAppender;
    private final Supplier<FixFieldsEncoder> bodyAppender;
    private final Supplier<FixFieldsEncoder> trailerAppender;
    private final T thisInstance;
    private final Consumer<FixMessageEncoder<?>> releaser;
    private final FixMessageEncodingListener encodingListener;
    private final Clock clock;
    private boolean reusable;
    private Object[] pluginEncodingState;
    private ByteBuffer bodyBuffer;
    private ByteBuffer headerBuffer;
    private ByteBuffer trailerBuffer;
    private FieldScope fieldScope;
    private int checksumSum;
    private boolean destroyed;
    private long encodingStartTimeInNanos;

    protected FixMessageEncoderImpl(Consumer<FixMessageEncoder<?>> releaser, MessageType messageType, IntFunction<ByteBuffer> allocator,
                                    int headerBufferCapacity, int bodyBufferCapacity, int trailerBufferCapacity, FixMessageEncodingListener encodingListener,
                                    Clock clock) {
        this.messageType = messageType;
        this.allocator = allocator != null ? allocator : DEFAULT_BYTE_BUFFER_ALLOCATOR;
        this.bodyBuffer = this.allocator.apply(bodyBufferCapacity);
        this.headerBuffer = this.allocator.apply(headerBufferCapacity);
        this.trailerBuffer = this.allocator.apply(trailerBufferCapacity);
        this.byteArraysCache = new ByteArraysCache(4);
        this.headersAppender = this::headersSupplier;
        this.bodyAppender = this::bodySupplier;
        this.trailerAppender = this::trailerSupplier;
        this.thisInstance = (T) this;
        this.releaser = releaser != null ? releaser : VOID_RELEASER;
        this.reusable = releaser != null;
        this.encodingListener = encodingListener != null ? encodingListener : FixMessageEncodingListener.VoidFixMessageEncodingListener.getInstance();
        this.clock = clock != null ? clock : Clock.VoidClock.getInstance();
    }

    protected FixMessageEncoderImpl(Consumer<FixMessageEncoder<?>> releaser, MessageType messageType, IntFunction<ByteBuffer> allocator,
                                    FixMessageEncodingListener encodingListener, Clock clock) {
        this(releaser, messageType, allocator, DEFAULT_HEADER_BUFFER_SIZE, DEFAULT_BODY_BUFFER_SIZE, DEFAULT_TRAILER_BUFFER_SIZE, encodingListener, clock);
    }

    private static IntFunction<ByteBuffer> getBBAllocator(String allocator) {
        switch (allocator) {
            case HEAP_BB_ALLOCATOR:
                return ByteBuffer::allocate;
            case DIRECT_BB_ALLOCATOR:
                return ByteBuffer::allocateDirect;
            default:
                throw new IllegalStateException("Only " + HEAP_BB_ALLOCATOR + " or "
                        + DIRECT_BB_ALLOCATOR + " values are allowed to define default byte buffer allocator");
        }
    }

    @Override
    public void release() {
        fieldScope = null;
        releaser.accept(this);
    }

    @Override
    public void destroy() {
        if (bodyBuffer.isDirect()) {
            if (!destroyed) {
                UnsafeOperationsApi.ifAvailableDo(this::cleanBuffers);
                destroyed = true;
            } else {
                log.error("FixMessageEncoder {} has been already destroyed, are you reusing the same non pooled instance to send multiple messages ?", this.getClass().getName());
            }
        }
    }

    private void cleanBuffers(UnsafeOperations unsafe) {
        unsafe.invokeCleaner(bodyBuffer);
        unsafe.invokeCleaner(headerBuffer);
        unsafe.invokeCleaner(trailerBuffer);
    }

    @Override
    public boolean isReusable() {
        return reusable;
    }

    @Override
    public MessageType getMessageType() {
        return messageType;
    }


    @Override
    public T asReusable() {
        this.reusable = true;
        return thisInstance;
    }

    @Override
    public boolean isAvailable() {
        return fieldScope == null;
    }

    @Override
    public Object[] pluginEncodingState(int pluginCount) {
        Object[] state = pluginEncodingState;
        if (state == null || state.length < pluginCount) {
            return pluginEncodingState = new Object[pluginCount];
        }

        for (int i = 0; i < pluginCount; i++) {
            state[i] = null;
        }
        return state;
    }

    @Override
    public Object[] pluginEncodingState() {
        return pluginEncodingState;
    }

    @Override
    public T begin() {
        if (fieldScope != null) {
            throw new IllegalStateException("Encoder begin() already called, but encoder not yet sent");
        }

        encodingStartTimeInNanos = clock.nanoTime();
        encodingListener.onEncodingStart(messageType, this, encodingStartTimeInNanos);
        checksumSum = 0;
        fieldScope = FieldScope.BODY;
        bodyBuffer.clear();
        headerBuffer.clear();
        trailerBuffer.clear();
        return thisInstance;
    }

    private FixFieldsEncoder<?> headersSupplier() {
        fieldScope = FieldScope.HEADER;
        return this;
    }

    private FixFieldsEncoder<?> trailerSupplier() {
        fieldScope = FieldScope.TRAILER;
        return this;
    }

    private FixFieldsEncoder<?> bodySupplier() {
        fieldScope = FieldScope.BODY;
        return this;
    }

    @Override
    public T copy(FixMessageEncoder<?> other) {
        copyBuffer(other.getEncodedBody().flip());
        checksumSum += other.getEncodedBodyChecksum();
        return thisInstance;
    }

    private void copyBuffer(ByteBuffer buffer) {
        try {
            bodyBuffer.put(buffer);
        } catch (BufferOverflowException ex) {
            resizeBuffer(bodyBuffer.mark());
            copyBuffer(buffer);
        }
    }

    @Override
    public ByteBuffer getEncodedBody() {
        return bodyBuffer;
    }

    @Override
    public int getEncodedBodyChecksum() {
        return checksumSum;
    }

    @Override
    public ByteBuffer encode(IntFunction<ByteBuffer> allocator, long sequenceNumber, FixSessionId fixSessionId, FixApplication fixApplication,
                             TimeUnit sendingTimeAccuracy, UTCTime sendingTime, FixSession fixSession) {
        if (fieldScope == null) {
            throw new IllegalStateException("null field scope, ensure you call begin() on encoder " + this.getClass().getSimpleName() + ":" + this.hashCode() + " before sending the message");
        }
        encodingListener.onEncodedBody(messageType, bodyBuffer, this, encodingStartTimeInNanos);

        fieldScope = FieldScope.HEADER;
        serializeFieldAndComputeChecksum(MSG_TYPE, messageType.serialized());
        addLong(SEQ_NUMBER, sequenceNumber);
        fixSessionId.serialize(this);

        serializeFieldAndComputeChecksum(SENDING_TIME, UtcDateTimeSerde.serializeTime(sendingTime, sendingTimeAccuracy));
        if (fixSession != null) {
            if (messageType.isAdmin()) {
                fixApplication.onAdminMessageEncoding(fixSession, messageType, headersAppender, bodyAppender, trailerAppender);
            } else {
                fixApplication.onMessageEncoding(fixSession, messageType, headersAppender, trailerAppender);
            }
        }

        int bodyLength = headerBuffer.position() + bodyBuffer.position() + trailerBuffer.position();
        ByteBuffer encodedBuffer = allocator.apply(bodyLength + ADDITIONAL_BUFFER_SIZE);
        serializeFieldAndComputeChecksum(BEGIN_STRING, fixSessionId.getFixVersion().getBeginString(), encodedBuffer);

        byte[] serializedBodyLength = byteArraysCache.forSize(IntSerde.getPositiveIntSize(bodyLength));
        IntSerde.serialize(bodyLength, serializedBodyLength);
        serializeFieldAndComputeChecksum(BODY_LENGTH, serializedBodyLength, encodedBuffer);

        fieldScope = FieldScope.TRAILER;
        serializeField(CHECKSUM, ChecksumsRegistry.getChecksumWithLeadingZeros(checksumSum & 0xFF));
        ByteBuffer encodedMessage = encodedBuffer.put(headerBuffer.flip())
                .put(bodyBuffer.flip())
                .put(trailerBuffer.flip());
        encodingListener.onEncodingEnd(messageType, encodedMessage, this, encodingStartTimeInNanos);
        return encodedMessage;
    }

    @Override
    public int getApproximateEncodedMessageLength(FixSessionId fixSessionId) {
        return bodyBuffer.position() + fixSessionId.getSerializedLen() + ADDITIONAL_BUFFER_SIZE + UtcDateTimeSerde.SIZE_WITH_NANOS;
    }

    @Override
    public <V> T addField(FixField fixField, V value, SerDe<V> serializer) {
        ByteBuffer outputBuffer = getOutputBufferForCurrentScope();
        try {
            outputBuffer.mark().put(fixField.serialized());
            int startPosition = outputBuffer.position();
            serializer.serialize(outputBuffer, value);
            int endPosition = outputBuffer.position();
            outputBuffer.put(FIELD_SEPARATOR_BYTE);
            computeChecksum(fixField, outputBuffer, startPosition, endPosition);
        } catch (BufferOverflowException ex) {
            resizeBuffer(outputBuffer);
            addField(fixField, value, serializer);
        }
        return thisInstance;
    }

    @Override
    public T addBytes(FixField fixField, byte[] serializedField) {
        serializeFieldAndComputeChecksum(fixField, serializedField);
        return thisInstance;
    }

    @Override
    public T addUUID(FixField fixField, UUID value) {
        addField(fixField, value, UUIDSerde.instance());
        return thisInstance;
    }

    @Override
    public T addUtcDateTime(FixField fixField, long epochDay, long nanosOfDay, TimeUnit accuracy) {
        serializeFieldAndComputeChecksum(fixField, UtcDateTimeSerde.serializeEpochDays(epochDay, nanosOfDay, accuracy));
        return thisInstance;
    }

    @Override
    public T addUtcDateTime(FixField fixField, Instant value, TimeUnit accuracy) {
        serializeFieldAndComputeChecksum(fixField, UtcDateTimeSerde.serializeInstant(value, accuracy));
        return thisInstance;
    }

    @Override
    public T addUtcDateTime(FixField fixField, UTCTime value, TimeUnit accuracy) {
        serializeFieldAndComputeChecksum(fixField, UtcDateTimeSerde.serializeTime(value, accuracy));
        return thisInstance;
    }

    @Override
    public T addTzTime(FixField fixField, OffsetTime offsetTime) {
        addField(fixField, offsetTime, TzTimeOnlySerde.instance());
        return thisInstance;
    }

    @Override
    public T addTzDateTime(FixField fixField, OffsetDateTime offsetDateTime, TimeUnit accuracy) {
        serializeFieldAndComputeChecksum(fixField, TzDateTimeSerde.instance().serialize(offsetDateTime, accuracy));
        return thisInstance;
    }

    @Override
    public T addUtcDate(FixField fixField, long epochDay) {
        serializeFieldAndComputeChecksum(fixField, UtcDateOnlySerde.serializeEpochDay(epochDay));
        return thisInstance;
    }

    @Override
    public T addUtcDate(FixField fixField, LocalDate value) {
        serializeFieldAndComputeChecksum(fixField, UtcDateOnlySerde.serializeEpochDay(value.toEpochDay()));
        return thisInstance;
    }

    @Override
    public T addUtcTime(FixField fixField, Instant value, TimeUnit accuracy) {
        serializeFieldAndComputeChecksum(fixField, UtcTimeOnlySerde.serializeForPrecision(value, accuracy));
        return thisInstance;
    }

    @Override
    public T addUtcTime(FixField fixField, UTCTime value, TimeUnit accuracy) {
        serializeFieldAndComputeChecksum(fixField, UtcTimeOnlySerde.serializeForPrecision(value, accuracy));
        return thisInstance;
    }

    @Override
    public T addUtcTime(FixField fixField, long nanoOfDay, TimeUnit accuracy) {
        serializeFieldAndComputeChecksum(fixField, UtcTimeOnlySerde.serializeForPrecision(nanoOfDay, accuracy));
        return thisInstance;
    }

    @Override
    public T addUtcTime(FixField fixField, LocalTime value, TimeUnit accuracy) {
        serializeFieldAndComputeChecksum(fixField, UtcTimeOnlySerde.serializeForPrecision(value, accuracy));
        return thisInstance;
    }

    @Override
    public T addInt(FixField fixField, int value) {
        serializeFieldAndComputeChecksum(fixField, IntSerde.cachedSerialize(value));
        return thisInstance;
    }

    @Override
    public T addLong(FixField fixField, long value) {
        serializeFieldAndComputeChecksum(fixField, LongSerde.cachedSerialize(value));
        return thisInstance;
    }

    @Override
    public T addDouble(FixField fixField, double value) {
        serializeFieldAndComputeChecksum(fixField, DoubleSerde.serialize(value));
        return thisInstance;
    }

    @Override
    public T addBigDecimal(FixField fixField, BigDecimal value) {
        return addField(fixField, value, BigDecimalSerde.instance());
    }

    @Override
    public T addDecimalFloat(FixField fixField, DecimalFloat value) {
        return addField(fixField, value, DecimalFloatSerde.instance());
    }

    @Override
    public T addBoolean(FixField fixField, boolean value) {
        byte booleanVal = BooleanSerde.serialize(value);
        serializeSimpleField(fixField, booleanVal);
        computeSimpleChecksum(fixField, booleanVal);
        return thisInstance;
    }

    @Override
    public T addString(FixField fixField, String value) {
        return addField(fixField, value, StringSerde.instance());
    }

    @Override
    public T addChar(FixField fixField, char value) {
        byte charVal = CharSerde.serialize(value);
        serializeSimpleField(fixField, charVal);
        computeSimpleChecksum(fixField, charVal);
        return thisInstance;
    }

    @Override
    public T addCharEnum(FixField fixField, FixField.CharValuesEnum value) {
        addChar(fixField, value.code());
        return thisInstance;
    }

    @Override
    public T addIntEnum(FixField fixField, FixField.IntValuesEnum value) {
        serializeFieldAndComputeChecksum(fixField, value.serialized());
        return thisInstance;
    }

    @Override
    public T addStringEnum(FixField fixField, FixField.StringValuesEnum value) {
        serializeFieldAndComputeChecksum(fixField, value.serialized());
        return thisInstance;
    }

    private void serializeFieldAndComputeChecksum(FixField fixField, byte[] serialized) {
        serializeField(fixField, serialized);
        computeChecksum(fixField, serialized);
    }

    private void serializeFieldAndComputeChecksum(FixField fixField, byte[] serialized, ByteBuffer outputBuffer) {
        serializeField(fixField, serialized, outputBuffer);
        computeChecksum(fixField, serialized);
    }

    private void serializeSimpleField(FixField fixField, byte serialized) {
        ByteBuffer outputBuffer = getOutputBufferForCurrentScope();
        try {
            outputBuffer.mark()
                    .put(fixField.serialized())
                    .put(serialized)
                    .put(FIELD_SEPARATOR_BYTE);
        } catch (BufferOverflowException ex) {
            resizeBuffer(outputBuffer);
            serializeSimpleField(fixField, serialized);
        }
    }

    private void serializeField(FixField fixField, byte[] serialized) {
        ByteBuffer outputBuffer = getOutputBufferForCurrentScope();
        try {
            serializeField(fixField, serialized, outputBuffer);
        } catch (BufferOverflowException ex) {
            resizeBuffer(outputBuffer);
            serializeField(fixField, serialized);
        }
    }

    private void serializeField(FixField fixField, byte[] serialized, ByteBuffer outputBuffer) {
        outputBuffer.mark()
                .put(fixField.serialized())
                .put(serialized)
                .put(FIELD_SEPARATOR_BYTE);
    }

    private ByteBuffer getOutputBufferForCurrentScope() {
        switch (fieldScope) {
            case BODY:
                return bodyBuffer;
            case HEADER:
                return headerBuffer;
            case TRAILER:
                return trailerBuffer;
            default:
                throw new IllegalStateException(fieldScope.name());
        }
    }

    private void computeSimpleChecksum(FixField fixField, byte serialized) {
        checksumSum += fixField.checksum();
        checksumSum += serialized;
        checksumSum += FIELD_SEPARATOR_INT;
    }

    private void resizeBuffer(ByteBuffer outputBuffer) {
        outputBuffer.reset();
        switch (fieldScope) {
            case BODY:
                bodyBuffer = resizeAndCleanBufferIfNeeded(bodyBuffer);
                break;
            case HEADER:
                headerBuffer = resizeAndCleanBufferIfNeeded(headerBuffer);
                break;
            case TRAILER:
                trailerBuffer = resizeAndCleanBufferIfNeeded(trailerBuffer);
                break;
            default:
                throw new IllegalStateException("Unhandled " + outputBuffer);
        }
    }

    private ByteBuffer resizeAndCleanBufferIfNeeded(ByteBuffer toIncreaseSizeBuffer) {
        ByteBuffer increasedSizeBuffer = increaseByteBufferSize(toIncreaseSizeBuffer);
        UnsafeOperationsApi.ifAvailableDo(UnsafeOperations::invokeCleanerIfNeeded, increasedSizeBuffer);
        return increasedSizeBuffer;
    }

    private ByteBuffer increaseByteBufferSize(ByteBuffer toIncrease) {
        return allocator.apply(toIncrease.capacity() * 2).put(toIncrease.flip());
    }

    private void computeChecksum(FixField fixField, byte[] bytes) {
        checksumSum += fixField.checksum();
        for (byte b : bytes) {
            checksumSum += b;
        }
        checksumSum += FIELD_SEPARATOR_INT;
    }

    private void computeChecksum(FixField fixField, ByteBuffer byteBuffer, int startPosition, int endPosition) {
        checksumSum += fixField.checksum();
        for (int i = startPosition; i < endPosition; i++) {
            checksumSum += byteBuffer.get(i);
        }
        checksumSum += FIELD_SEPARATOR_INT;
    }

    private enum FieldScope {
        HEADER,
        BODY,
        TRAILER;
    }

    private static class ChecksumsRegistry {

        private static final int CHECKSUM_MAX_INDEX = 257;
        private static final byte[][] CHECKSUMS = generateChecksumTable();

        private static byte[][] generateChecksumTable() {
            DecimalFormat format = new DecimalFormat("000");
            byte[][] table = new byte[CHECKSUM_MAX_INDEX][];
            for (int i = 0; i < table.length; i++) {
                table[i] = format.format(i).getBytes(SerDe.CHARSET);
            }
            return table;
        }

        public static byte[] getChecksumWithLeadingZeros(int checksum) {
            return CHECKSUMS[checksum];
        }
    }

}