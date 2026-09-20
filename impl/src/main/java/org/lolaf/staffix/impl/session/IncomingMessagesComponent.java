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

import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.api.codec.*;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.codec.decoders.*;

import java.util.List;

/**
 * The incoming side of a session: what the parser reports goes here, and what a message arriving does to the
 * sequence numbers is decided here.
 *
 * <p>Sections 4.5 and 4.8. A message too high opens a gap and is queued until the peer fills it; one too low ends
 * the session unless it says why it is a duplicate; and a message the decoder refused still has to leave the
 * numbering somewhere sensible, which is what {@link RolledBackSequenceNumber} settles.
 *
 * <p>It is the parser's listener, so the session itself no longer stands between the two. The decoding callbacks
 * it does not decide on, the start and the end of one, go to the plugins from here.
 */
@RequiredArgsConstructor
class IncomingMessagesComponent implements FixSessionLayerComponent, FixMessageParserEventsListener {

    private final FixSessionImpl fixSession;
    private final FixSessionLayerComponents fixSessionLayerComponents;
    private final FixSessionStateComponent fixSessionStateComponent;
    private final FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore;
    private final RetransmissionComponent retransmission;
    private final MessageRejectsComponent messageRejects;
    private PluginsComponent plugins;

    @Override
    public void onSessionStarted(FixSessionLayerComponents components) {
        plugins = components.get(PluginsComponent.class);
    }

    @Override
    public void onMessageDecodingStart(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        plugins.onMessageDecodingStarted(messageType, localReceiveTimeInNanos, localReceiveTime);
    }

    @Override
    public void onMessageDecodingEnd(MessageType messageType, int payloadSize, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        plugins.onMessageReceived(messageType, payloadSize, localReceiveTimeInNanos, localReceiveTime);
    }

    @Override
    public void onMessageRejects(MessageType messageType, FixMessageDecoder fixMessageDecoder, long incomingSeqNum,
                                 List<MessageReject> rejects) {
        messageRejects.onMessageRejects(messageType, incomingSeqNum, rejects);
    }

    @Override
    public void onMessageDecoded(MessageType messageType, FixMessageDecoder fixMessageDecoder, long incomingSeqNum,
                                 boolean possibleDuplicate, boolean possResend, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        fixSessionLayerComponents.onMessageReceived(localReceiveTime);
        if (fixSessionStateComponent.isAdminOnlyMessagesAllowed() && !messageType.isAdmin()) {
            MessageReject reject = MessageReject.builder()
                    .message("Only admin messages are allowed at this session current state")
                    .refTagId(0)
                    .businessRejectReasonCode(BusinessRejectReasonCodes.OTHER)
                    .sessionRejectReasonCode(SessionRejectReasonCodes.OTHER)
                    .build();
            onMessageDecodingFailed(messageType, fixMessageDecoder, incomingSeqNum, new RejectedMessageException(reject.getMessage()), localReceiveTimeInNanos, localReceiveTime);
            messageRejects.onMessageRejects(messageType, incomingSeqNum, List.of(reject));
            return;
        }

        // check first if we have a pending resend request, onDecoded() may clear the state of the pending request
        boolean pendingResendRequest = retransmission.getResendRecovery().hasPendingResendRequest();
        fixMessageDecoder.onDecoded(fixSession, possibleDuplicate, possResend);
        plugins.onMessageDecodingFinished(messageType, localReceiveTimeInNanos, localReceiveTime);

        // some decoders settle what comes next themselves - a SequenceReset jumping to NewSeqNo or over a gap filled
        // range, a Logon that restarted the numbering or completed a retransmission - and their own MsgSeqNum belongs
        // to the numbering they replaced, so it must not be used to derive the next one. Asked after onDecoded, which
        // is where they decide
        boolean sequenceManagedByDecoder = fixMessageDecoder.managesIncomingSequenceNumber();
        long nextIncomingSeqNum = sequenceManagedByDecoder
                ? fixSessionMessagesStore.getIncomingSeqNum()
                : incomingSeqNum + 1;
        // stored before the resend is told about this message: completing a resend schedules the replay of what was
        // queued on top of the gap, and that replay reads what the session expects next from the store
        fixSessionMessagesStore.storeNextIncomingSeqNum(nextIncomingSeqNum);
        if (pendingResendRequest && !sequenceManagedByDecoder) {
            // an ordinary message of the range settles its own MsgSeqNum(34) and nothing else; the decoders that
            // settle more than that say so themselves, which is why they are excluded here
            retransmission.getResendRecovery().onSequenceNumbersSettledUpTo(incomingSeqNum);
        }
    }

    @Override
    public void onMessageDecodingFailed(MessageType messageType, FixMessageDecoder fixMessageDecoder, long incomingSeqNum,
                                        DecodingException decodingFailureCause, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        // a garbled message is not inbound activity: it must not reset the heartbeat interval timer
        boolean garbled = decodingFailureCause instanceof GarbledMessageException;
        if (garbled) {
            fixSession.logEvent("Garbled message received, ignoring it: %s", ((GarbledMessageException) decodingFailureCause).getReason());
        } else {
            fixSessionLayerComponents.onMessageReceived(localReceiveTime);
        }
        if (decodingFailureCause instanceof WrongBeginStringException) {
            // incorrect BeginString(8): reference the offending value in a Logout and disconnect
            WrongBeginStringException wrongBeginString = (WrongBeginStringException) decodingFailureCause;
            String message = String.format("Received message with incorrect BeginString(8): expected %s but got %s",
                    wrongBeginString.getExpectedBeginString(), wrongBeginString.getReceivedBeginString());
            fixSession.logEvent(message);
            fixSession.logout(message);
            fixMessageDecoder.onDecodingFailed(fixSession, decodingFailureCause);
            return;
        }

        // settled before the decoder is told anything: the out of sequence handling below queues the message and asks
        // for the gap ahead of it, and the decoders that answer while out of sequence - Logon(35=A) and
        // ResendRequest(35=2) - build their answer on top of that having happened
        RolledBackSequenceNumber sequenceNumber = onRolledBackSequenceNumber(messageType, decodingFailureCause, garbled);

        fixMessageDecoder.onDecodingFailed(fixSession, decodingFailureCause);
        if (!(decodingFailureCause instanceof NotEnoughDataException)) {
            // in case of NotEnoughDataException message will be shortly reprocessed and normally
            // onDecodingStarted has never been called in such case
            plugins.onMessageDecodingFinished(messageType, localReceiveTimeInNanos, localReceiveTime);
        }

        if (sequenceNumber == RolledBackSequenceNumber.LEFT_UNCHANGED) {
            return;
        }
        // a decoder may have settled the incoming sequence number itself even on this path: a SequenceReset(35=4)
        // rolled back after it was read has left NextNumIn where the reset it refused, or applied, decided it goes,
        // and deriving one from this message's MsgSeqNum(34) would undo that. See managesIncomingSequenceNumber()
        if (!fixMessageDecoder.managesIncomingSequenceNumber()) {
            fixSessionMessagesStore.storeNextIncomingSeqNum(incomingSeqNum + 1);
        }
    }

    private RolledBackSequenceNumber onRolledBackSequenceNumber(MessageType messageType, DecodingException decodingFailureCause, boolean garbled) {
        if (garbled) {
            // section 4.8: a garbled message is disregarded as if it had never been received. The peer keeps
            // incrementing its own MsgSeqNum(34), so whatever it sends next surfaces as a gap and drives the recovery
            return RolledBackSequenceNumber.LEFT_UNCHANGED;
        }
        if (decodingFailureCause instanceof NotEnoughDataException) {
            // not processed at all, merely truncated: it is parsed again once the rest of it arrives, and counting it
            // now would have that second parse look too low
            return RolledBackSequenceNumber.LEFT_UNCHANGED;
        }
        if (decodingFailureCause instanceof WrongSeqNumException) {
            WrongSeqNumException wrongSeqNumException = (WrongSeqNumException) decodingFailureCause;
            fixSession.logEvent("Received out of order message %s, expecting %s but got %s", messageType,
                    wrongSeqNumException.getExpectedMsgSeqNum(), wrongSeqNumException.getMsgSeqNum());
            if (wrongSeqNumException.getMsgSeqNum() > wrongSeqNumException.getExpectedMsgSeqNum()) {
                onMsgSeqNumTooHigh(messageType, wrongSeqNumException);
            } else {
                onMsgSeqNumTooLow(messageType, wrongSeqNumException);
            }
            // whichever way it was out of sequence, this message settles nothing: what the session expects next is
            // moved on by the recovery, by the peer's retransmission filling the gap ahead of a message too high or
            // by the session that a message too low has just taken down
            return RolledBackSequenceNumber.LEFT_UNCHANGED;
        }
        // anything else - a validation failure, a rejected message - is a message that arrived in sequence and was
        // read: it is answered with a Reject(35=3) rather than replayed, so its MsgSeqNum(34) is consumed
        return RolledBackSequenceNumber.CONSUMED;
    }

    /**
     * Section 4.5 state table rows 11 and 12: queue the message and ask for the range missing ahead of it. The peer
     * will not resend this one - it was delivered - so it has to be replayed once the gap has been filled.
     * <p>
     * A Logon(35=A) is the exception on both counts. It is processed on arrival by its decoder, out of sequence and
     * all, so only its MsgSeqNum(34) is still owed and there is nothing to replay. And it has to be acknowledged
     * before the ResendRequest(35=2) goes out - test case scenario 1S-a orders the two, and section 4.3.12 calls the
     * retransmission "synchronization after successful logon" - which only {@code LogonFixMessageDecoder} can time,
     * the application being free to validate the logon asynchronously, so it issues the request itself right after
     * the acknowledgement.
     */
    private void onMsgSeqNumTooHigh(MessageType messageType, WrongSeqNumException wrongSeqNumException) {
        long receivedSeqNum = wrongSeqNumException.getMsgSeqNum();
        boolean logon = messageType.code().equals(CoreMessageType.LOGON);
        // a ResendRequest is answered on arrival too, by its own decoder and for its own reason: holding it
        // back would deadlock two peers that each reconnect with a gap. Only its MsgSeqNum is left owed.
        boolean answeredOnArrival = logon || messageType.code().equals(CoreMessageType.RESEND_REQUEST);
        retransmission.getResendRecovery().queueOutOfSequenceMessage(answeredOnArrival, receivedSeqNum, wrongSeqNumException.getRawMessage());
        if (!logon && !retransmission.getResendRecovery().hasPendingResendRequest()) {
            retransmission.requestRetransmission(wrongSeqNumException.getExpectedMsgSeqNum(), receivedSeqNum - 1, "MsgSeqNum too high");
        }
    }

    /**
     * A MsgSeqNum(34) below what the session expects, which section 4.8.1 makes fatal unless the message says why it
     * is a duplicate. Only gap fills reach here among the SequenceReset(35=4)s: a hard reset ignores its own
     * MsgSeqNum(34) and never takes the out of sequence path at all, see
     * {@link FixMessageDecoder#ignoresIncomingSequenceNumber()}.
     */
    private void onMsgSeqNumTooLow(MessageType messageType, WrongSeqNumException wrongSeqNumException) {
        long receivedSeqNum = wrongSeqNumException.getMsgSeqNum();
        long expectedSeqNum = wrongSeqNumException.getExpectedMsgSeqNum();
        if (wrongSeqNumException.isPossDup()) {
            // PossDupFlag(43)=Y: a legitimate retransmission of an already processed message, ignored - it has
            // already been received
            fixSession.logEvent("Ignoring already processed PossDup message %s with MsgSeqNum %s, expecting %s",
                    messageType, receivedSeqNum, expectedSeqNum);
            return;
        }
        if (isOverrunGapFillOfOurOwnOpenEndedRequest(messageType)) {
            // the tail of an open ended ResendRequest of ours, landing after the queue on top of the gap has been
            // replayed and taken NextNumIn past it. Section 4.8.1 has this terminate the session, and it stays that
            // way for a session asking a closed range - a peer answering within the range it named has no reason to
            // reach here. This one was asked for everything from BeginSeqNo(7) onwards, so the gap fill covering what
            // it chose not to retransmit is its answer arriving a moment late, saying nothing this session has not
            // already worked out for itself.
            fixSession.logEvent("Ignoring gap fill with MsgSeqNum %s past the end of our open ended ResendRequest, expecting %s",
                    receivedSeqNum, expectedSeqNum);
            return;
        }
        // too low without PossDupFlag(43): unrecoverable, log out and disconnect
        fixSession.logout(String.format("MsgSeqNum too low, expecting %s but received %s", expectedSeqNum, receivedSeqNum));
    }

    /**
     * @see ResendRecovery#isAnsweringOwnOpenEndedRequest()
     */
    private boolean isOverrunGapFillOfOurOwnOpenEndedRequest(MessageType messageType) {
        return messageType.code().equals(CoreMessageType.SEQUENCE_REQUEST)
                && retransmission.getResendRecovery().isAnsweringOwnOpenEndedRequest();
    }

    /**
     * What a rolled back message leaves behind for NextNumIn, which is the one thing every branch of
     * {@link #onMessageDecodingFailed} has to agree on.
     */
    private enum RolledBackSequenceNumber {
        /**
         * The message was received and its MsgSeqNum(34) is accounted for, so the numbering moves on past it.
         */
        CONSUMED,
        /**
         * NextNumIn stays where it is, the session still expecting the sequence number this message carried - or,
         * where the message was ahead of it, the one it is still missing.
         */
        LEFT_UNCHANGED
    }
}
