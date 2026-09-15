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
package org.lolaf.staffix.impl.session.codec;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.codec.DecodingException;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.SessionRejectReasonCodes;
import org.lolaf.staffix.api.codec.WrongSeqNumException;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.impl.session.FixSessionImpl;

/**
 * Decodes an inbound SequenceReset(35=4) message. Two very different messages share this type, told apart by
 * GapFillFlag(123):
 * <ul>
 *     <li>a <b>hard reset</b> (GapFillFlag other than Y) forces the expected incoming sequence number to
 *     NewSeqNo(36) <em>without regard to its own MsgSeqNum(34)</em> (section 4.8.6). It therefore bypasses the usual
 *     out-of-sequence handling, signaled through {@link #ignoresIncomingSequenceNumber()}, and is always processed
 *     here;</li>
 *     <li>a <b>gap fill</b> (GapFillFlag=Y) is a genuine message subject to MsgSeqNum(34) processing, sent to skip
 *     over messages not retransmitted while answering a ResendRequest(35=2). An out-of-sequence gap fill is handled
 *     by the session layer as any other message before reaching {@link #onDecodedLocal}; only an in-sequence one
 *     lands here.</li>
 * </ul>
 */
@Slf4j
@Setter
public class SequenceResetFixMessageDecoder extends AbstractAdminFixMessageDecoder {

    private Boolean gapFill;
    private long newSeqNo;
    private long msgSeqNum;

    public SequenceResetFixMessageDecoder(MessageType messageType, AdminMessageCodecContext adminMessageCodecContext, FixAdminMessagesCodec fixAdminMessagesCodec) {
        super(messageType, adminMessageCodecContext, fixAdminMessagesCodec);
    }

    @Override
    public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
        fixFieldsDecoderMapper.mapBooleanObjectField(getFieldsRegistry().find(CoreFields.GAP_FILL), this::setGapFill, null)
                .mapLongField(getFieldsRegistry().find(CoreFields.NEW_SEQ_NO), this::setNewSeqNo, 0)
                // captured only to reference the offending message in a Reject; the reset itself ignores it
                .mapLongField(getFieldsRegistry().find(CoreFields.MESSAGE_SEQ_NUM), this::setMsgSeqNum, 0);
        super.mapFieldsForDecoding(fixFieldsDecoderMapper, fieldsRegistry);
    }

    private boolean isHardReset() {
        return gapFill == null || !gapFill;
    }

    @Override
    public boolean ignoresIncomingSequenceNumber() {
        // a hard reset is applied whatever its MsgSeqNum, so the session layer must not treat it as out of sequence
        return isHardReset();
    }

    @Override
    public boolean managesIncomingSequenceNumber() {
        // whichever of the two this turned out to be, onDecodedLocal has stored what comes next: NewSeqNo for a hard
        // reset, the end of the filled range for a gap fill, or the unchanged value for a reset that was refused
        return true;
    }

    @Override
    public void onDecodedLocal(FixSession fixSession, boolean possDupFlag, boolean possResend) {
        FixSessionImpl fixSessionImpl = getFixSession();
        long nextNumIn = getFixSessionMessagesStore().getIncomingSeqNum();

        if (isHardReset()) {
            processHardReset(fixSessionImpl, nextNumIn);
        } else {
            processGapFill(fixSession, fixSessionImpl, nextNumIn);
        }
    }

    private void processHardReset(FixSessionImpl fixSessionImpl, long nextNumIn) {
        if (newSeqNo < nextNumIn) {
            // scenario 11-c: lowering the sequence number is refused, and NextNumIn is left untouched
            rejectLoweredSequenceNumber(fixSessionImpl, nextNumIn);
            return;
        }
        if (newSeqNo == nextNumIn) {
            fixSessionImpl.logEvent("Received redundant SequenceReset with NewSeqNum %s equal to the expected sequence number", newSeqNo);
        } else {
            fixSessionImpl.logEvent("Processing hard SequenceReset with NewSeqNum %s", newSeqNo);
        }
        getFixSessionMessagesStore().storeNextIncomingSeqNum(newSeqNo);
        // A hard reset arriving while a ResendRequest(35=2) of ours is still outstanding is not something section
        // 4.8.6 contemplates - a gap fill is the only defined answer to a resend request - but it is what a peer that
        // has lost its store and cannot satisfy the request reaches for.
        // Carrying the sequence past the far end of what was asked for makes that request unsatisfiable by anything,
        // so it is settled here rather than left outstanding until some later message happens to close it: an
        // outstanding request holds back outgoing application messages and leaves the queue on top of the gap
        // unreplayed. A reset landing inside the requested range settles nothing, which this call already accounts
        // for - it only finishes a request the new sequence number has actually run past.
        // NewSeqNo(36) itself, unlike the gap fill below: a hard reset restarts the numbering there, so nothing of the
        // range will ever arrive and a request ending on it is settled rather than left waiting for a message the
        // peer has just declared it will not send.
        getResendRecovery().onSequenceNumbersSettledUpTo(newSeqNo);
    }

    private void processGapFill(FixSession fixSession, FixSessionImpl fixSessionImpl, long nextNumIn) {
        if (!getResendRecovery().hasPendingResendRequest() && !getResendRecovery().isAnsweringOwnOpenEndedRequest()) {
            log.error("Received SequenceReset gap fill with no pending ResendRequest, disconnecting");
            fixSession.logout("Received SequenceReset with no pending ResendRequest");
            return;
        }
        if (newSeqNo <= nextNumIn) {
            // scenario 10-e: a gap fill that does not advance the sequence number is an attempt to lower it
            rejectLoweredSequenceNumber(fixSessionImpl, nextNumIn);
            return;
        }
        fixSessionImpl.logEvent("Processing SequenceReset with gap fill and NewSeqNum %s", newSeqNo);
        getFixSessionMessagesStore().storeNextIncomingSeqNum(newSeqNo);
        // a gap fill accounts for the messages below NewSeqNo(36), not for NewSeqNo(36) itself: the last MsgSeqNum(34)
        // it settles is the one before it, and a request ending exactly there is still owed its last message. Do not
        // align this with the hard reset above, which deliberately settles NewSeqNo(36) itself.
        getResendRecovery().onSequenceNumbersSettledUpTo(newSeqNo - 1);
    }

    /**
     * A gap fill that arrived below the expected MsgSeqNum(34) is rolled back before it is ever processed, and the
     * admin decoders answer a decoding failure by taking the connection down. That is section 4.8.1 for anything else, but
     * the tail of an open ended answer is exactly this shape - the peer accounting for messages this side has since
     * moved past - so it is left alone here, the session layer having already decided to ignore it.
     *
     * @see org.lolaf.staffix.impl.session.ResendRecovery#isAnsweringOwnOpenEndedRequest()
     */
    @Override
    void onDecodingFailedLocal(FixSession fixSession, DecodingException decodingException) {
        if (!isHardReset() && getResendRecovery().isAnsweringOwnOpenEndedRequest()
                && decodingException instanceof WrongSeqNumException
                && ((WrongSeqNumException) decodingException).getMsgSeqNum()
                < ((WrongSeqNumException) decodingException).getExpectedMsgSeqNum()) {
            return;
        }
        super.onDecodingFailedLocal(fixSession, decodingException);
    }

    private void rejectLoweredSequenceNumber(FixSessionImpl fixSessionImpl, long nextNumIn) {
        String message = "Attempt to lower sequence number, invalid value NewSeqNo(36)=" + newSeqNo
                + ", expecting at least " + nextNumIn;
        log.error("{}, sending Reject", message);
        fixSessionImpl.logEvent(message);
        // NextNumIn is deliberately left unchanged: the reset is refused, not applied
        fixSessionImpl.send(getFixAdminMessagesCodec().generateReject(message,
                        SessionRejectReasonCodes.VALUE_IS_INCORRECT.getCode(), msgSeqNum, CoreFields.NEW_SEQ_NO, getMessageType()),
                getClock().now());
    }
}
