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

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.codec.DecodingException;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.SessionRejectReasonCodes;
import org.lolaf.staffix.api.codec.WrongSeqNumException;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.CancelOnDisconnectType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.version.ApplVerID;
import org.lolaf.staffix.codec.decoders.FixMessageResendTransformer;
import org.lolaf.staffix.codec.decoders.MessageReject;
import org.lolaf.staffix.impl.session.FixSessionImpl;
import org.lolaf.staffix.impl.session.FixSessionImplState;
import org.lolaf.staffix.impl.session.MessagesResender;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Decodes a Logon(35=A) - the message that establishes the session and settles what it will run on.
 *
 * <p>The only admin message that may legitimately arrive with a sequence number lower than expected, when
 * ResetSeqNumFlag(141)=Y restarts the numbering, so it is also the only one allowed to move what the session
 * expects next.
 */
@Slf4j
@Setter
@Getter
public class LogonFixMessageDecoder extends AbstractAdminFixMessageDecoder {

    private static final int SUPPORTED_ENCRYPT_METHOD = 0;
    private static final long NOTHING_TO_RETRANSMIT = 0;
    private static final String REJECTING_LOGON = "Rejecting logon: %s";

    private final FixField codTypeField;
    private final FixField codWindowField;
    private final Map<Character, CancelOnDisconnectType> codFieldsMappings;
    private MessagesResender messagesResender;
    private long msgSeqNum;
    private int heartbeatInterval;
    private int peerMaxMessageSize;
    private Boolean resetSeqNum;
    private Boolean testMessageIndicator;
    private boolean incomingSequenceNumberManaged;

    public LogonFixMessageDecoder(MessageType messageType, AdminMessageCodecContext adminMessageCodecContext, FixAdminMessagesCodec fixAdminMessagesCodec) {
        super(messageType, adminMessageCodecContext, fixAdminMessagesCodec);
        FixSessionSettings.CancelOnDisconnectSettings cancelOnDisconnectSettings = adminMessageCodecContext.getFixSessionSettings().getCancelOnDisconnectSettings();
        codTypeField = adminMessageCodecContext.getFieldsRegistry().find(cancelOnDisconnectSettings.getCancelOnDisconnectTypeFieldCode());
        codWindowField = adminMessageCodecContext.getFieldsRegistry().find(cancelOnDisconnectSettings.getCodTimeoutWindowFieldCode());
        if (codTypeField != null && codWindowField != null) {
            codFieldsMappings = new HashMap<>();
            cancelOnDisconnectSettings.getCancelOnDisconnectTypeFieldCodes()
                    .forEach((codType, code) -> codFieldsMappings.put(code, codType));
        } else {
            codFieldsMappings = null;
        }
    }

    @Override
    public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
        fixFieldsDecoderMapper.mapLongField(getFieldsRegistry().find(CoreFields.MESSAGE_SEQ_NUM), this::setMsgSeqNum, 0)
                .mapIntField(getFieldsRegistry().find(CoreFields.HEARTBEAT_INTERVAL), this::setHeartbeatInterval, 0)
                .mapIntField(getFieldsRegistry().find(CoreFields.MAX_MESSAGE_SIZE), this::setPeerMaxMessageSize, 0)
                .mapBooleanObjectField(getFieldsRegistry().find(CoreFields.RESET_NUM_FLAG), this::setResetSeqNum, null);
        super.mapFieldsForDecoding(fixFieldsDecoderMapper, fieldsRegistry);
    }

    @Override
    public boolean managesIncomingSequenceNumber() {
        // set by countThisLogonAsFirstOfTheNewNumbering, the only thing that sets it: what comes next has been stored
        // against the numbering this Logon(35=A) restarted, and deriving it from the message's own MsgSeqNum(34) -
        // which belongs to the numbering that was just replaced - would undo that
        return incomingSequenceNumberManaged;
    }

    /**
     * Counts the Logon(35=A) being processed as message 1 of the numbering it has just restarted, and takes the
     * incoming sequence number over from the session layer for it.
     * <p>
     * The two go together and must never be done apart, which is why nothing else writes
     * {@link #incomingSequenceNumberManaged}. Storing without the flag has the session layer derive what comes next
     * from this message's MsgSeqNum(34) and overwrite it; raising the flag without storing leaves NextNumIn behind
     * for good, so everything the peer sends afterwards arrives out of sequence into a session that has stopped
     * counting.
     *
     * @see FixSessionImpl#onMessageDecoded
     */
    private void countThisLogonAsFirstOfTheNewNumbering() {
        FixMessagesStore.FixSessionMessagesStore store = getFixSessionMessagesStore();
        store.storeNextIncomingSeqNum(store.getIncomingSeqNum() + 1);
        incomingSequenceNumberManaged = true;
    }

    @Override
    public boolean ignoresIncomingSequenceNumber() {
        // a Logon with ResetSeqNumFlag(141)=Y resets both sequence numbers to 1, so it must be processed regardless
        // of its MsgSeqNum(34): treating it as out of sequence would race the reset against a "MsgSeqNum too low"
        // logout and let the session flap. The reset itself is applied in finishLogon.
        return resetSeqNum != null && resetSeqNum;
    }

    @Override
    public void onDecodedLocal(FixSession fixSession, boolean possDupFlag, boolean possResend) {
        if (getFixSessionSettings().getResetSeqNumOnLogon() == null && resetSeqNum == null) {
            // Neither end has an opinion: this session leaves the flag to the counterparty and the counterparty did
            // not send one. ResetSeqNumFlag(141) is an optional field, so a Logon without it simply means no reset,
            // and the session carries on from the sequence numbers it already has. Worth a line while diagnosing a
            // session that resets when it should not, or the reverse, but it is not an error.
            log.debug("Received a logon request with no ResetSeqNumFlag(141) and no reset sequence on logon configured, not resetting");
        }
        processLogonRequest(fixSession, null, peerMaxMessageSize);
    }

    @Override
    public void onDecodingFailedLocal(FixSession fixSession, DecodingException decodingException) {
        if (decodingException instanceof WrongSeqNumException) {
            processLogonRequest(fixSession, (WrongSeqNumException) decodingException, peerMaxMessageSize);
        } else {
            super.onDecodingFailedLocal(fixSession, decodingException);
        }
    }

    protected Long getNextExpectedMsgSeqNum() {
        return null;
    }

    private void processLogonRequest(FixSession fixSession, WrongSeqNumException wrongSeqNumException, int peerMaxMessageSize) {
        // reset here for every Logon decoded, the decoder outliving the message, and turned on again further down by
        // whichever branch of finishLogon settles the sequence itself.
        //
        // Left off while a retransmission is under way, as it used to be turned on: a Logon is never put back on the
        // wire by this engine - it is gap filled like any other admin message - but a peer is free to retransmit one,
        // and it is then a message of the range like any other. Declining to count its MsgSeqNum(34) leaves NextNumIn
        // below everything that follows it in the answer, so the rest of the range arrives out of sequence into a
        // request that can no longer complete, and the session stalls with the recovery half done.
        incomingSequenceNumberManaged = false;
        Long nextExpectedMsgSeqNum = getFixSessionSettings().isEnabledLogonNextExpectedMsgSeqNum() ? getNextExpectedMsgSeqNum() : null;
        DecodedFixMessage logonMessage = getDecodedFixMessage();
        FixSessionImpl fixSessionImpl = getFixSession();
        if (rejectIfUnsupportedApplVerId(fixSessionImpl, logonMessage)) {
            return;
        }
        if (rejectIfUnsupportedEncryptMethod(fixSessionImpl, logonMessage)) {
            return;
        }
        if (rejectIfMsgSeqNumTooLow(fixSessionImpl, wrongSeqNumException)) {
            return;
        }
        // NextExpectedMsgSeqNum(789) must carry the next sequence number we expect to receive from the peer (see
        // section 4.4.1 of the FIX Session Layer specification). It is captured here, while still running inline in
        // the decoding of the Logon: finishLogon may run later on a task, once FixSessionImpl has already stored
        // the incoming sequence number, and would then read a value one too high. An out of sequence Logon leaves
        // the gap unfilled, so what we expect next is unchanged.
        long nextExpectedIncomingSeqNum = getFixSessionMessagesStore().getIncomingSeqNum()
                + (wrongSeqNumException == null ? 1 : 0);
        boolean testMessageIndicatorFlag = testMessageIndicator != null && testMessageIndicator;
        CompletableFuture<Optional<String>> logonRejectionMessageFuture = validateOnLogon(fixSessionImpl, logonMessage, testMessageIndicatorFlag, peerMaxMessageSize);
        if (logonRejectionMessageFuture != null) {
            int heartbeatIntervalLocal = heartbeatInterval;
            DecodedFixMessage logonMessageLocal = logonMessage.copy();
            Boolean resetSeqNumFlagLocal = resetSeqNum;
            logonRejectionMessageFuture.whenComplete((logonRejectionMessageOptional, error) -> {
                if (error != null) {
                    fixSession.processTask(() -> {
                        log.error("Error when validating logon request", error);
                        fixSession.logEvent("Rejecting logon request due to failure: %s", error.getMessage());
                        fixSessionImpl.sendLogoutRequest("Failed to validate logon request", true);
                        // cannot call session.logout() because need to be logged in to send message
                    });
                } else {
                    logonRejectionMessageOptional.ifPresentOrElse(logonRejectionMessage ->
                                    fixSession.processTask(() -> {
                                        fixSession.logEvent(REJECTING_LOGON, logonRejectionMessage);
                                        fixSessionImpl.sendLogoutRequest(logonRejectionMessage, true);
                                    }),
                            () -> fixSession.processTask(() -> finishLogon(wrongSeqNumException, fixSessionImpl, heartbeatIntervalLocal, resetSeqNumFlagLocal, nextExpectedMsgSeqNum, nextExpectedIncomingSeqNum, logonMessageLocal)));
                }
            });
        } else {
            finishLogon(wrongSeqNumException, fixSessionImpl, heartbeatInterval, resetSeqNum, nextExpectedMsgSeqNum, nextExpectedIncomingSeqNum, logonMessage);
        }
    }

    private void finishLogon(WrongSeqNumException wrongSeqNumException, FixSessionImpl fixSession, int heartbeatInterval, Boolean resetSeqNumFlag,
                             Long nextExpectedMsgSeqNum, long nextExpectedIncomingSeqNum, DecodedFixMessage logonMessage) {
        FixSessionSettings fixSessionSettings = getFixSessionSettings();
        FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore = getFixSessionMessagesStore();
        FixSessionImplState fixSessionImplState = getFixSessionImplState();

        // Section 4.4.2, a reset carried out over a session that is already up. Two Logons carrying
        // ResetSeqNumFlag(141)=Y cross on a live session - the proposal and its acknowledgement - and they have to be
        // told apart or each end answers the other's answer for ever. The flag below is set only by the side that
        // sent the proposal, and is consumed by the one message that answers it.
        boolean acknowledgesOwnInSessionReset = fixSessionImplState.consumeInSessionResetPending();
        boolean inSessionReset = Boolean.TRUE.equals(resetSeqNumFlag)
                && fixSessionImplState.isLoggedIn()
                && !acknowledgesOwnInSessionReset;

        // canSendLoginResponse covers bringing a session up, and admits acceptors only; 4.4.2 lets either peer start
        // a reset, so an initiator has to be able to acknowledge one too
        boolean canSendLoginResponse = fixSessionImplState.canSendLoginResponse() || inSessionReset;

        // NextExpectedMsgSeqNum(789) synchronization, section 4.4.1. The range to put back on the wire is only worked
        // out here: it goes out after our own Logon(35=A) does, further down.
        long retransmitFromSeqNum = NOTHING_TO_RETRANSMIT;
        long retransmitToSeqNum = NOTHING_TO_RETRANSMIT;
        if (nextExpectedMsgSeqNum != null && (resetSeqNumFlag == null || !resetSeqNumFlag)) {
            long currentOutgoingSeqNum = fixSessionMessagesStore.getOutgoingSeqNum();
            if (nextExpectedMsgSeqNum > currentOutgoingSeqNum) {
                // the peer expects a message we have never sent: its view of the session is broken beyond recovery
                String msg = String.format("NextExpectedMsgSeqNum is higher than expected: expected %d, received %d", currentOutgoingSeqNum, nextExpectedMsgSeqNum);
                fixSession.sendLogoutRequest(msg, true);
                return;
            }
            // "perform message recovery for messages starting with the message with MsgSeqNum(34) equal to the
            // NextExpectedMsgSeqNum(789) through to the message with MsgSeqNum(34) equal to NextNumOut - 1", except
            // that our own Logon(35=A) consumed a MsgSeqNum too and the peer is processing it as we speak: it is not
            // missing and must not be retransmitted. An acceptor has not sent its acknowledgement yet at this point,
            // so its NextNumOut does not cover it; an initiator sent its Logon before the acknowledgement it is
            // handling here came back, so its own does.
            boolean ownLogonAlreadySent = fixSessionSettings.getFixSessionType().equals(FixSession.FixSessionType.INITIATOR);
            long lastSeqNumPeerMayMiss = currentOutgoingSeqNum - (ownLogonAlreadySent ? 2 : 1);
            if (lastSeqNumPeerMayMiss >= nextExpectedMsgSeqNum) {
                retransmitFromSeqNum = nextExpectedMsgSeqNum;
                retransmitToSeqNum = lastSeqNumPeerMayMiss;
                fixSession.logEvent("Peer expects MsgSeqNum %s while %s were sent, retransmitting %s -> %s",
                        nextExpectedMsgSeqNum, lastSeqNumPeerMayMiss, retransmitFromSeqNum, retransmitToSeqNum);
            }
            if (wrongSeqNumException != null) {
                // the peer retransmits on its own, driven by the NextExpectedMsgSeqNum(789) our own Logon carries. The
                // range is still tracked as pending so that the recovery terminates and the messages queued on top of
                // the gap - this Logon among them - are replayed once it is filled.
                fixSession.awaitPeerRetransmission(wrongSeqNumException.getExpectedMsgSeqNum(),
                        wrongSeqNumException.getMsgSeqNum() - 1, "Received out of order Logon");
            }
        }

        if (Boolean.TRUE.equals(resetSeqNumFlag) && Boolean.FALSE.equals(fixSessionSettings.getResetSeqNumOnLogon())) {
            // Section 4.4.3: "if the peer is not configured to accept resetting of an inbound session the peer should
            // send a Logout(35=5) with Text(58) indicating that resetting the sequence number is not supported and
            // then terminate the transport layer connection". Explicitly false is that configuration - null instead
            // means the counterparty manages the flag, and is followed. Ignoring the reset, as this used to, leaves
            // the peer numbering from 1 while we carry on where we were, which just flaps the connection.
            String msg = "Resetting the sequence number is not supported by this session";
            fixSession.logEvent(msg);
            fixSession.sendLogoutRequest(msg, true);
            return;
        }

        // short circuited on purpose: nothing of the acknowledgement happens for a Logon this side may not answer
        boolean sequenceResetDone = canSendLoginResponse
                && resetSequenceAndAcknowledgeLogon(fixSession, heartbeatInterval, resetSeqNumFlag, nextExpectedMsgSeqNum, nextExpectedIncomingSeqNum);

        settleInSessionResetNumbering(fixSession, resetSeqNumFlag, acknowledgesOwnInSessionReset, inSessionReset);

        if (!sequenceResetDone && wrongSeqNumException != null && !getResendRecovery().hasPendingResendRequest()) {
            fixSession.requestRetransmission(wrongSeqNumException.getExpectedMsgSeqNum(),
                    wrongSeqNumException.getMsgSeqNum() - 1, "Received out of order Logon");
        }

        bringSessionUp(fixSession, heartbeatInterval, logonMessage);

        if (retransmitFromSeqNum != NOTHING_TO_RETRANSMIT && !sequenceResetDone) {
            // after our own Logon(35=A), so the peer completes its logon before the retransmitted messages reach it.
            // Skipped when the sequence has just been reset, the range having been computed against the old numbering.
            getMessagesResender().resendMessages(retransmitFromSeqNum, retransmitToSeqNum);
        }
    }

    /**
     * Answers the peer's Logon(35=A) with one of our own, restarting the numbering first where either end asked for
     * it - the peer through ResetSeqNumFlag(141)=Y, this side through
     * {@link FixSessionSettings#getResetSeqNumOnLogon()}.
     *
     * @return whether the sequence numbers were reset, which the rest of the exchange turns on: the acknowledgement
     * carries the flag, the Logon just processed becomes message 1 of the new numbering, and a retransmission worked
     * out against the numbering that has just been replaced must not go out
     */
    private boolean resetSequenceAndAcknowledgeLogon(FixSessionImpl fixSession, int heartbeatInterval, Boolean resetSeqNumFlag,
                                                     Long nextExpectedMsgSeqNum, long nextExpectedIncomingSeqNum) {
        FixSessionSettings fixSessionSettings = getFixSessionSettings();
        FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore = getFixSessionMessagesStore();
        boolean sequenceResetDone = false;
        boolean shouldResetSequenceOnLogon = fixSessionSettings.getResetSeqNumOnLogon() != null && fixSessionSettings.getResetSeqNumOnLogon();
        if (Boolean.TRUE.equals(resetSeqNumFlag)) {
            resetSequence(fixSession, "Received initiator ResetSeqNumFlag");
            sequenceResetDone = true;
        } else if (shouldResetSequenceOnLogon) {
            resetSequence(fixSession, "Acceptor ResetSeqNumOnLogon enabled");
            sequenceResetDone = true;
        }

        if (nextExpectedMsgSeqNum != null) {
            // the peer told us what it expects next, we must answer with what we expect next from it. When the
            // sequence numbers have just been reset the captured value belongs to the previous numbering, so it
            // is recomputed the same way the reset below does.
            nextExpectedMsgSeqNum = sequenceResetDone
                    ? fixSessionMessagesStore.getIncomingSeqNum() + 1
                    : nextExpectedIncomingSeqNum;
        }
        fixSession.send(getFixAdminMessagesCodec().generateLogin(heartbeatInterval, sequenceResetDone,
                fixSessionSettings, getFixApplication().getFixApiVersion(), nextExpectedMsgSeqNum, fixSession.getIncomingMessageTypes(), fixSession.getOutgoingMessageTypes()), null);
        if (sequenceResetDone) {
            // the Logon(35=A) just processed is message 1 of the new numbering, so what we expect next is 2 -
            // section 4.4.2: "upon completion of the session reset, both peers must have NextNumIn = 2 and
            // NextNumOut = 2". The acknowledgement sent above already moved NextNumOut on by itself.
            countThisLogonAsFirstOfTheNewNumbering();
        }
        return sequenceResetDone;
    }

    /**
     * Section 4.4.2, the two ends of a reset carried out over a live session: this side's proposal coming back
     * acknowledged, or the peer's own reset arriving for this side to fall in behind. Both leave a Logon(35=A) whose
     * MsgSeqNum(34) nothing else will count, the reset having had it deliberately ignored.
     */
    private void settleInSessionResetNumbering(FixSessionImpl fixSession, Boolean resetSeqNumFlag,
                                               boolean acknowledgesOwnInSessionReset, boolean inSessionReset) {
        FixSessionSettings fixSessionSettings = getFixSessionSettings();
        FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore = getFixSessionMessagesStore();
        if (acknowledgesOwnInSessionReset) {
            // closing the exchange this side started: the numbering was put back to 1 before the proposal went out and
            // that Logon was message 1 of it, so NextNumOut is already 2. All that is left is this acknowledgement,
            // which is the peer's own message 1.
            countThisLogonAsFirstOfTheNewNumbering();
            fixSession.logEvent("In-session sequence reset acknowledged by the peer");
        } else if (!inSessionReset
                && fixSessionSettings.getFixSessionType().equals(FixSession.FixSessionType.INITIATOR)
                && fixSessionSettings.getResetSeqNumOnLogon() == null
                && Boolean.TRUE.equals(resetSeqNumFlag)) {
            resetSequence(fixSession, "Received acceptor ResetSeqNumFlag");
            // the mirror image of the acknowledgement case above: the peer reset the session on its own and counted
            // the Logon we had already sent as message 1 of the new numbering, so ours has to carry on at 2 rather
            // than restart at 1. NextNumIn is left to the usual processing of this acknowledgement, which is its own
            // message 1.
            fixSessionMessagesStore.storeNextOutgoingSeqNum(fixSessionMessagesStore.getOutgoingSeqNum() + 1);
            // this acknowledgement is message 1 of the new numbering, so what we expect next is 2
            countThisLogonAsFirstOfTheNewNumbering();
        }
    }

    /**
     * The session is established as soon as the Logon(35=A) exchange completes, whether or not messages still have to
     * be recovered: section 4.3.12 is titled "synchronization after successful logon", and test case scenario 1S-a
     * acknowledges the Logon before dealing with the sequence gap. Applications are told the recovery is over through
     * {@code onResendRequestTerminated}.
     */
    private void bringSessionUp(FixSessionImpl fixSession, int heartbeatInterval, DecodedFixMessage logonMessage) {
        AtomicReference<CancelOnDisconnectType> codTypeEnum = new AtomicReference<>();
        AtomicInteger codTimeoutWindowInMillis = new AtomicInteger();
        handleCancelOnDisconnectLogonInstructions(getFixSessionSettings(), fixSession, logonMessage,
                getFixSessionMessagesStore(), codTypeEnum, codTimeoutWindowInMillis);
        getFixSessionImplState().onLogon(heartbeatInterval, codTypeEnum.get(), codTimeoutWindowInMillis.get());
        getFixApplication().onLogon(getFixSession(), logonMessage);
    }

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
                    getFixSession());
        }
        return messagesResender;
    }

    private void handleCancelOnDisconnectLogonInstructions(FixSessionSettings fixSessionSettings, FixSessionImpl fixSession, DecodedFixMessage logonMessage,
                                                           FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore,
                                                           AtomicReference<CancelOnDisconnectType> codTypeEnum, AtomicInteger codTimeoutWindowInMillis) {
        if (fixSessionSettings.getFixSessionType().equals(FixSession.FixSessionType.INITIATOR)) {
            return;
        }
        if (codTypeField != null) {
            char codType = logonMessage.getChar(codTypeField, '\001');
            if (codType != '\001') {
                codTypeEnum.set(codFieldsMappings.get(codType));
                if (codTypeEnum.get() == null) {
                    MessageReject reject = MessageReject.builder()
                            .message("Invalid COD enum value '" + codType + "', allowed values are: " + codFieldsMappings.keySet())
                            .refTagId(codTypeField.getCode())
                            .sessionRejectReasonCode(SessionRejectReasonCodes.VALUE_IS_INCORRECT)
                            .build();
                    fixSession.onMessageRejects(getMessageType(), this, fixSessionMessagesStore.getIncomingSeqNum(), List.of(reject));
                }
            }
            TimeUnit codWindowScale = getFixSessionSettings().getCancelOnDisconnectSettings().getCodTimeoutWindowScale();
            Duration minimalCodWindow = getFixSessionSettings().getCancelOnDisconnectSettings().getCodTimeoutWindow();
            int codWindow = logonMessage.getInt(codWindowField, Integer.MAX_VALUE);
            if (codWindow == Integer.MAX_VALUE) {
                // nothing provided, get default setting
                codWindow = (int) codWindowScale.convert(getFixSessionSettings().getCancelOnDisconnectSettings().getCodTimeoutWindow());
            }
            if (Duration.of(codWindow, codWindowScale.toChronoUnit()).compareTo(minimalCodWindow) < 0) {
                MessageReject reject = MessageReject.builder()
                        .message("Invalid COD timeout window value '" + codWindow + "', must be minimum " + minimalCodWindow.toMillis() + " milliseconds")
                        .refTagId(codWindowField.getCode())
                        .sessionRejectReasonCode(SessionRejectReasonCodes.VALUE_IS_INCORRECT)
                        .build();
                fixSession.onMessageRejects(getMessageType(), this, fixSessionMessagesStore.getIncomingSeqNum(), List.of(reject));
                codTypeEnum.set(null);
                codTimeoutWindowInMillis.set(0);
            } else {
                codTimeoutWindowInMillis.set((int) Duration.of(codWindow, codWindowScale.toChronoUnit()).toMillis());
            }
        }
        // nothing provided in the logon message fields, let's see if acceptor is configured to trigger COD even when nothing received from logon messages
        if (codTypeEnum.get() == null && getFixSessionSettings().getCancelOnDisconnectSettings().getCancelOnDisconnectType() != null) {
            codTimeoutWindowInMillis.set((int) getFixSessionSettings().getCancelOnDisconnectSettings().getCodTimeoutWindow().toMillis());
            codTypeEnum.set(getFixSessionSettings().getCancelOnDisconnectSettings().getCancelOnDisconnectType());
        }
    }

    private CompletableFuture<Optional<String>> validateOnLogon(FixSession fixSession, DecodedFixMessage logonMessage, boolean testMessageIndicator, int peerMaxMessageSize) {
        FixSessionSettings fixSessionSettings = getFixSessionSettings();
        FixSessionImplState fixSessionImplState = getFixSessionImplState();
        if (testMessageIndicator && !fixSessionSettings.isTestingMode()) {
            return CompletableFuture.completedFuture(Optional.of("Session not configured for accepting login with TestMessageIndicator(464) enabled"));
        }
        if (fixSessionSettings.isTestingMode() && !testMessageIndicator) {
            return CompletableFuture.completedFuture(Optional.of("Session configured for only accepting login with TestMessageIndicator(464) enabled"));
        }
        if (!fixSessionImplState.isInsideSessionTime()) {
            return CompletableFuture.completedFuture(Optional.of("Logon attempt outside of configured session time"));
        }
        if (!fixSessionImplState.getDesiredState().equals(org.lolaf.staffix.api.session.FixSessionState.LOGGED_IN)) {
            return CompletableFuture.completedFuture(Optional.of("Logon rejected, session not setup to accept login requests for now"));
        }
        if (fixSessionSettings.getFixSessionType().equals(FixSession.FixSessionType.ACCEPTOR)) {
            long lowerBoundSeconds = fixSessionSettings.getHeartBeatInterval().getAcceptorLowerBoundInterval().toSeconds();
            long upperBoundSeconds = fixSessionSettings.getHeartBeatInterval().getAcceptorUpperBoundInterval().toSeconds();
            if (heartbeatInterval < lowerBoundSeconds || heartbeatInterval > upperBoundSeconds) {
                return CompletableFuture.completedFuture(Optional.of(
                        "HeartBtInt(108) = " + heartbeatInterval + " is not within accepted bounds ["
                                + lowerBoundSeconds + ", " + upperBoundSeconds + "]"));
            }
        }
        // Terminate when the peer's advertised MaxMessageSize(383) (what the peer is able to receive) is smaller than
        // the largest message we may need to send it. Per the FIX session layer spec this applies to both the acceptor
        // (validating the initiator's Logon) and the initiator (validating the acceptor's Logon response).
        Integer requiredPeerMaxMessageSize = fixSessionSettings.getValidationSettings().getRequiredPeerMaxMessageSize();
        if (requiredPeerMaxMessageSize != null && peerMaxMessageSize != 0 && peerMaxMessageSize < requiredPeerMaxMessageSize) {
            return CompletableFuture.completedFuture(Optional.of(
                    "MaxMessageSize(383) = " + peerMaxMessageSize + " < required message size " + requiredPeerMaxMessageSize));
        }
        return getFixApplication().validateLogon(fixSession, logonMessage, getExecutor());
    }

    private boolean rejectIfUnsupportedApplVerId(FixSessionImpl fixSession, DecodedFixMessage logonMessage) {
        if (getFixSessionSettings().getFixSessionType().equals(FixSession.FixSessionType.INITIATOR)) {
            return false;
        }
        ApplVerID configuredApplVerId = getFixSessionSettings().getFixSessionId().getDefaultApplVerID();
        if (configuredApplVerId == null) {
            // not a FIXT session, DefaultApplVerID(1137) does not apply
            return false;
        }
        FixField defaultApplVerIdField = getFieldsRegistry().find(CoreFields.DEFAULT_APPL_VER_ID);
        if (defaultApplVerIdField == null) {
            return false;
        }
        String incomingApplVerId = logonMessage.getString(defaultApplVerIdField, null);
        if (incomingApplVerId == null || incomingApplVerId.equals(configuredApplVerId.getCode())) {
            return false;
        }
        String rejectText = "Invalid or unsupported DefaultApplVerID(1137)='" + incomingApplVerId
                + "', this session only supports application version '" + configuredApplVerId.getCode() + "'";
        fixSession.logEvent(REJECTING_LOGON, rejectText);
        MessageReject reject = MessageReject.builder()
                .message(rejectText)
                .refTagId(CoreFields.DEFAULT_APPL_VER_ID)
                .sessionRejectReasonCode(SessionRejectReasonCodes.INVALID_UNSUPPORTED_APPL_VER)
                .build();
        fixSession.onMessageRejects(getMessageType(), this, getFixSessionMessagesStore().getIncomingSeqNum(), List.of(reject));
        fixSession.sendLogoutRequest(rejectText, true);
        return true;
    }

    private boolean rejectIfUnsupportedEncryptMethod(FixSessionImpl fixSession, DecodedFixMessage logonMessage) {
        if (getFixSessionSettings().getFixSessionType().equals(FixSession.FixSessionType.INITIATOR)) {
            return false;
        }
        FixField encryptMethodField = getFieldsRegistry().find(CoreFields.ENCRYPT_METHOD);
        if (encryptMethodField == null) {
            return false;
        }
        // absent reads as the supported value: EncryptMethod(98) is required on a Logon, and enforcing its presence is
        // the business of the required fields validation rather than of this check
        int incomingEncryptMethod = logonMessage.getInt(encryptMethodField, SUPPORTED_ENCRYPT_METHOD);
        if (incomingEncryptMethod == SUPPORTED_ENCRYPT_METHOD) {
            return false;
        }
        String rejectText = "Invalid or unsupported EncryptMethod(98)=" + incomingEncryptMethod
                + ", this session only supports " + SUPPORTED_ENCRYPT_METHOD + ", no FIX level encryption";
        fixSession.logEvent(REJECTING_LOGON, rejectText);
        MessageReject reject = MessageReject.builder()
                .message(rejectText)
                .refTagId(CoreFields.ENCRYPT_METHOD)
                .sessionRejectReasonCode(SessionRejectReasonCodes.DECRYPTION_PROBLEM)
                .build();
        fixSession.onMessageRejects(getMessageType(), this, getFixSessionMessagesStore().getIncomingSeqNum(), List.of(reject));
        fixSession.sendLogoutRequest(rejectText, true);
        return true;
    }

    private boolean rejectIfMsgSeqNumTooLow(FixSessionImpl fixSession, WrongSeqNumException wrongSeqNumException) {
        if (wrongSeqNumException == null
                || wrongSeqNumException.getMsgSeqNum() >= wrongSeqNumException.getExpectedMsgSeqNum()
                || wrongSeqNumException.isPossDup()
                || Boolean.TRUE.equals(getFixSessionSettings().getResetSeqNumOnLogon())) {
            return false;
        }
        String logoutText = String.format("MsgSeqNum too low, expecting %s but received %s",
                wrongSeqNumException.getExpectedMsgSeqNum(), wrongSeqNumException.getMsgSeqNum());
        fixSession.logEvent(REJECTING_LOGON, logoutText);
        fixSession.sendLogoutRequest(logoutText, true);
        return true;
    }

    private void resetSequence(FixSessionImpl fixSession, String message) {
        fixSession.logEvent("Resetting sequence: %s", message);
        getFixSessionMessagesStore().resetSequenceNumbers();
    }
}