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
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.DecodingException;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.codec.decoders.DecodedFixMessageDecoder;
import org.lolaf.staffix.impl.session.FixSessionImpl;
import org.lolaf.staffix.impl.session.FixSessionStateComponent;
import org.lolaf.staffix.impl.session.FixSessionLayerComponents;
import org.lolaf.staffix.impl.session.CodecsComponent;
import org.lolaf.staffix.impl.session.OutgoingMessagesComponent;
import org.lolaf.staffix.impl.session.LogonLogoutComponent;
import org.lolaf.staffix.impl.session.MessageRejectsComponent;
import org.lolaf.staffix.impl.session.ResendRecovery;
import org.lolaf.staffix.impl.session.RetransmissionComponent;

import java.util.concurrent.Executor;

/**
 * The base of the session-layer decoders.
 *
 * <p>An admin decoder answers a decoding failure by ending the connection, where an application decoder answers
 * with a reject - the session layer cannot negotiate about a message it could not read.
 */
@Slf4j
@Getter
public abstract class AbstractAdminFixMessageDecoder extends DecodedFixMessageDecoder {

    private final FixAdminMessagesCodec fixAdminMessagesCodec;
    private final MessageType messageType;
    private final FieldsRegistry fieldsRegistry;
    private final FixApplication fixApplication;
    private final FixSessionImpl fixSession;
    private final FixSessionStateComponent fixSessionStateComponent;
    private final FixSessionLayerComponents fixSessionLayerComponents;
    private final MessageTypeRegistry messageTypeRegistry;
    private final FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore;
    private final FixSessionSettings fixSessionSettings;
    private final Clock clock;
    private final Executor executor;

    protected AbstractAdminFixMessageDecoder(MessageType messageType, AdminMessageCodecContext adminMessageCodecContext, FixAdminMessagesCodec fixAdminMessagesCodec) {
        super(messageType);
        this.messageType = messageType;
        this.fieldsRegistry = adminMessageCodecContext.getFieldsRegistry();
        this.fixAdminMessagesCodec = fixAdminMessagesCodec;
        this.fixApplication = adminMessageCodecContext.getFixApplication();
        this.fixSession = adminMessageCodecContext.getFixSession();
        this.fixSessionStateComponent = adminMessageCodecContext.getFixSessionStateComponent();
        this.fixSessionLayerComponents = adminMessageCodecContext.getFixSessionLayerComponents();
        this.messageTypeRegistry = adminMessageCodecContext.getMessageTypeRegistry();
        this.fixSessionMessagesStore = adminMessageCodecContext.getFixSessionMessagesStore();
        this.fixSessionSettings = adminMessageCodecContext.getFixSessionSettings();
        this.clock = adminMessageCodecContext.getClock();
        this.executor = adminMessageCodecContext.getExecutor();
    }

    /**
     * The Logon(35=A) and Logout(35=5) exchanges, which several admin messages either answer or break off.
     */
    protected LogonLogoutComponent getLogonLogoutComponent() {
        return fixSessionLayerComponents.get(LogonLogoutComponent.class);
    }

    /**
     * What this session is missing and has asked for, which a Logon(35=A) or a ResendRequest(35=2) either opens or
     * answers.
     */
    protected RetransmissionComponent getRetransmission() {
        return fixSessionLayerComponents.get(RetransmissionComponent.class);
    }

    /**
     * The decoders this session was set up with, whose message types a Logon(35=A) advertises to the peer.
     */
    protected CodecsComponent getCodecs() {
        return fixSessionLayerComponents.get(CodecsComponent.class);
    }

    /**
     * The sending pipeline, which a retransmission writes into directly: a replayed message carries the MsgSeqNum(34)
     * it was stored under rather than the next one.
     */
    protected OutgoingMessagesComponent getOutgoingMessages() {
        return fixSessionLayerComponents.get(OutgoingMessagesComponent.class);
    }

    /**
     * Where a message the decoder refuses goes: the rejects are its answer to the peer, and what they cost the
     * session is decided in one place rather than by each decoder.
     */
    protected MessageRejectsComponent getMessageRejects() {
        return fixSessionLayerComponents.get(MessageRejectsComponent.class);
    }

    /**
     * The gap recovery this session is in the middle of, if any: what the peer still owes it, and what arrived on top
     * of that meanwhile. Several admin messages settle part of it - a SequenceReset(35=4) above all - so they reach
     * for it often enough to be worth naming here.
     */
    protected ResendRecovery getResendRecovery() {
        return fixSessionLayerComponents.get(RetransmissionComponent.class).getResendRecovery();
    }

    @Override
    public final void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
        onDecodedLocal(fixSession, possDupFlag, possResend);
        super.onDecoded(fixSession, possDupFlag, possResend);
    }

    abstract void onDecodedLocal(FixSession fixSession, boolean possDupFlag, boolean possResend);

    @Override
    public final void onDecodingFailed(FixSession fixSession, DecodingException decodingException) {
        onDecodingFailedLocal(fixSession, decodingException);
        super.onDecodingFailed(fixSession, decodingException);
    }

    void onDecodingFailedLocal(FixSession fixSession, DecodingException decodingException) {
        if (!decodingException.shouldTriggerDisconnect(true)) {
            // e.g. validation errors or a short buffer: do not disconnect in such case
            return;
        }
        // in case of logout received it can happen with a wrong seq num from other side..
        FixSessionImpl fixSessionImpl = getFixSession();
        log.error("Failed to decode admin message type {} on session {}, disconnecting", getMessageType().code(), fixSession.getFixSessionId(), decodingException);
        fixSessionImpl.logEvent("Failed to decode admin message type %s with error: %s, abnormal situation disconnecting", getMessageType().code(), decodingException.getMessage());
        fixSessionImpl.processTask(fixSessionImpl::disconnect);

    }
}