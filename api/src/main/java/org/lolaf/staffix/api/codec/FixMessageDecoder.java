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
package org.lolaf.staffix.api.codec;

import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;

/**
 * Decodes an inbound FIX message of a particular {@link MessageType} into application state.
 * <p>
 * A decoder is driven by the FIX engine through a well-defined lifecycle. Typically you declare your message fields as
 * decoder variables and bind them once in {@link #mapFieldsForDecoding(FixFieldsDecoderMapper, FieldsRegistry)}; the
 * engine then populates those variables directly as it parses each message, so most callbacks below are optional hooks
 * you only override when you need finer control (per-field handling, repeating groups, validation, the outcome).
 * <p>
 * For a single message the engine invokes, in order:
 * <ol>
 *     <li>{@link #onBegin(long, UTCTime)} when parsing of the message starts;</li>
 *     <li>{@link #onField(FixField, SerDe.DeserializationContext)} (and
 *     {@link #onUnknownField(FixField, SerDe.DeserializationContext)} for fields absent from the dictionary) as fields
 *     are parsed, interleaved with the {@code onGroup*} callbacks for any repeating groups;</li>
 *     <li>{@link #validate()} to check required fields are present;</li>
 *     <li>{@link #onDecoded(FixSession, boolean, boolean)} on success, or
 *     {@link #onDecodingFailed(FixSession, DecodingException)} if
 *     parsing or validation failed.</li>
 * </ol>
 * Most methods are {@code default} no-ops so implementations override only the hooks they need. A decoder instance
 * processes one message at a time and is reused across messages; treat per-message state as reset at {@link #onBegin}.
 *
 * @see FixFieldsDecoderMapper
 * @see FixMessageEncoder
 */
public interface FixMessageDecoder {

    /**
     * @return the {@link MessageType} this decoder handles
     */
    MessageType getMessageType();

    /**
     * Main method called by the fix engine to map field to variables in your decoder after its instantiation
     *
     * @param fixFieldsDecoderMapper the mapper to map fix field to variables in your decoder
     * @param fieldsRegistry         the configured fields registry for your dictionary, you can also use it to add user defined fields on the fly in the registry.
     *                               Note that your dictionary fields are also generated as Enums in the "org.lolaf.staffix.$fixversion.fields" package
     */
    default void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
    }

    /**
     * Called before onDecoded to validate that all required fields have been received, if an ValidationException is raised,
     * the onDecodingFailed will be called with the exception and a BusinessMessageReject will be sent back to the session remote host
     *
     * @throws ValidationException if the message does not pass validation
     */
    default void validate() throws ValidationException {
    }

    /**
     * Called when a field is processed, not that this method is usually not required ot be implemented if you
     * correctly mapped your fields using {@link #mapFieldsForDecoding(FixFieldsDecoderMapper, FieldsRegistry)}
     *
     * @param fixField               the field being processed
     * @param deserializationContext the serde deserialization context.
     */
    default void onField(FixField fixField, SerDe.DeserializationContext deserializationContext) {
    }

    /**
     * Called when a user defined or unknow field (not present in the dictionary) is processed, this method will only be called
     * if {@link FixSessionSettings.ValidationSettings#isAllowUserDefinedFields()}
     * or {@link FixSessionSettings.ValidationSettings#isAllowUnknownFields()} settings are enabled.
     *
     * @param fixField               the field being processed
     * @param deserializationContext the serde deserialization context.
     */
    default void onUnknownField(FixField fixField, SerDe.DeserializationContext deserializationContext) {
    }

    /**
     * Called when a message parsing is beginning on the decoder instance
     *
     * @param localReceiveTimeInNanos the message local receive time in nanos
     * @param localReceiveTime        the message local receive time
     */
    default void onBegin(long localReceiveTimeInNanos, UTCTime localReceiveTime) {
    }

    /**
     * Called when a message parsing is successfully terminated
     *
     * @param fixSession  the fix session that processed the message
     * @param possDupFlag possible duplicate flag of the message
     * @param possResend  PossResend(97) of the message, as received in its standard header. The session layer only
     *                    hands it over, as section 4.9 of the FIX Session Layer specification requires it to: a
     *                    PossResend message is processed as a brand new message, and detecting and handling duplicate
     *                    application messages is the application's responsibility, typically by checking a
     *                    message-specific identifier against the ones already seen on the session.
     */
    default void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
    }

    /**
     * Whether the message being decoded must be processed regardless of its MsgSeqNum(34), i.e. without the usual
     * too-high (ResendRequest) or too-low (Logout) handling. This is the case of a hard SequenceReset(35=4) with
     * GapFillFlag(123) other than Y, which forces the peer's expected sequence number to NewSeqNo(36) whatever its
     * own MsgSeqNum(34) (see section 4.8.6 of the FIX Session Layer specification). The default is {@code false}: an
     * out of sequence message is handled by the session layer before ever reaching {@link #onDecoded}.
     */
    default boolean ignoresIncomingSequenceNumber() {
        return false;
    }

    /**
     * Whether {@link #onDecoded} has itself settled what the session expects to receive next, in which case the
     * session layer must leave that value alone instead of storing this message's MsgSeqNum(34) plus one.
     * <p>
     * This is the case of every message that moves the sequence rather than merely advancing it: a
     * SequenceReset(35=4), jumping to NewSeqNo(36) or skipping a gap filled range, and a Logon(35=A) that restarted
     * the numbering through ResetSeqNumFlag(141) or completed a retransmission. Their own MsgSeqNum(34) belongs to
     * the numbering they replaced, so deriving the next one from it would undo what they just did.
     * <p>
     * Asked after {@link #onDecoded} has run, so that a decoder can answer on what it actually did. The default is
     * {@code false}: an ordinary message simply moves the expected sequence number on by one.
     */
    default boolean managesIncomingSequenceNumber() {
        return false;
    }

    /**
     * Called when a message parsing has failed, either due to an untrapped decoder exception or invalid received data
     * or missing field
     */
    default void onDecodingFailed(FixSession fixSession, DecodingException decodingException) {

    }

    /**
     * Called when a repeating group is starting
     *
     * @param parentGroup the parent group of this new group, cover the case of multiple nested inner groups, can be null if group is the first in the nested group chain
     * @param groupField  the repeating group field
     * @param numInGroup  the number of repeating group entries
     */
    default void onGroupStart(FixField parentGroup, FixField groupField, int numInGroup) {
    }

    /**
     * Called when the current repeating group entries have been  fully processed
     *
     * @param parentGroup the parent group of the group we exit, cover the case of multiple nested inner groups, can be null if group is the first in the nested group chain
     * @param groupField  the processed group field
     */
    default void onGroupEnd(FixField parentGroup, FixField groupField) {
    }

    /**
     * Called when a new entry of a repeating group is starting.
     *
     * @param parentGroup the parent group, or null if this group is the first in the nested group chain
     * @param groupField  the repeating group field
     * @param fixField    the field that delimits the start of the entry (typically the entry's first field)
     */
    default void onGroupEntryStart(FixField parentGroup, FixField groupField, FixField fixField) {
    }

    /**
     * Called when the current entry of a repeating group has been fully processed.
     *
     * @param parentGroup the parent group, or null if this group is the first in the nested group chain
     * @param groupField  the repeating group field
     * @param fixField    the field that delimited the start of the entry
     */
    default void onGroupEntryEnd(FixField parentGroup, FixField groupField, FixField fixField) {
    }
}