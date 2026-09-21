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
import org.lolaf.staffix.api.codec.*;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.codec.decoders.FixMessageResendTransformer;
import org.lolaf.staffix.impl.session.MessagesResender;

/**
 * Decodes a ResendRequest(35=2), the peer asking for messages it believes it missed.
 */
@Setter
@Slf4j
public class ResendRequestFixMessageDecoder extends AbstractAdminFixMessageDecoder {
    private static final int BEGIN_SEQ_NO_FIELD = 7;
    private static final int END_SEQ_NO_FIELD = 16;
    private MessagesResender messagesResender;
    private long beginSeqNo;
    private long endSeqNo;

    public ResendRequestFixMessageDecoder(MessageType messageType, AdminMessageCodecContext adminMessageCodecContext, FixAdminMessagesCodec fixAdminMessagesCodec) {
        super(messageType, adminMessageCodecContext, fixAdminMessagesCodec);
    }

    // built on the first request rather than here: this decoder is constructed with the admin codec, before the
    // components it needs are registered
    private MessagesResender getMessagesResender() {
        if (messagesResender == null) {
            messagesResender = new MessagesResender(getFixSessionMessagesStore(),
                    getFieldsRegistry().find(CoreFields.MESSAGE_TYPE),
                    getFieldsRegistry().find(CoreFields.MESSAGE_SEQ_NUM),
                    new FixMessageResendTransformer(getFixSessionSettings().getSendingTimeAccuracy(),
                            getClock(), getFixSession().getFixSessionId(), getMessageTypeRegistry(), getFieldsRegistry()),
                    getFixApplication(),
                    getMessageTypeRegistry(),
                    getFixAdminMessagesCodec(),
                    getFixSession(),
                    getRetransmission(),
                    getOutgoingMessages());
        }
        return messagesResender;
    }

    @Override
    public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
        fixFieldsDecoderMapper.mapLongField(getFieldsRegistry().find(BEGIN_SEQ_NO_FIELD), this::setBeginSeqNo, 0)
                .mapLongField(getFieldsRegistry().find(END_SEQ_NO_FIELD), this::setEndSeqNo, 0);
        super.mapFieldsForDecoding(fixFieldsDecoderMapper, fieldsRegistry);
    }

    /**
     * Turns the open ended forms of EndSeqNo(16) into the largest sequence number there is, so that the range can be
     * capped against what has actually been sent. Section 4.8.2 allows requesting "all messages subsequent to a
     * particular message", spelled either 0 or 999999 depending on the FIX version.
     */
    private void normalizeOpenEndedRange() {
        if (endSeqNo == 0 || endSeqNo == 999999) {
            endSeqNo = Integer.MAX_VALUE;
        }
    }

    @Override
    public void validate() throws ValidationException {
        normalizeOpenEndedRange();
        if (endSeqNo < beginSeqNo) {
            // equal bounds are legal: section 4.8.2 of the FIX Session Layer specification allows a ResendRequest to
            // "request retransmission of a single message, a range of messages or all messages"
            throw new ValidationException(getFieldsRegistry().find(END_SEQ_NO_FIELD),
                    "EndSeqNo " + endSeqNo + " cannot be smaller than BeginSeqNo " + beginSeqNo,
                    SessionRejectReasonCodes.VALUE_IS_INCORRECT, BusinessRejectReasonCodes.OTHER);
        }
        super.validate();
    }

    @Override
    public void onDecodedLocal(FixSession fixSession, boolean possDupFlag, boolean possResend) {
        resendRequestedMessages();
    }

    /**
     * Answers a ResendRequest(35=2) that arrived with a MsgSeqNum(34) higher than expected.
     * <p>
     * Waiting for the gap ahead of it to be filled would deadlock whenever both peers reconnect with a gap of their
     * own - figure 5 of section 4.3.12 - since each would then be holding the very retransmission the other is
     * waiting for. Nothing about answering needs this message to be in sequence: the reply is built from the store,
     * at the sequence numbers the messages were originally sent with. Its own MsgSeqNum(34) is still owed, and the
     * session layer accounts for it the same way it does for an out of sequence Logon.
     */
    @Override
    void onDecodingFailedLocal(FixSession fixSession, DecodingException decodingException) {
        if (decodingException instanceof WrongSeqNumException
                && ((WrongSeqNumException) decodingException).getMsgSeqNum() > ((WrongSeqNumException) decodingException).getExpectedMsgSeqNum()) {
            // validate() never ran, the message having been rolled back before it: the range still has to be capped
            normalizeOpenEndedRange();
            if (endSeqNo >= beginSeqNo) {
                getFixSession().logEvent("Answering ResendRequest %s -> %s received while recovering a gap of our own", beginSeqNo, endSeqNo);
                resendRequestedMessages();
            }
            return;
        }
        super.onDecodingFailedLocal(fixSession, decodingException);
    }

    private void resendRequestedMessages() {
        long nextExpectedOutgoingSeqNum = getFixSessionMessagesStore().getOutgoingSeqNum();
        if (endSeqNo >= nextExpectedOutgoingSeqNum) {
            // the peer asked beyond what has been sent, which is what an open ended EndSeqNo(16) of 0 means: cap the
            // range at the last message actually sent. A range that already ends within it is left untouched, the
            // resender must retransmit what was requested and nothing more (section 4.8.5).
            endSeqNo = nextExpectedOutgoingSeqNum - 1;
        }
        getMessagesResender().resendMessages(beginSeqNo, endSeqNo);
    }
}