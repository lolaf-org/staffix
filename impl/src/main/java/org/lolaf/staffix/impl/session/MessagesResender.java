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
package org.lolaf.staffix.impl.session;

import lombok.AllArgsConstructor;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.serde.Hashing;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.codec.decoders.FixMessageResendTransformer;
import org.lolaf.staffix.codec.encoders.GenericFixMessageEncoder;
import org.lolaf.staffix.impl.session.codec.FixAdminMessagesCodec;
import org.lolaf.staffix.serde.ByteArraySerde;
import org.lolaf.staffix.serde.LongSerde;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Answers a ResendRequest from the message store, replaying what can be replayed and filling the rest with a
 * gap fill.
 *
 * <p>An administrative message is never replayed - resending an old Logon or Heartbeat would be nonsense - so a
 * run of them collapses into one SequenceReset, which is what the specification asks for.
 */
@AllArgsConstructor
public class MessagesResender {

    private final FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore;
    private final FixField msgTypeField;
    private final FixField msgSeqNumField;
    private final FixMessageResendTransformer transformer;
    private final FixApplication fixApplication;
    private final MessageTypeRegistry messageTypeRegistry;
    private final FixAdminMessagesCodec fixAdminMessagesCodec;
    private final FixSessionImpl fixSessionImpl;

    /**
     * Retransmits {@code [beginSeqNo, endSeqNo]}, covering with a SequenceReset(35=4) gap fill whatever is not
     * actually put back on the wire - session level messages, and the ones the application declines.
     * <p>
     * Driven both by an incoming ResendRequest(35=2) and, when the peer synchronizes with
     * NextExpectedMsgSeqNum(789) instead, by the Logon(35=A) itself.
     */
    public void resendMessages(long beginSeqNo, long endSeqNo) {
        // off the IO thread, which would otherwise be held for the whole replay - see FixSessionImpl.executeResend.
        // The range is captured here, on the IO thread, because the decoder fields it comes from are reused by the
        // next message to arrive.
        fixSessionImpl.executeResend(() -> resendMessagesRange(beginSeqNo, endSeqNo));
    }

    private void resendMessagesRange(long beginSeqNo, long endSeqNo) {
        // whatever is chosen to be retransmitted inside it, the requested range must be covered up to its end and
        // no further: the peer has to end up expecting the message right after the range it asked for
        long firstSeqNumAfterRequestedRange = endSeqNo + 1;
        if (beginSeqNo > endSeqNo) {
            // an empty range, which is what capping EndSeqNo(16) at the last message sent leaves when the peer asked
            // for something entirely beyond it. There is nothing to retransmit, but the request still has to be
            // answered so that the peer stops waiting for one
            sendSequenceResetWithGapFill(fixSessionImpl, beginSeqNo, firstSeqNumAfterRequestedRange);
            return;
        }
        // A peer decides this range, so it decides how much work this costs us. Section 4.8.5 lets a resender gap
        // fill anything it chooses not to retransmit, so a range wider than this is answered for in full - the peer
        // ends up correctly synchronized either way - without replaying more than the session is willing to.
        int maxMessagesResent = fixSessionImpl.getFixSessionSettings().getMaxMessagesResentPerRequest();

        fixSessionImpl.logEvent("Resending messages %s -> %s", beginSeqNo, endSeqNo);
        ResendState resendState = new ResendState(beginSeqNo);
        try {
            fixSessionMessagesStore.find(beginSeqNo, endSeqNo, (seqNum, message) ->
                    resendStoredMessage(resendState, message, maxMessagesResent));
        } catch (FixMessagesStore.FixSessionMessagesStore.StoreException ex) {
            fixSessionImpl.logout("Unable to fetch FIX messages to resend, try again later");
            return;
        }

        if (resendState.expectedNextSeqNum < firstSeqNumAfterRequestedRange) {
            // whatever was not retransmitted is skipped at once: session layer messages, ones the application
            // declined, ones past the cap, or the whole range when the store held nothing for it. One single skipped
            // message is gap filled too.
            sendSequenceResetWithGapFill(fixSessionImpl, resendState.expectedNextSeqNum, firstSeqNumAfterRequestedRange);
        }
    }

    /**
     * @return whether the store should carry on reading the range
     */
    private boolean resendStoredMessage(ResendState resendState, ByteBuffer messageContent, int maxMessagesResent) {
        // a store may hand over the very buffer it holds the message in, and both reads below drain what they are
        // given, so the position goes back as it was: the same message can be asked for again by a later request
        int storedMessagePosition = messageContent.position();
        if (messageContent.isDirect()) {
            // use limit as ByteBuffer returned by the stare can have a bigger capacity than their actual real size (limit)
            if (resendState.transformedMessageBuffer == null || resendState.transformedMessageBuffer.limit() < messageContent.limit()) {
                resendState.transformedMessageBuffer = ByteBuffer.allocate(messageContent.limit());
            }
            resendState.transformedMessageBuffer.clear().put(messageContent).flip();
        } else {
            resendState.transformedMessageBuffer = messageContent;
        }
        resendState.expectedNextSeqNum = resendMessage(fixSessionImpl, transformer, resendState.transformedMessageBuffer,
                msgTypeField, msgSeqNumField, resendState.expectedNextSeqNum, resendState.encoders);
        messageContent.position(storedMessagePosition);
        resendState.messagesRead++;
        if (maxMessagesResent > 0 && resendState.messagesRead >= maxMessagesResent) {
            fixSessionImpl.logEvent("Stopping the retransmission after %s messages, the rest of the range is gap filled",
                    resendState.messagesRead);
            return false;
        }
        return true;
    }

    private long resendMessage(FixSessionImpl fixSession, FixMessageResendTransformer transformer, ByteBuffer transformedMessageBuffer, FixField msgType,
                               FixField msgSeqNum, long expectedNextSeqNum, Map<MessageType, GenericFixMessageEncoder> encoders) {

        DecodedFixMessage decodedFixMessage = transformer.transformForResend(transformedMessageBuffer);
        byte[] messageTypeArray = decodedFixMessage.remove(msgType);
        MessageType messageType = messageTypeRegistry.find(Hashing.hash(messageTypeArray, 0, messageTypeArray.length));
        byte[] msgSeqNumArray = decodedFixMessage.remove(msgSeqNum);
        long seqNum = LongSerde.deserializeUnsigned(msgSeqNumArray, 0, msgSeqNumArray.length);
        if (!fixApplication.onResendRequest(fixSession, messageType, decodedFixMessage)) {
            return expectedNextSeqNum;
        }
        if (expectedNextSeqNum != seqNum) {
            // manage gap fills
            sendSequenceResetWithGapFill(fixSession, expectedNextSeqNum, seqNum);
        }
        GenericFixMessageEncoder encoder = encoders.computeIfAbsent(messageType, GenericFixMessageEncoder::new).begin();
        decodedFixMessage.foreach((f, v) -> encoder.addField(f, v, ByteArraySerde.instance()));
        fixSession.sendWithSeqNum(encoder, seqNum);
        // sendWithSeqNum encodes into a buffer of its own and does not go through the sending context that normally
        // releases the encoder afterwards. These encoders being cached per message type, a range holding two messages
        // of the same type would otherwise fail the second begin() with "encoder not yet sent", aborting the resend
        // in the middle and taking the session down with it.
        encoder.release();
        return seqNum + 1;
    }

    private void sendSequenceResetWithGapFill(FixSessionImpl fixSession, long messageSequenceNumber, long newSeqNum) {
        fixSession.logEvent("Sending SequenceReset from SeqNum %s with gap fill to NewSeqNum %s", messageSequenceNumber, newSeqNum);
        FixMessageEncoder<?> gapFillEncoder = fixAdminMessagesCodec.generateSequenceReset(newSeqNum, true);
        fixSession.sendWithSeqNum(gapFillEncoder, messageSequenceNumber);
        fixApplication.onSequenceReset(fixSession, newSeqNum, true);
    }

    /**
     * What the retransmission carries from one message of the range to the next. A holder rather than locals because
     * the range is read through a callback, and only ever built when a resend actually happens.
     */
    private static class ResendState {
        private final Map<MessageType, GenericFixMessageEncoder> encoders = new HashMap<>();
        private ByteBuffer transformedMessageBuffer;
        private long expectedNextSeqNum;
        private int messagesRead;

        ResendState(long beginSeqNo) {
            this.expectedNextSeqNum = beginSeqNo;
        }
    }

}
