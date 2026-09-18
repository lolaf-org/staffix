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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.codec.DecodingException;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.logging.VoidMessageLogger;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageFieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.codec.serde.BooleanSerde;
import org.lolaf.staffix.codec.serde.UtcDateTimeSerde;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Turns a FIX message written as a string - a line pasted out of a log, with {@code |} or SOH between its fields -
 * into the body fields of a message this session can send, and refuses anything its dictionary does not describe.
 * <p>
 * The header and the trailer of the string are thrown away: BeginString(8), BodyLength(9), MsgSeqNum(34),
 * SendingTime(52), the comp ids and CheckSum(10) belong to the message the caller copied, not to the one being sent.
 * Only MsgType(35) is taken from it, to say which message this is, and only body fields survive - the session's own
 * encoder puts a header and a trailer of its own around them.
 * <p>
 * The parsing is the engine's own {@link FixMessageParser} against the session's own dictionary, not a second reading
 * of what a FIX message is: repeating groups, nested groups and data fields whose value may itself contain the
 * separator - RawData(96) and its kind, readable only through their length field - come out right because the parser
 * that reads a peer's messages reads this one too. What that costs is a frame the parser can work with, which
 * {@link #reframe} builds.
 * <p>
 * <b>Validation.</b> A message that does not follow the session's dictionary is refused rather than sent: a tag that is
 * not defined for its message type, a tag the dictionary does not define at all, a required field left out, a tag
 * repeated, a field with no value, an unknown MsgType. The checks are the session's own, through
 * {@link FixMessageDecoderImpl} - the wrapper a session puts around every application decoder - so the answer here and
 * the answer a peer would give are the same one. Only the checks about the frame this class replaces are turned off.
 */
@Slf4j
public class AdminFixMessageTransformer {

    private static final char PIPE = '|';
    private static final String BEGIN_STRING_TAG = "8=";
    private static final String BODY_LENGTH_TAG = "9=";
    private static final String MESSAGE_TYPE_TAG = "35=";
    private static final String CHECKSUM_TAG = "10=";
    private static final byte[] POSS_DUP_FLAG_SET = BooleanSerde.TRUE.getBytes(SerDe.CHARSET);
    private static final String CHECKSUM_PLACEHOLDER = CHECKSUM_TAG + "000" + CoreFields.FIELD_SEPARATOR;

    private final FixSessionId fixSessionId;
    private final MessageFieldsRegistry messageFieldsRegistry;
    private final FieldsRegistry fieldsRegistry;
    private final FixSessionSettings.ValidationSettings validationSettings;
    private final FixMessageParser parser;
    private final String beginString;
    private final List<MessageReject> rejects;
    private final TimeUnit sendingTimeAccuracy;
    private final FixField sendingTime;
    private final FixField origSendingTime;
    private final FixField possDupFlagField;
    private DecodedFixMessageDecoder decodedFixMessageDecoder;
    private int decodedMessagesCount;
    private boolean possDupFlag;
    private boolean sendingTimeCarriedOver;

    public AdminFixMessageTransformer(FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry,
                                      FieldsRegistry fieldsRegistry, FixSessionSettings.ValidationSettings sessionValidationSettings,
                                      Clock clock, TimeUnit sendingTimeAccuracy) {
        this.sendingTimeAccuracy = sendingTimeAccuracy;
        this.sendingTime = fieldsRegistry.find(CoreFields.SENDING_TIME);
        this.origSendingTime = fieldsRegistry.find(CoreFields.ORIG_SENDING_TIME);
        this.possDupFlagField = fieldsRegistry.find(CoreFields.POSS_DUP_FLAG);
        this.fixSessionId = fixSessionId;
        this.fieldsRegistry = fieldsRegistry;
        this.messageFieldsRegistry = MessageFieldsRegistry.Registry.getInstance(messageTypeRegistry.getTargetDictionary());
        this.validationSettings = adminValidationSettings(sessionValidationSettings);
        this.beginString = new String(fixSessionId.getFixVersion().getBeginString(), SerDe.CHARSET);
        this.rejects = new ArrayList<>();
        this.parser = new FixMessageParser(fixSessionId, messageTypeRegistry, fieldsRegistry,
                VoidMessageLogger.getInstance(), validationSettings, clock, new FixMessageParserEventsListener() {
            @Override
            public void onMessageRejects(MessageType messageType, FixMessageDecoder fixMessageDecoder, long incomingSeqNum, List<MessageReject> messageRejects) {
                rejects.addAll(messageRejects);
            }
        });
    }

    private static FixSessionSettings.ValidationSettings adminValidationSettings(FixSessionSettings.ValidationSettings sessionSettings) {
        return sessionSettings.toBuilder()
                .validateChecksum(false)
                .validateBeginString(false)
                .validateCompId(false)
                .detectGarbledMessages(false)
                .maxSendingTime(null)
                .maxMessageSize(null)
                .validateFieldsHaveValues(true)
                .validateRequiredFields(true)
                .allowUndefinedTagsForMessage(false)
                .validateDuplicateTags(true)
                .build();
    }

    private static int bodyLength(String body) {
        return body.getBytes(SerDe.CHARSET).length;
    }

    /**
     * @param fixMessage  the message as a string, with or without the BeginString(8) and BodyLength(9) it was written
     *                    with and with or without its CheckSum(10)
     * @param separator   the character between its fields, SOH for a message captured off the wire, typically
     *                    {@code |} for one written by hand
     * @param possDupFlag whether this is the same message going out again: its SendingTime(52) is then carried into
     *                    OrigSendingTime(122) and PossDupFlag(43)=Y is set, the way a retransmission does it, while the
     *                    session stamps a SendingTime of its own as it always does. The message must carry a
     *                    SendingTime for that, section 4.4 requiring OrigSendingTime wherever PossDupFlag is Y
     * @return the fields to send, under the message type the string named - which the decoded message carries itself,
     * through {@link DecodedFixMessage#getMessageType()}
     * @throws IllegalArgumentException if the string is not a FIX message this session could have sent: the reasons
     *                                  name the tag or the message type at fault
     */
    public synchronized DecodedFixMessage transform(String fixMessage, char separator, boolean possDupFlag) {
        if (fixMessage == null || fixMessage.trim().isEmpty()) {
            throw new IllegalArgumentException("No FIX message to send");
        }
        this.possDupFlag = possDupFlag;
        this.sendingTimeCarriedOver = false;
        String body = reframe(fixMessage, separator);
        byte[] framed = (BEGIN_STRING_TAG + beginString + CoreFields.FIELD_SEPARATOR
                + BODY_LENGTH_TAG + bodyLength(body) + CoreFields.FIELD_SEPARATOR
                + body
                + CHECKSUM_PLACEHOLDER).getBytes(SerDe.CHARSET);

        rejects.clear();
        decodedMessagesCount = 0;
        decodedFixMessageDecoder = null;
        try {
            parser.parseMessages(ByteBuffer.wrap(framed), this::decoderFor);
        } catch (DecodingException ex) {
            throw new IllegalArgumentException("The FIX message to send cannot be decoded against dictionary "
                    + messageFieldsRegistry.getTargetDictionary() + ": " + ex.getMessage(), ex);
        }
        if (!rejects.isEmpty()) {
            throw new IllegalArgumentException("The FIX message to send does not follow dictionary "
                    + messageFieldsRegistry.getTargetDictionary() + ": " + rejects.stream()
                    .map(reject -> "tag " + reject.getRefTagId() + " " + reject.getMessage())
                    .collect(Collectors.joining(", ")));
        }
        if (decodedFixMessageDecoder == null) {
            throw new IllegalArgumentException("The FIX message to send is incomplete, no message could be read out of it");
        }
        if (possDupFlag && !sendingTimeCarriedOver) {
            throw new IllegalArgumentException("The FIX message to send carries no SendingTime(52) to put in "
                    + "OrigSendingTime(122), which PossDupFlag(43)=Y requires");
        }
        if (decodedMessagesCount > 1) {
            throw new IllegalArgumentException("The FIX message to send holds " + decodedMessagesCount
                    + " messages, one is sent at a time");
        }
        return decodedFixMessageDecoder.getDecodedFixMessage();
    }

    /**
     * Normalizes the separator and hands back the caller's bytes with the frame this class rebuilds taken off them:
     * BeginString(8) and BodyLength(9) if the message opens with them, CheckSum(10) if it closes with one. Nothing in
     * between is read here - that is the parser's job, and a data field's value is free to hold anything a scan of this
     * string would mistake for a field of its own.
     * <p>
     * Normalizing the separator alone would not do, and the reason is BodyLength(9): the parser compares it with the
     * bytes it counted and marks the message garbled when they differ - a check made where CheckSum(10) is read, not
     * through the garbled detector the settings can turn off. A garbled message is then disregarded whole, <b>and the
     * rejects collected while decoding it are dropped with it</b>, so a message whose 9 does not add up comes back as
     * "nothing could be read out of it" rather than naming the tag at fault. And 9 stops adding up the moment an
     * operator edits a value, which is the reason for pasting a message in at all. Hence the frame is rebuilt rather
     * than trusted: 8 and 9 are dropped and written again, and so is 10 - it has to be there for the parser to know
     * where the message ends, while its value is never read.
     * <p>
     * That the remainder starts with MsgType(35) is checked here for the diagnostic alone: without it, a fragment that
     * forgot its MsgType would come back as that same unhelpful "nothing could be read out of it".
     */
    private String reframe(String fixMessage, char separator) {
        String normalized = fixMessage.trim();
        // the caller says which character separates the fields rather than this guessing from what it finds: a value is
        // free to contain the one that is not in use, and a message carrying both would otherwise be read either way
        if (separator != CoreFields.FIELD_SEPARATOR) {
            normalized = normalized.replace(separator, CoreFields.FIELD_SEPARATOR);
        }
        if (normalized.charAt(normalized.length() - 1) != CoreFields.FIELD_SEPARATOR) {
            normalized = normalized + CoreFields.FIELD_SEPARATOR;
        }
        if (normalized.startsWith(BEGIN_STRING_TAG)) {
            normalized = normalized.substring(normalized.indexOf(CoreFields.FIELD_SEPARATOR) + 1);
        }
        if (normalized.startsWith(BODY_LENGTH_TAG)) {
            normalized = normalized.substring(normalized.indexOf(CoreFields.FIELD_SEPARATOR) + 1);
        }
        int lastFieldStart = normalized.lastIndexOf(CoreFields.FIELD_SEPARATOR, normalized.length() - 2) + 1;
        if (normalized.startsWith(CHECKSUM_TAG, lastFieldStart)) {
            normalized = normalized.substring(0, lastFieldStart);
        }
        if (!normalized.startsWith(MESSAGE_TYPE_TAG)) {
            throw new IllegalArgumentException("The FIX message to send must start with MsgType(35), after the "
                    + "BeginString(8) and BodyLength(9) it may carry, but starts with: "
                    + normalized.substring(0, Math.min(20, normalized.length())).replace(CoreFields.FIELD_SEPARATOR, PIPE));
        }
        return normalized;
    }

    private FixMessageDecoder decoderFor(MessageType messageType) {
        decodedMessagesCount++;
        decodedFixMessageDecoder = new DecodedFixMessageDecoder(messageType) {
            @Override
            public void onField(FixField fixField, SerDe.DeserializationContext deserializationContext) {
                if (possDupFlag && fixField.equals(sendingTime)) {
                    // the same message going out again: the time it was first sent becomes OrigSendingTime(122) and the
                    // message says so with PossDupFlag(43)=Y, which is what a retransmission does with a stored message.
                    // The SendingTime(52) of this send is the session's own, stamped as it encodes
                    UTCTime originalSendingTime = UtcDateTimeSerde.deserialize(deserializationContext);
                    getCurrentFixFieldMap().add(origSendingTime, UtcDateTimeSerde.serializeTime(originalSendingTime, sendingTimeAccuracy));
                    getCurrentFixFieldMap().add(possDupFlagField, POSS_DUP_FLAG_SET);
                    sendingTimeCarriedOver = true;
                } else if (fixField.getLocation() == FieldLocation.BODY) {
                    // the body, and nothing else: the header and trailer fields of the message the caller copied are
                    // not the ones this session is going to send
                    super.onField(fixField, deserializationContext);
                }
            }
        };
        return new FixMessageDecoderImpl(fieldsRegistry, decodedFixMessageDecoder, messageFieldsRegistry,
                validationSettings, fixSessionId);
    }
}
