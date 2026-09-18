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
package org.lolaf.staffix.codec.decoders;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.codec.*;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.msg.MessageFieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.serde.Hashing;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.api.version.FixtVersion;
import org.lolaf.staffix.codec.serde.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Turns bytes off the socket into decoded messages. The hottest code in the library.
 *
 * <p>Optional behaviour is never a test on the parsing loop. Each of checksum validation, garbled-message
 * detection and field processing is an interface with an {@code Active}/{@code Void} pair, chosen once in the
 * constructor from the session's validation settings, or swapped when state changes - a message being rolled
 * back swaps in {@link VoidDecoder}, so the rest of it is skipped without a branch per field.
 *
 * <p>A message can arrive across any number of reads, so the parser keeps its position and unwinds on
 * {@link org.lolaf.staffix.api.codec.NotEnoughDataException} rather than buffering a whole message first.
 */
@Slf4j
public class FixMessageParser {

    public static final int MAX_INNER_GROUP = Integer.parseInt(System.getProperty("staffix.FixMessageParser.maxInnerGroup", "4"));
    public static final int MAX_FIELD_SIZE = Integer.parseInt(System.getProperty("staffix.FixMessageParser.maxFieldValueSize", "2048"));
    private static final LongSupplier DISABLED_CURRENT_SEQ_NUM_CHECK = () -> Long.MIN_VALUE;
    private static final NotEnoughDataException NOT_ENOUGH_DATA_EXCEPTION = new NotEnoughDataException();

    private static final byte EQUALS = '=';
    private static final byte FIELD_SEPARATOR_BYTE = CoreFields.FIELD_SEPARATOR_BYTE;
    /**
     * The three tags the FIX session layer mandates as the first three fields of any message, in that order
     * (see section 4.5.2 "Garbled message processing").
     */
    private static final int[] MANDATED_FIRST_FIELDS = {CoreFields.BEGIN_STRING, CoreFields.BODY_LENGTH, CoreFields.MESSAGE_TYPE};
    private static final int[] DEFINED_BEGIN_STRING_HASHES = definedBeginStringHashes();
    /**
     * Byte count of a CheckSum(10) field, which the FIX session layer fixes to exactly {@code 10=} followed by three
     * digits and the field separator.
     */
    private static final int CHECKSUM_FIELD_LENGTH = 7;
    /**
     * Returned by {@link #garbledMessageResyncPosition} when no position could be established.
     */
    private static final int NOT_SET_POSITION = -1;
    private final MessageTypeRegistry messageTypeRegistry;
    private final FieldsRegistry fieldsRegistry;
    private final FixField checksumField;
    private final MessageParsingState messageParsingState;
    private final SerDe.DeserializationContext deserializationContext;
    private final FixMessagesLogger.Logger fixMessagesLogger;
    private final ChecksumCalculator checksumCalculator;
    private final long maxSendingTimeInNanos;
    private final Clock clock;
    private final boolean validateFieldsHaveValues;
    private final boolean validateFieldsOutOfOrder;
    private final CompIdValidator compIdValidator;
    private final GarbledMessageDetector garbledMessageDetector;
    private final int expectedBeginStringHash;
    private ByteBuffer logFragmentBuffer;

    public FixMessageParser(FixSessionId fixSessionId,
                            MessageTypeRegistry messageTypeRegistry,
                            FieldsRegistry fieldsRegistry,
                            FixMessagesLogger.Logger fixMessagesLogger,
                            FixSessionSettings.ValidationSettings validationSettings,
                            Clock clock,
                            FixMessageParserEventsListener fixMessageParserEventsListener) {
        this.messageTypeRegistry = messageTypeRegistry;
        this.fieldsRegistry = fieldsRegistry;
        this.checksumField = fieldsRegistry.find(CoreFields.CHECKSUM);
        this.messageParsingState = new MessageParsingState(fixMessageParserEventsListener,
                fixSessionId, MessageFieldsRegistry.Registry.getInstance(messageTypeRegistry.getTargetDictionary()), validationSettings.getMaxMessageSize(),
                validationSettings.isValidateFieldsOutOfOrder());
        this.deserializationContext = new DeserializationContextImpl();
        this.fixMessagesLogger = fixMessagesLogger;
        this.checksumCalculator = validationSettings.isValidateChecksum() ? ActiveChecksumCalculator.INSTANCE : VoidChecksumCalculator.INSTANCE;
        this.maxSendingTimeInNanos = validationSettings.getMaxSendingTime() != null ? validationSettings.getMaxSendingTime().toNanos() : 0;
        this.clock = clock;
        this.logFragmentBuffer = ByteBuffer.allocate(128);
        this.validateFieldsHaveValues = validationSettings.isValidateFieldsHaveValues();
        this.validateFieldsOutOfOrder = validationSettings.isValidateFieldsOutOfOrder();
        // one validator for all four CompID checks, built as soon as any of them is configured, so that the parsing
        // loop keeps a single null test whatever the combination
        Collection<String> expectedOnBehalfOfCompIds = validationSettings.getExpectedOnBehalfOfCompIds();
        Collection<String> expectedDeliverToCompIds = validationSettings.getExpectedDeliverToCompIds();
        boolean anyCompIdCheck = validationSettings.isValidateCompId()
                || (expectedOnBehalfOfCompIds != null && !expectedOnBehalfOfCompIds.isEmpty())
                || (expectedDeliverToCompIds != null && !expectedDeliverToCompIds.isEmpty());
        this.compIdValidator = anyCompIdCheck
                ? new CompIdValidator(validationSettings.isValidateCompId(), fixSessionId.getSenderCompID().getValue(),
                fixSessionId.getTargetCompID().getValue(), expectedOnBehalfOfCompIds, expectedDeliverToCompIds) : null;
        this.garbledMessageDetector = validationSettings.isDetectGarbledMessages() ?
                ActiveGarbledMessageDetector.INSTANCE : VoidGarbledMessageDetector.INSTANCE;
        byte[] beginStringBytes = validationSettings.isValidateBeginString() ? fixSessionId.getFixVersion().getBeginString() : null;
        this.expectedBeginStringHash = beginStringBytes != null ? Hashing.hash(beginStringBytes, 0, beginStringBytes.length) : 0;
    }

    /**
     * Hashes of every BeginString the engine knows about. A BeginString outside this set is not a defined FIX session
     * profile identifier and makes the message garbled, independently of the version this session is configured with
     * (a well-formed but unexpected version is a {@link WrongBeginStringException} instead, see
     * {@link #finalizeMessageParsingOnChecksum}).
     */
    private static int[] definedBeginStringHashes() {
        List<byte[]> beginStrings = new ArrayList<>();
        for (FixRegularVersion fixVersion : FixRegularVersion.values()) {
            beginStrings.add(fixVersion.getBeginString());
        }
        for (FixtVersion fixtVersion : FixtVersion.values()) {
            beginStrings.add(fixtVersion.getBeginString());
        }
        return beginStrings.stream().mapToInt(beginString -> Hashing.hash(beginString, 0, beginString.length)).distinct().toArray();
    }

    /**
     * Parses the given buffer slice as a UTC timestamp, returning its epoch nanos, or 0 if the value is malformed
     * (in which case the standard field-decoding path emits a Reject for the bad value).
     */
    private static long tryParseEpochNanos(byte[] buffer, int offset, int length) {
        try {
            return UtcDateTimeSerde.deserializeToEpochNanos(buffer, offset, length);
        } catch (RuntimeException malformedTimestamp) {
            return 0;
        }
    }

    public void reset() {
        messageParsingState.reset();
    }

    private void resizeLogFragmentBufferIfNeeded(ByteBuffer message) {
        if (logFragmentBuffer.remaining() < message.remaining()) {
            ByteBuffer newFragment = ByteBuffer.allocate(logFragmentBuffer.capacity() + message.remaining());
            newFragment.put(logFragmentBuffer.flip());
            logFragmentBuffer = newFragment;
        }
    }

    private void bufferLogFragment(ByteBuffer message, int limit) {
        if (fixMessagesLogger.isLoggingIncoming()) {
            resizeLogFragmentBufferIfNeeded(message);
            int position = message.position();
            int currentLimit = message.limit();
            logFragmentBuffer.put(message.limit(limit));
            message.limit(currentLimit).position(position);
        }
    }

    public void parseMessages(ByteBuffer message, Function<MessageType, FixMessageDecoder> fixMessageDecoderProvider) throws DecodingException {
        parseMessages(message, fixMessageDecoderProvider, DISABLED_CURRENT_SEQ_NUM_CHECK, 0);
    }

    public void parseMessages(ByteBuffer message, Function<MessageType, FixMessageDecoder> fixMessageDecoderProvider, LongSupplier currentSequenceNumber, long localReceiveTimeInNanos) throws DecodingException {
        UTCTime localReceiveTime = clock.now();
        ChecksumCalculator checksumCalculatorForParsing = checksumCalculator;
        int limit = message.limit();
        byte[] messageContent = message.array();
        int currentPosition = message.position();
        int nextEqualsPosition;
        int nextDelimiterPosition;
        int fieldTagLen;
        int fieldTag;
        while (currentPosition <= limit) {
            try {
                nextEqualsPosition = findNextPosition(messageContent, currentPosition, limit, EQUALS);
                if (hasNotEnoughData(message, nextEqualsPosition, currentPosition)) {
                    return;
                }
                nextDelimiterPosition = getNextDelimiterPosition(messageContent, nextEqualsPosition, limit);
                if (hasNotEnoughData(message, nextDelimiterPosition, currentPosition)) {
                    return;
                }

                fieldTagLen = nextEqualsPosition - currentPosition;
                try {
                    fieldTag = IntSerde.deserializeUnsigned(messageContent, currentPosition, fieldTagLen);
                } catch (IllegalFieldValueException ex) {
                    int resyncPosition = garbledMessageResyncPosition(currentPosition, limit);
                    if (resyncPosition == NOT_SET_POSITION) {
                        throw ex;
                    }
                    messageParsingState.markAsGarbled("tag is not a number: " + ex.getMessage());
                    deserializationContext.clean();
                    currentPosition = resyncPosition;
                    continue;
                }
                deserializationContext.setup(messageContent, nextEqualsPosition + 1, nextDelimiterPosition - nextEqualsPosition - 1);
                switch (fieldTag) {
                    case CoreFields.POSS_DUP_FLAG:
                        messageParsingState.possDupFlag = BooleanSerde.deserialize(deserializationContext);
                        break;
                    case CoreFields.POSS_RESEND:
                        messageParsingState.possResend = BooleanSerde.deserializeLenient(deserializationContext);
                        break;
                    case CoreFields.MESSAGE_SEQ_NUM:
                        messageParsingState.msgSeqNum = LongSerde.deserializeUnsigned(deserializationContext);
                        break;
                    case CoreFields.ORIG_SENDING_TIME:
                        // only present on PossDup retransmissions, parse eagerly. A non-negative value means the field
                        // was received (0 when malformed, which the normal field-decoding path then rejects)
                        messageParsingState.origSendingTimeNanos =
                                tryParseEpochNanos(messageContent, nextEqualsPosition + 1, nextDelimiterPosition - nextEqualsPosition - 1);
                        break;
                    case CoreFields.SENDING_TIME:
                        // do not decode SendingTime on every message: just remember where it is, it is only parsed
                        // lazily when actually needed (accuracy validation below, or the OrigSendingTime check)
                        messageParsingState.sendingTimeOffset = nextEqualsPosition + 1;
                        messageParsingState.sendingTimeLength = nextDelimiterPosition - nextEqualsPosition - 1;
                        if (maxSendingTimeInNanos > 0) {
                            validateSendingTime(messageContent);
                        }
                }

                nextDelimiterPosition++;
                if (messageParsingState.isDecoderNotReady()) {
                    checksumCalculatorForParsing.computeChecksum(currentPosition, nextDelimiterPosition, messageContent, messageParsingState);
                    processRequiredHeaderFields(currentPosition, fixMessageDecoderProvider, fieldTag, fieldTagLen, localReceiveTimeInNanos, localReceiveTime);
                } else if (fieldTag == CoreFields.CHECKSUM) {
                    finalizeMessageParsingOnChecksum(message, nextDelimiterPosition, checksumCalculatorForParsing, currentSequenceNumber);
                    if (nextDelimiterPosition == limit) {
                        // all bytes processed immediately return
                        deserializationContext.clean();
                        return;
                    }
                } else {
                    checksumCalculatorForParsing.computeChecksum(currentPosition, nextDelimiterPosition, messageContent, messageParsingState);
                    messageParsingState.fieldProcessor.processField(this, fieldTagLen, fieldTag);
                }
                deserializationContext.clean();
                currentPosition = nextDelimiterPosition;
            } catch (Exception ex) {
                // terminal parsing exception, disconnecting session
                int startPosition = Math.max(messageParsingState.beginStringPosition, 0);
                int length = Math.min(limit - startPosition, 2048);
                DecodingException dcEx = ex instanceof DecodingException ? (DecodingException) ex :
                        new DecodingException("Decoding exception on message: " + new String(messageContent, startPosition, length, SerDe.CHARSET), ex);
                if (messageParsingState.isDecoderReady()) {
                    messageParsingState.markAsFailed(dcEx);
                    messageParsingState.completeDecoding();
                }
                throw dcEx;
            }
        }
    }

    private int garbledMessageResyncPosition(int currentPosition, int limit) {
        if (messageParsingState.isDecoderNotReady() || messageParsingState.bodyLength <= 0) {
            return NOT_SET_POSITION;
        }
        int remainingBodyBytes = messageParsingState.bodyLength - messageParsingState.bodyLengthActual;
        if (remainingBodyBytes <= 0) {
            return NOT_SET_POSITION;
        }
        int checksumFieldPosition = currentPosition + remainingBodyBytes;
        if (checksumFieldPosition + CHECKSUM_FIELD_LENGTH > limit + 1) {
            // the message is not fully buffered yet, so where its CheckSum(10) sits cannot be confirmed
            return NOT_SET_POSITION;
        }
        return checksumFieldPosition;
    }

    /**
     * The SendingTimeThreshold check of section 4.2.3 of the FIX Session Layer specification: the tolerance is
     * two-sided, a SendingTime(52) dated in the future being as much of an accuracy problem as a stale one — it is
     * how a peer whose clock runs ahead of this session's shows up. Both branches allocate, and both are the
     * rejection path; the check itself costs one comparison per side on a message that is fine.
     */
    private void validateSendingTime(byte[] buffer) {
        long sendingTimeNanos = UtcDateTimeSerde.deserializeToEpochNanos(buffer,
                messageParsingState.sendingTimeOffset, messageParsingState.sendingTimeLength);

        long nowEpochNanos = clock.nowEpochNanos();
        if (sendingTimeNanos + maxSendingTimeInNanos < nowEpochNanos) {
            long timeDiffInMillis = TimeUnit.NANOSECONDS.toMillis(nowEpochNanos - sendingTimeNanos);
            messageParsingState.onReject("Sending time accuracy problem, received message is " + timeDiffInMillis
                            + " ms old, max allowed sending time age is " + maxSendingTimeInNanos / 1_000_000L + " ms", CoreFields.SENDING_TIME,
                    SessionRejectReasonCodes.SENDING_TIME_ACCURACY_PROBLEM, null);
        } else if (sendingTimeNanos > nowEpochNanos + maxSendingTimeInNanos) {
            long timeDiffInMillis = TimeUnit.NANOSECONDS.toMillis(sendingTimeNanos - nowEpochNanos);
            messageParsingState.onReject("Sending time accuracy problem, received message is dated " + timeDiffInMillis
                            + " ms in the future, max allowed sending time deviation is " + maxSendingTimeInNanos / 1_000_000L + " ms",
                    CoreFields.SENDING_TIME, SessionRejectReasonCodes.SENDING_TIME_ACCURACY_PROBLEM, null);
        }
    }

    private void processField(int fieldTagLen, int fieldTag) {
        messageParsingState.cumulateBodyLength(fieldTagLen, deserializationContext.getLength(), fieldTag);
        try {
            callDecoder(fieldTag, messageParsingState.fixMessageDecoder);
        } catch (IllegalFieldValueException ex) {
            messageParsingState.onReject(ex.getMessage(), fieldTag, ex.getSessionRejectReasonCode(), null);
        } catch (Exception ex) {
            messageParsingState.onReject(
                    new DecodingException("Application level decoding exception", ex), "Application level decoding exception",
                    fieldTag, SessionRejectReasonCodes.OTHER, BusinessRejectReasonCodes.APPLICATION_NOT_AVAILABLE);
            log.error("Failed to process message {} field {} on session {}", messageParsingState.messageType, fieldTag, messageParsingState.getFixSessionId(), ex);
            if (fixMessagesLogger.isLoggingEvents()) {
                try {
                    fixMessagesLogger.logEvent(clock.now(), "Failed to process field %s:%s: %s", messageParsingState.messageType, fieldTag, ex.getMessage());
                } catch (FixMessagesLogger.LoggingException ex2) {
                    log.info("Failed to log event", ex2);
                }
            }
        }
    }

    private void processRequiredHeaderFields(int currentPosition, Function<MessageType, FixMessageDecoder> fixMessageDecoderProvider, int fieldTag,
                                             int fieldTagLen, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        garbledMessageDetector.checkHeaderField(messageParsingState, fieldTag, deserializationContext);
        if (fieldTag == CoreFields.BEGIN_STRING) {
            messageParsingState.beginStringPosition = currentPosition;
            messageParsingState.setLocalReceiveTime(localReceiveTimeInNanos, localReceiveTime);
        } else if (fieldTag == CoreFields.BODY_LENGTH) {
            messageParsingState.bodyLength = IntSerde.deserializeUnsigned(deserializationContext);
        } else if (fieldTag == CoreFields.MESSAGE_TYPE) {
            MessageType messageType = messageTypeRegistry.find(Hashing.hash(deserializationContext));
            if (messageType != null) {
                messageParsingState.onDecodingStart(messageType, fixMessageDecoderProvider.apply(messageType), localReceiveTimeInNanos);
            } else {
                messageParsingState.messageType = MessageType.of(StringSerde.instance().deserialize(deserializationContext), false);
                messageParsingState.fixMessageDecoder = VoidDecoder.getInstance(messageParsingState.messageType);
                if (messageParsingState.messageType.isUserDefined()) {
                    // a user defined MsgType(35) is a valid one, it is merely not registered here. Section 4.5.4 of
                    // the FIX Session Layer specification asks for such a message to be forwarded to the trading
                    // application for a business level rejection rather than being rejected at the session layer
                    messageParsingState.onReject("Message type not supported", CoreFields.MESSAGE_TYPE,
                            SessionRejectReasonCodes.INVALID_MSGTYPE, BusinessRejectReasonCodes.UNSUPPORTED_MESSAGE_TYPE);
                } else {
                    // neither a MsgType(35) of the dictionary nor a user defined one: it is not a valid MsgType at
                    // all, so there is no application to hand it over to and the session layer rejects it
                    messageParsingState.onReject("Invalid MsgType", CoreFields.MESSAGE_TYPE,
                            SessionRejectReasonCodes.INVALID_MSGTYPE, null);
                }
            }
            messageParsingState.cumulateBodyLength(fieldTagLen, deserializationContext.getLength(), fieldTag);
            messageParsingState.fixMessageDecoder.onField(fieldsRegistry.find(fieldTag), deserializationContext);
        }
    }

    private void finalizeMessageParsingOnChecksum(ByteBuffer message, int nextDelimiterPosition,
                                                  ChecksumCalculator checksumCalculatorForParsing, LongSupplier currentSequenceNumber) {
        messageParsingState.processCurrentGroupFieldBeforeDecodeIfNeeded(checksumField);

        if (messageParsingState.bodyLengthActual != messageParsingState.bodyLength) {
            // an incorrect BodyLength(9) byte count always makes the message garbled: a Reject would reference a
            // RefSeqNum(45) read from a message whose framing was just proven untrustworthy, and would consume the
            // sequence number of a message the peer could still retransmit intact
            messageParsingState.markAsGarbled("BodyLength(9) is " + messageParsingState.bodyLength
                    + " but computed " + messageParsingState.bodyLengthActual);
        }
        // the CheckSum(10) is the other garbled condition the parser gets for free, from a comparison it already
        // makes. Validated here rather than with the other validations below so that a garbled message is caught
        // before anything is decided about it.
        checksumCalculatorForParsing.validate(deserializationContext, messageParsingState);

        if (messageParsingState.isGarbled()) {
            // the message is disregarded as a whole: nothing is answered to the peer (any Reject collected while
            // decoding it is dropped) and the session leaves NextNumIn untouched, so the peer's next message shows
            // up as a sequence gap and drives the usual recovery
            messageParsingState.markAsGarbled();
        } else {
            validateMessageAndDetectSeqNumGap(message, nextDelimiterPosition, currentSequenceNumber);
        }

        MessageType messageType = messageParsingState.messageType;
        if (messageType.isAdmin()) {
            // always log admin message first before calling onDecoded may trigger events that makes it difficult to follow sequence of events
            logMessageIfNeeded(messageParsingState.localReceiveTime, messageType, message, nextDelimiterPosition);
        }
        messageParsingState.completeDecoding();

        if (!messageType.isAdmin()) {
            // for non admin message types we log it after calling onDecoded() to make sure we don't add additional latency for nothing
            logMessageIfNeeded(messageParsingState.localReceiveTime, messageType, message, nextDelimiterPosition);
        }
        message.position(nextDelimiterPosition);
        messageParsingState.onMessageDecoded(nextDelimiterPosition);
    }

    private void validateMessageAndDetectSeqNumGap(ByteBuffer message, int nextDelimiterPosition, LongSupplier currentSequenceNumber) {
        if (expectedBeginStringHash != 0) {
            byte[] buffer = message.array();
            int beginStringValueStart = messageParsingState.beginStringPosition + 2; // skip "8="
            int beginStringValueEnd = findNextPosition(buffer, beginStringValueStart, nextDelimiterPosition, FIELD_SEPARATOR_BYTE);
            int beginStringValueLength = beginStringValueEnd - beginStringValueStart;
            if (Hashing.hash(buffer, beginStringValueStart, beginStringValueLength) != expectedBeginStringHash) {
                String receivedBeginString = new String(buffer, beginStringValueStart, beginStringValueLength, SerDe.CHARSET);
                String expectedBeginString = new String(messageParsingState.getFixSessionId().getFixVersion().getBeginString(), SerDe.CHARSET);
                messageParsingState.markAsFailed(new WrongBeginStringException(expectedBeginString, receivedBeginString));
            }
        }

        if (messageParsingState.possDupFlag && messageParsingState.origSendingTimeNanos < 0) {
            messageParsingState.onReject("OrigSendingTime is required when PossDupFlag is Y",
                    CoreFields.ORIG_SENDING_TIME, SessionRejectReasonCodes.REQUIRED_TAG_MISSING, null);
        } else if (messageParsingState.possDupFlag && messageParsingState.sendingTimeLength > 0) {
            // lazily decode SendingTime only now that a PossDup message carries an OrigSendingTime to compare against
            long sendingTimeNanos = tryParseEpochNanos(message.array(), messageParsingState.sendingTimeOffset, messageParsingState.sendingTimeLength);
            if (sendingTimeNanos > 0 && messageParsingState.origSendingTimeNanos > sendingTimeNanos) {
                messageParsingState.onReject("OrigSendingTime is after SendingTime",
                        CoreFields.ORIG_SENDING_TIME, SessionRejectReasonCodes.SENDING_TIME_ACCURACY_PROBLEM, null);
            }
        }

        long expectedMsgSeqNum = currentSequenceNumber.getAsLong();
        if (messageParsingState.msgSeqNum != expectedMsgSeqNum
                && expectedMsgSeqNum != DISABLED_CURRENT_SEQ_NUM_CHECK.getAsLong()
                && !messageParsingState.fixMessageDecoder.ignoresIncomingSequenceNumber()) {
            // The message exactly as it arrived, BeginString(8) included, so that a session holding on to an out of
            // sequence message can feed it back through this very parser
            int beginStringPosition = messageParsingState.beginStringPosition;
            byte[] rawMessage = new byte[nextDelimiterPosition - beginStringPosition];
            int currentMessagePosition = message.position();
            message.position(beginStringPosition);
            message.get(rawMessage).position(currentMessagePosition);
            messageParsingState.markAsFailed(new WrongSeqNumException(messageParsingState.msgSeqNum, expectedMsgSeqNum,
                    messageParsingState.possDupFlag, rawMessage));
        } else {
            messageParsingState.validate();
        }
    }

    private boolean hasNotEnoughData(ByteBuffer message, int nextSearchedCharPosition, int currentPosition) throws IllegalParsingStateException {
        if (nextSearchedCharPosition == -1) {
            // not enough or garbage data 8=FIX.4.4 = less than 10 chars we fail only if we have received more and nothing contains begin string
            // cannot use message.remaining() as message.position() is only updated when message is fully or partially parsed
            int remainingByteToRead = message.limit() - currentPosition;
            if (!messageParsingState.isBeginStringReceived() && remainingByteToRead > 10) {
                int maxDebugLength = Math.min(message.remaining(), 64);
                throw new IllegalParsingStateException("Unable to find FIX BeginString field in network payload: " + new String(message.array(), currentPosition, maxDebugLength, SerDe.CHARSET));
            } else if (messageParsingState.isBeginStringReceived() && remainingByteToRead > MAX_FIELD_SIZE) {
                int maxDebugLength = Math.min(message.remaining(), 64);
                throw new IllegalParsingStateException("Unable to find field value delimiter within " + MAX_FIELD_SIZE + " bytes: " + new String(message.array(), currentPosition, maxDebugLength, SerDe.CHARSET));
            }
            bufferLogFragment(message, currentPosition);
            if (messageParsingState.isMsgSeqNumNotReceived()) {
                // when seq num field is not processed unfortunately we must reprocess the beginning of the entire message since
                // if we receive an out of sequence message, we must be able to re process it in fully to store all elements in memory
                // we may also be out of readable bytes just before the 8=FIX so messageParsingState.beginStringPosition could be -1
                message.position(messageParsingState.beginStringPosition != MessageParsingState.NOT_SET ? messageParsingState.beginStringPosition : currentPosition);
                if (messageParsingState.isDecoderReady()) {
                    messageParsingState.markAsFailed(NOT_ENOUGH_DATA_EXCEPTION);
                    messageParsingState.completeDecoding();
                }
                messageParsingState.reset();
            } else {
                message.position(currentPosition);
            }
            return true;
        }
        return false;
    }

    private void logMessageIfNeeded(UTCTime localReceiveTime, MessageType msgType, ByteBuffer message, int bufferLogLimit) {
        if (fixMessagesLogger.isLoggingIncoming()) {
            if (logFragmentBuffer.position() == 0) {
                logIncoming(localReceiveTime, msgType, message, bufferLogLimit);
            } else {
                bufferLogFragment(message, bufferLogLimit);
                logIncoming(localReceiveTime, msgType, logFragmentBuffer.flip(), logFragmentBuffer.limit());
                logFragmentBuffer.clear();
            }
        }
    }

    private void logIncoming(UTCTime localReceiveTime, MessageType msgType, ByteBuffer message, int bufferLogLimit) {
        int currentLimit = message.limit();
        message.limit(bufferLogLimit);
        try {
            fixMessagesLogger.logIncoming(localReceiveTime, msgType, message);
        } catch (Exception ex) {
            log.warn("Failed to log incoming FIX message", ex);
        }
        message.limit(currentLimit);
    }

    private int getNextDelimiterPosition(byte[] messageContent, int nextEqualsPosition, int limit) {
        if (messageParsingState.dataFieldLength != MessageParsingState.NOT_SET) {
            int nextPosition = nextEqualsPosition + messageParsingState.dataFieldLength + 1;
            if (nextPosition >= messageContent.length) {
                // tricky case where we can receive a non full message in IO buffer
                // with a datafield length bigger than remaining bytes in IO buffer
                return -1;
            }
            messageParsingState.dataFieldLength = MessageParsingState.NOT_SET;
            return nextPosition;
        }
        return findNextPosition(messageContent, nextEqualsPosition, limit, FIELD_SEPARATOR_BYTE);
    }

    private void callDecoder(int fieldTag, FixMessageDecoder fixMessageDecoder) {
        FixField targetField = fieldsRegistry.find(fieldTag);
        if (targetField == null) {
            if (!FixField.isUserDefined(fieldTag)) {
                messageParsingState.onReject("Invalid tag number", fieldTag, SessionRejectReasonCodes.INVALID_TAG_NUMBER, null);
            } else {
                messageParsingState.onReject("Undefined tag", fieldTag, SessionRejectReasonCodes.UNDEFINED_TAG, null);
            }
            return;
        }
        if (validateFieldsOutOfOrder) {
            int fieldLocationOrdinal = targetField.getLocation().ordinal();
            if (fieldLocationOrdinal < messageParsingState.maxFieldLocationOrdinal) {
                messageParsingState.onReject("Tag specified out of required order", targetField.getCode(),
                        SessionRejectReasonCodes.TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER, null);
            } else {
                messageParsingState.maxFieldLocationOrdinal = fieldLocationOrdinal;
            }
        }
        if (compIdValidator != null && !compIdValidator.validate(fieldTag, deserializationContext)) {
            messageParsingState.onReject(wrongCompIdMessage(fieldTag), targetField.getCode(),
                    SessionRejectReasonCodes.COMPID_PROBLEM, null);
            return;
        }

        if (deserializationContext.getLength() > 0) {
            messageParsingState.processCurrentGroupFieldBeforeDecodeIfNeeded(targetField);
            if (targetField.getType().equals(FieldType.NUMINGROUP)) {
                messageParsingState.enterGroup(targetField, ensurePositiveGroupLen(fieldTag));
            } else if (targetField.getType().equals(FieldType.LENGTH)) {
                messageParsingState.dataFieldLength = IntSerde.deserializeUnsigned(deserializationContext);
            }
            fixMessageDecoder.onField(targetField, deserializationContext);
        } else if (validateFieldsHaveValues) {
            messageParsingState.onReject("Empty tag value", targetField.getCode(), SessionRejectReasonCodes.TAG_SPECIFIED_WITHOUT_A_VALUE, null);
        }
    }

    /**
     * Text(58) of a CompID reject. Only ever reached once a field has been found wrong, so it is free to build the
     * strings the happy path deliberately avoids. The third party fields name no expected value: there is a set of
     * them, and listing it would put the session's routing configuration on the wire.
     */
    private String wrongCompIdMessage(int fieldTag) {
        String received = StringSerde.instance().deserialize(deserializationContext);
        switch (fieldTag) {
            case CoreFields.TARGET_COMP_ID:
                return String.format("Wrong TARGET_COMP_ID, expecting %s but got %s",
                        messageParsingState.getFixSessionId().getTargetCompID().getValue(), received);
            case CoreFields.ON_BEHALF_OF_COMP_ID:
                return String.format("Wrong ON_BEHALF_OF_COMP_ID, %s is not an expected value for this session", received);
            case CoreFields.DELIVER_TO_COMP_ID:
                return String.format("Wrong DELIVER_TO_COMP_ID, %s is not an expected value for this session", received);
            default:
                return String.format("Wrong SENDER_COMP_ID, expecting %s but got %s",
                        messageParsingState.getFixSessionId().getSenderCompID().getValue(), received);
        }
    }

    private int ensurePositiveGroupLen(int fieldTag) {
        int groupLen = IntSerde.deserializeUnsigned(deserializationContext);
        if (groupLen <= 0) {
            messageParsingState.onReject("NumInGroup value must be greater than zero", fieldTag, SessionRejectReasonCodes.INCORRECT_NUM_IN_GROUP_COUNT_FOR_REPEATING_GROUP, null);
        }
        return groupLen;
    }

    private int findNextPosition(byte[] messageContent, int startPosition, int limit, byte toFind) {
        for (int i = startPosition; i < limit; i++) {
            if (messageContent[i] == toFind) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Enforces the framing rules the session layer puts on the first fields of a message (section 4.5.2 "Garbled
     * message processing" of the FIX Session Layer specification): the mandated position of BeginString(8),
     * BodyLength(9) and MsgType(35), and BeginString(8) being a defined FIX session profile identifier. Selected once
     * at construction from {@link FixSessionSettings.ValidationSettings#isDetectGarbledMessages()}: enforcing them
     * costs a check on every header field received plus a hash of every BeginString(8), so when the detection is
     * disabled not a single cycle is spent on it while parsing.
     * <p>
     * The remaining rule, an incorrect BodyLength(9) byte count, is not part of this: it is detected by a comparison
     * the parser makes anyway, so it is always enforced.
     */
    private interface GarbledMessageDetector {

        /**
         * Checks a field received before the decoder is resolved, i.e. one of the first fields of the message.
         */
        void checkHeaderField(MessageParsingState messageParsingState, int fieldTag, SerDe.DeserializationContext deserializationContext);
    }

    /**
     * Decodes the body fields of the message being parsed. Swapped for {@link VoidFieldProcessor} as soon as the
     * message is found to be garbled, so that the remaining fields are skipped without testing for it on every field.
     */
    private interface FieldProcessor {

        void processField(FixMessageParser fixMessageParser, int fieldTagLen, int fieldTag);
    }

    private interface ChecksumCalculator {

        void computeChecksum(int from, int to, byte[] messageContent, MessageParsingState messageParsingState);

        void validate(SerDe.DeserializationContext deserializationContext, MessageParsingState messageParsingState);
    }

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    private static class ActiveGarbledMessageDetector implements GarbledMessageDetector {

        private static final GarbledMessageDetector INSTANCE = new ActiveGarbledMessageDetector();

        private static boolean isDefinedBeginString(int beginStringHash) {
            for (int definedBeginStringHash : DEFINED_BEGIN_STRING_HASHES) {
                if (definedBeginStringHash == beginStringHash) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void checkHeaderField(MessageParsingState messageParsingState, int fieldTag, SerDe.DeserializationContext deserializationContext) {
            int fieldIndex = messageParsingState.headerFieldIndex++;
            if (fieldIndex < MANDATED_FIRST_FIELDS.length && fieldTag != MANDATED_FIRST_FIELDS[fieldIndex]) {
                messageParsingState.markAsGarbled("expecting tag " + MANDATED_FIRST_FIELDS[fieldIndex] + " as field "
                        + (fieldIndex + 1) + " of the message but got tag " + fieldTag);
            } else if (fieldTag == CoreFields.BEGIN_STRING && !isDefinedBeginString(Hashing.hash(deserializationContext))) {
                messageParsingState.markAsGarbled("BeginString(8) " + StringSerde.instance().deserialize(deserializationContext)
                        + " is not a defined FIX session profile identifier");
            }
        }

    }

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    private static class VoidGarbledMessageDetector implements GarbledMessageDetector {

        private static final GarbledMessageDetector INSTANCE = new VoidGarbledMessageDetector();

        @Override
        public void checkHeaderField(MessageParsingState messageParsingState, int fieldTag, SerDe.DeserializationContext deserializationContext) {
            // nothing to do
        }
    }

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    private static class ActiveFieldProcessor implements FieldProcessor {

        private static final FieldProcessor INSTANCE = new ActiveFieldProcessor();

        @Override
        public void processField(FixMessageParser fixMessageParser, int fieldTagLen, int fieldTag) {
            fixMessageParser.processField(fieldTagLen, fieldTag);
        }
    }

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    private static class VoidFieldProcessor implements FieldProcessor {

        private static final FieldProcessor INSTANCE = new VoidFieldProcessor();

        @Override
        public void processField(FixMessageParser fixMessageParser, int fieldTagLen, int fieldTag) {
            // the message is garbled and disregarded as a whole, its fields are not interpreted at all. Decoding them
            // could even derail the framing itself: a stray LENGTH field would make the parser skip the number of
            // bytes it announces. Only the Checksum(10) delimiting the message is still looked for.
        }
    }

    private static class ActiveChecksumCalculator implements ChecksumCalculator {

        /**
         * The FIX session layer mandates a CheckSum(10) value of exactly three characters, zero padded.
         */
        private static final int CHECKSUM_VALUE_LENGTH = 3;
        private static final ChecksumCalculator INSTANCE = new ActiveChecksumCalculator();

        private ActiveChecksumCalculator() {

        }

        @Override
        public void computeChecksum(int from, int to, byte[] messageContent, MessageParsingState messageParsingState) {
            for (int i = from; i < to; i++) {
                messageParsingState.checksum += messageContent[i];
            }
        }

        /**
         * Section 4.5.2 "Garbled message processing" of the FIX Session Layer specification makes a message garbled
         * when its CheckSum(10) "is not the last tag or contains an incorrect value", so a checksum problem is not
         * answered with a Reject: the message is disregarded and the peer is left to retransmit it.
         */
        @Override
        public void validate(SerDe.DeserializationContext deserializationContext, MessageParsingState messageParsingState) {
            if (deserializationContext.getLength() != CHECKSUM_VALUE_LENGTH) {
                messageParsingState.markAsGarbled("CheckSum(10) value must be " + CHECKSUM_VALUE_LENGTH
                        + " characters long but got " + StringSerde.instance().deserialize(deserializationContext));
                return;
            }
            int computedChecksum = messageParsingState.checksum & 0xFF;
            int checksum = IntSerde.deserializeUnsigned(deserializationContext);
            if (computedChecksum != checksum) {
                messageParsingState.markAsGarbled("CheckSum(10) is " + checksum + " but computed " + computedChecksum);
            }
        }
    }

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    private static class VoidChecksumCalculator implements ChecksumCalculator {

        private static final ChecksumCalculator INSTANCE = new VoidChecksumCalculator();

        @Override
        public void computeChecksum(int from, int to, byte[] messageContent, MessageParsingState messageParsingState) {
            // nothing to do
        }

        @Override
        public void validate(SerDe.DeserializationContext deserializationContext, MessageParsingState messageParsingState) {
            // nothing to do
        }
    }

    private static class MessageParsingState {

        private static final int NOT_SET = -1;
        private static final long NOT_SET_LONG = -1L;
        private final FixGroupParsingState[] fixGroupParsingStates;
        @Getter
        private final FixSessionId fixSessionId;
        private final MessageFieldsRegistry messageFieldsRegistry;
        private final FixMessageParserEventsListener fixMessageParserEventsListener;
        private final int maxMessageSize;
        private final boolean validateFieldsOutOfOrder;
        private FixGroupParsingState currentGroupParsingState;
        private int maxFieldLocationOrdinal;
        private int beginStringPosition = NOT_SET;
        private int dataFieldLength = NOT_SET;
        private int currentGroupIndex = NOT_SET;
        private int bodyLength = NOT_SET;
        private int bodyLengthActual;
        private int checksum;
        private int headerFieldIndex;
        private GarbledMessageException garbledMessageException;
        private FieldProcessor fieldProcessor = ActiveFieldProcessor.INSTANCE;
        private long msgSeqNum = NOT_SET_LONG;
        private long localReceiveTimeInNanos;
        private UTCTime localReceiveTime;
        private boolean possDupFlag;
        private boolean possResend;
        private int sendingTimeOffset;
        private int sendingTimeLength;
        private long origSendingTimeNanos = -1;
        private FixMessageDecoder fixMessageDecoder;
        private FixMessageDecoder failedDecoder;
        private DecodingException failureException;
        private MessageType messageType;
        private List<MessageReject> messageRejects;
        private MessageFieldsRegistry.FixMessageFields fixMessageFieldsOrder;
        private FixField currentGroup;

        public MessageParsingState(FixMessageParserEventsListener fixMessageParserEventsListener, FixSessionId fixSessionId, MessageFieldsRegistry messageFieldsRegistry,
                                   Integer maxMessageSize, boolean validateFieldsOutOfOrder) {
            this.fixSessionId = fixSessionId;
            this.maxMessageSize = maxMessageSize == null ? Integer.MAX_VALUE : maxMessageSize;
            this.messageFieldsRegistry = messageFieldsRegistry;
            this.validateFieldsOutOfOrder = validateFieldsOutOfOrder;
            this.fixGroupParsingStates = new FixGroupParsingState[MAX_INNER_GROUP];
            for (int i = 0; i < fixGroupParsingStates.length; i++) {
                fixGroupParsingStates[i] = new FixGroupParsingState(this);
            }
            this.fixMessageParserEventsListener = fixMessageParserEventsListener;
        }

        void cumulateBodyLength(int fieldTagLen, int valueLength, int fieldTag) {
            bodyLengthActual += fieldTagLen + valueLength + 2; // 2 for equals and delimiter
            if (bodyLengthActual > maxMessageSize && failedDecoder == null) {
                onReject("max message size reached", fieldTag, SessionRejectReasonCodes.OTHER, null);
            }
        }

        void setLocalReceiveTime(long localReceiveTimeInNanos, UTCTime localReceiveTime) {
            this.localReceiveTimeInNanos = localReceiveTimeInNanos;
            this.localReceiveTime = localReceiveTime;
        }

        boolean isDecoderNotReady() {
            return fixMessageDecoder == null;
        }

        boolean isDecoderReady() {
            return fixMessageDecoder != null;
        }

        boolean isMsgSeqNumNotReceived() {
            return msgSeqNum == NOT_SET_LONG;
        }

        boolean isBeginStringReceived() {
            return beginStringPosition != NOT_SET;
        }

        void onReject(String message, int refTagId, SessionRejectReasonCodes sessionRejectReasonCode, BusinessRejectReasonCodes businessRejectReasonCode) {
            onReject(new RejectedMessageException(message), message, refTagId, sessionRejectReasonCode, businessRejectReasonCode);
        }

        void onReject(DecodingException ex, String message, int refTagId, SessionRejectReasonCodes sessionRejectReasonCode, BusinessRejectReasonCodes businessRejectReasonCode) {
            if (messageRejects == null) {
                messageRejects = new ArrayList<>();
            }
            messageRejects.add(new MessageReject(message, refTagId, sessionRejectReasonCode, businessRejectReasonCode));
            markAsFailed(ex);
        }

        boolean isGarbled() {
            return garbledMessageException != null;
        }

        /**
         * Records that the message breaches the session layer framing rules. Only the first breach detected is kept:
         * once a message is garbled the remaining fields are parsed but their outcome is discarded anyway.
         */
        void markAsGarbled(String reason) {
            if (garbledMessageException == null) {
                garbledMessageException = new GarbledMessageException(reason);
                fieldProcessor = VoidFieldProcessor.INSTANCE;
            }
        }

        /**
         * Rolls the message back as garbled. Unlike {@link #markAsFailed(DecodingException)} the garbled cause
         * supersedes any earlier failure cause and drops the pending rejects: a garbled message is disregarded as a
         * whole, so nothing about it is reported back to the peer.
         */
        void markAsGarbled() {
            messageRejects = null;
            if (failedDecoder == null) {
                // the decoder is always resolved by the time the Checksum field is reached, so the message is
                // guaranteed to fail rather than decode
                failedDecoder = fixMessageDecoder;
            }
            failureException = garbledMessageException;
            fixMessageDecoder = VoidDecoder.getInstance(messageType);
        }

        void markAsFailed(DecodingException ex) {
            if (failedDecoder == null) {
                failureException = ex;
                failedDecoder = fixMessageDecoder;
            }
            fixMessageDecoder = VoidDecoder.getInstance(messageType);
        }

        void validate() {
            try {
                fixMessageDecoder.validate();
            } catch (ValidationException ex) {
                if (ex.getValidationExceptions() != null) {
                    for (ValidationException e : ex.getValidationExceptions()) {
                        onReject(e, e.getMessage(), e.getField().getCode(), e.getSessionRejectReasonCode(), e.getBusinessRejectReasonCode());
                    }
                } else {
                    onReject(ex, ex.getMessage(), ex.getField().getCode(), ex.getSessionRejectReasonCode(), ex.getBusinessRejectReasonCode());
                }
            }
        }

        void completeDecoding() {
            if (failedDecoder != null) {
                fixMessageParserEventsListener.onMessageDecodingFailed(messageType, failedDecoder, msgSeqNum, failureException, localReceiveTimeInNanos, localReceiveTime);
                return;
            }
            fixMessageParserEventsListener.onMessageDecoded(messageType, fixMessageDecoder, msgSeqNum, possDupFlag, possResend, localReceiveTimeInNanos, localReceiveTime);
        }

        void processRejectsIfNeeded() {
            if (messageRejects != null) {
                fixMessageParserEventsListener.onMessageRejects(messageType, fixMessageDecoder, msgSeqNum, messageRejects);
            }
        }

        void onDecodingStart(MessageType messageType, FixMessageDecoder fixMessageDecoder, long localReceiveTimeInNanos) {
            this.messageType = messageType;
            this.fixMessageDecoder = fixMessageDecoder;
            this.fixMessageParserEventsListener.onMessageDecodingStart(messageType, localReceiveTimeInNanos, localReceiveTime);
            this.fixMessageDecoder.onBegin(localReceiveTimeInNanos, localReceiveTime);
        }

        void onMessageDecoded(int nextDelimiterPosition) {
            processRejectsIfNeeded();
            fixMessageParserEventsListener.onMessageDecodingEnd(messageType, nextDelimiterPosition - beginStringPosition, localReceiveTimeInNanos, localReceiveTime);
            reset();
        }

        void reset() {
            checksum = bodyLengthActual = maxFieldLocationOrdinal =
                    headerFieldIndex = sendingTimeOffset = sendingTimeLength = 0;
            fieldProcessor = ActiveFieldProcessor.INSTANCE;
            msgSeqNum = origSendingTimeNanos = NOT_SET_LONG;
            beginStringPosition = currentGroupIndex = dataFieldLength = bodyLength = NOT_SET;
            garbledMessageException = null;
            fixMessageDecoder = null;
            localReceiveTime = null;
            failedDecoder = null;
            failureException = null;
            messageRejects = null;
            currentGroupParsingState = null;
            fixMessageFieldsOrder = null;
            currentGroup = null;
            messageType = null;
            possDupFlag = possResend = false;
        }

        void processCurrentGroupFieldBeforeDecodeIfNeeded(FixField field) {
            if (currentGroupParsingState != null && currentGroupParsingState.onGroupField(field)) {
                exitGroup(field);
            }
        }

        private void exitGroup(FixField field) {
            currentGroup = currentGroupParsingState.getParentGroup();
            FixField destroyedGroup = currentGroupParsingState.onGroupEnd();
            fixMessageDecoder.onGroupEnd(currentGroup, destroyedGroup);
            if (--currentGroupIndex >= 0) {
                // we are maybe in a nested group
                currentGroupParsingState = fixGroupParsingStates[currentGroupIndex];
                if (currentGroupParsingState.onGroupField(field)) {
                    exitGroup(field);
                }
            } else {
                currentGroupParsingState = null;
            }
        }

        void enterGroup(FixField group, int entriesCount) {
            if (fixMessageFieldsOrder == null) {
                fixMessageFieldsOrder = messageFieldsRegistry.getFixMessageFields(messageType);
                if (fixMessageFieldsOrder == null) {
                    // a MsgType(35) the dictionary does not define, already rejected: it has no groups to follow
                    return;
                }
            }
            fixMessageDecoder.onGroupStart(currentGroup, group, entriesCount);
            if (currentGroupIndex >= 0) {
                fixGroupParsingStates[currentGroupIndex].onNestedGroupStart(group);
            }
            currentGroupParsingState = fixGroupParsingStates[++currentGroupIndex]
                    .onGroupStart(currentGroup, group, entriesCount, fixMessageDecoder, fixMessageFieldsOrder.getGroupOrderedFields(group),
                            validateFieldsOutOfOrder ? fixMessageFieldsOrder : null);
            currentGroup = group;
        }
    }

    /**
     * Checks the CompID fields of a received message against what the session expects: SenderCompID(49) and
     * TargetCompID(56), which say who the peer is, and OnBehalfOfCompID(115) and DeliverToCompID(128), which say who
     * the peer is routing for when the session carries third party traffic (section 6.2 of the FIX Session Layer
     * specification).
     * <p>
     * The four checks are configured independently and this holds all of them, so that the parsing loop keeps the
     * single null test it has always had rather than gaining one per check. Values are compared as hashes, no String
     * being built for a field that turns out to be correct.
     */
    private static final class CompIdValidator {

        private final boolean validateSenderAndTarget;
        private final int senderCompIdHash;
        private final int targetCompIdHash;
        /**
         * Hashes of the accepted OnBehalfOfCompID(115) values, null when the field is not checked.
         */
        private final int[] onBehalfOfCompIdHashes;
        /**
         * Hashes of the accepted DeliverToCompID(128) values, null when the field is not checked.
         */
        private final int[] deliverToCompIdHashes;

        public CompIdValidator(boolean validateSenderAndTarget, String senderCompId, String targetCompId,
                               Collection<String> expectedOnBehalfOfCompIds, Collection<String> expectedDeliverToCompIds) {
            this.validateSenderAndTarget = validateSenderAndTarget;
            this.senderCompIdHash = Hashing.hash(senderCompId);
            this.targetCompIdHash = Hashing.hash(targetCompId);
            this.onBehalfOfCompIdHashes = hashes(expectedOnBehalfOfCompIds);
            this.deliverToCompIdHashes = hashes(expectedDeliverToCompIds);
        }

        private static int[] hashes(Collection<String> compIds) {
            if (compIds == null || compIds.isEmpty()) {
                return null;
            }
            return compIds.stream().mapToInt(Hashing::hash).toArray();
        }

        private static boolean contains(int[] expectedHashes, int hash) {
            for (int expected : expectedHashes) {
                if (expected == hash) {
                    return true;
                }
            }
            return false;
        }

        boolean validate(int fieldTag, SerDe.DeserializationContext deserializationContext) {
            switch (fieldTag) {
                case CoreFields.SENDER_COMP_ID:
                    return !validateSenderAndTarget || Hashing.hash(deserializationContext) == senderCompIdHash;
                case CoreFields.TARGET_COMP_ID:
                    return !validateSenderAndTarget || Hashing.hash(deserializationContext) == targetCompIdHash;
                case CoreFields.ON_BEHALF_OF_COMP_ID:
                    return onBehalfOfCompIdHashes == null || contains(onBehalfOfCompIdHashes, Hashing.hash(deserializationContext));
                case CoreFields.DELIVER_TO_COMP_ID:
                    return deliverToCompIdHashes == null || contains(deliverToCompIdHashes, Hashing.hash(deserializationContext));
                default:
                    return true;
            }
        }
    }


    @RequiredArgsConstructor
    private static class FixGroupParsingState {
        // see https://www.fixtrading.org/standards/tagvalue-online/#message-structure
        private final MessageParsingState messageParsingState;
        private FixField groupField;
        private int groupEntriesCount;
        private FixField startTag;
        private FixField lastDecodedTag;
        private FixMessageDecoder fixMessageDecoder;
        private Collection<FixField> groupFieldOrder;
        // non-null only when out-of-order validation is enabled, used to look up the declared order position of a
        // group member field; the position of the last decoded member in the current entry is tracked to detect
        // fields appearing before one already seen
        private MessageFieldsRegistry.FixMessageFields fixMessageFields;
        private int lastOrderIndexInEntry;
        @Getter
        private FixField parentGroup;

        FixGroupParsingState onGroupStart(FixField parentGroup, FixField groupField, int groupEntriesCount, FixMessageDecoder fixMessageDecoder,
                                          Collection<FixField> groupFieldOrder, MessageFieldsRegistry.FixMessageFields fixMessageFields) {
            this.parentGroup = parentGroup;
            this.groupField = groupField;
            this.groupEntriesCount = groupEntriesCount;
            this.fixMessageDecoder = fixMessageDecoder;
            this.groupFieldOrder = groupFieldOrder;
            this.fixMessageFields = fixMessageFields;
            this.lastOrderIndexInEntry = -1;
            return this;
        }

        void onNestedGroupStart(FixField nestedGroupField) {
            lastDecodedTag = nestedGroupField;
        }

        boolean onGroupField(FixField fieldTag) {
            if (startTag == null) {
                startTag = fieldTag;
                fixMessageDecoder.onGroupEntryStart(parentGroup, groupField, fieldTag);
                onEntryStart(fieldTag);
            } else if (startTag.equals(fieldTag)) {
                fixMessageDecoder.onGroupEntryEnd(parentGroup, groupField, lastDecodedTag);
                if (--groupEntriesCount > 0) {
                    fixMessageDecoder.onGroupEntryStart(parentGroup, groupField, fieldTag);
                    onEntryStart(fieldTag);
                } else {
                    // an extra entry starts after the declared NumInGroup count has been consumed
                    rejectOnInvalidGroupCount();
                }
            } else {
                if (!groupFieldOrder.contains(fieldTag)) {
                    //group finished as processed tag is not in group tags
                    fixMessageDecoder.onGroupEntryEnd(parentGroup, groupField, lastDecodedTag);
                    if (groupEntriesCount > 1) {
                        // the group is terminated while more entries were still expected
                        rejectOnInvalidGroupCount();
                    }
                    groupEntriesCount = 0;
                } else if (fixMessageFields != null) {
                    checkGroupFieldOrder(fieldTag);
                }
            }
            lastDecodedTag = fieldTag;
            return groupEntriesCount == 0;
        }

        private void rejectOnInvalidGroupCount() {
            messageParsingState.onReject("Incorrect NumInGroup count for repeating group",
                    groupField.getCode(), SessionRejectReasonCodes.INCORRECT_NUM_IN_GROUP_COUNT_FOR_REPEATING_GROUP, null);
        }

        private void onEntryStart(FixField fieldTag) {
            if (fixMessageFields != null) {
                lastOrderIndexInEntry = fixMessageFields.getGroupFieldOrder(groupField, fieldTag);
            }
        }

        private void checkGroupFieldOrder(FixField fieldTag) {
            int orderIndex = fixMessageFields.getGroupFieldOrder(groupField, fieldTag);
            if (orderIndex < lastOrderIndexInEntry) {
                messageParsingState.onReject("Repeating group " + groupField.getCode() + " field " + fieldTag.getCode() + " out of order",
                        fieldTag.getCode(), SessionRejectReasonCodes.REPEATING_GROUP_FIELDS_OUT_OF_ORDER, null);
            } else {
                lastOrderIndexInEntry = orderIndex;
            }
        }

        FixField onGroupEnd() {
            FixField toReturn = groupField;
            groupField = null;
            groupEntriesCount = 0;
            startTag = null;
            lastDecodedTag = null;
            fixMessageDecoder = null;
            parentGroup = null;
            fixMessageFields = null;
            lastOrderIndexInEntry = -1;
            return toReturn;
        }
    }
}