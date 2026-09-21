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
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Section 4.3.11: application messages wait while a retransmission this session asked for is under way, so that both
 * ends are synchronized before anything new goes out. Session level messages are never held, the recovery being made
 * of them.
 *
 * <p>The one part of a session an application thread still touches directly, which is why the list and the flag
 * are guarded: {@code send} is documented as callable from any thread, and the recovery that ends the holding runs
 * on the IO thread.
 */
@RequiredArgsConstructor
class HeldOutgoingMessagesComponent implements FixSessionLayerComponent {

    private final FixSessionImpl fixSession;
    private final FixSessionSettings fixSessionSettings;
    private final Object heldOutgoingMessagesLock = new Object();
    private final List<HeldOutgoingMessage> heldOutgoingMessages = new ArrayList<>();
    private volatile boolean holdingOutgoingMessages;
    private OutgoingMessagesComponent outgoingMessages;

    @Override
    public void onSessionStarted(FixSessionLayerComponents components) {
        outgoingMessages = components.get(OutgoingMessagesComponent.class);
    }

    /**
     * @return whether the message was taken over, the caller then having nothing left to send
     */
    <P1, P2> boolean holdIfRecovering(FixMessageEncoder<?> encoder, UTCTime sendingTime,
                                      FixSession.MessageSendOperationCallback<P1, P2> messageSendOperationCallback,
                                      P1 param1, P2 param2) {
        if (!holdingOutgoingMessages || encoder.getMessageType().isAdmin()) {
            return false;
        }
        synchronized (heldOutgoingMessagesLock) {
            // checked again under the lock: the release may have finished since send() last looked
            if (!holdingOutgoingMessages) {
                return false;
            }
            if (heldOutgoingMessages.size() < fixSessionSettings.getMaxOutgoingMessagesHeldDuringRecovery()) {
                heldOutgoingMessages.add(HeldOutgoingMessage.of(encoder, sendingTime, messageSendOperationCallback, param1, param2));
                return true;
            }
            fixSession.logEvent("Refusing to send %s, already holding %s application messages while recovering",
                    encoder.getMessageType(), heldOutgoingMessages.size());
        }
        // the same lifecycle a sent message's encoder gets from FixSessionFixMessageContext.release(), which
        // this one never reaches
        if (!encoder.isReusable()) {
            encoder.destroy();
        }
        encoder.release();
        outgoingMessages.callOnMessageCallbackIfNeeded(new IOException("Too many application messages held back while the session recovers missing messages"),
                messageSendOperationCallback, param1, param2);
        return true;
    }

    void startHoldingIfNeeded() {
        synchronized (heldOutgoingMessagesLock) {
            holdingOutgoingMessages = fixSessionSettings.getMaxOutgoingMessagesHeldDuringRecovery() > 0;
        }
    }

    /**
     * Holding stops only once nothing is left held: an application thread sending while this drains is queued behind
     * what is already held, so the messages go out in the order they were handed over and none is left stranded.
     */
    void releaseAll() {
        while (true) {
            List<HeldOutgoingMessage> released;
            synchronized (heldOutgoingMessagesLock) {
                if (heldOutgoingMessages.isEmpty()) {
                    holdingOutgoingMessages = false;
                    return;
                }
                released = new ArrayList<>(heldOutgoingMessages);
                heldOutgoingMessages.clear();
            }
            fixSession.logEvent("Sending the %s application messages held back while recovering", released.size());
            released.forEach(held -> held.send(outgoingMessages));
        }
    }

    /**
     * A logout ends the holding as surely as a completed recovery does: the messages are sent while disconnected,
     * which stores them, so they reach the peer on the next connection.
     */
    @Override
    public void onLogoutProcessed(boolean cleanLogout) {
        releaseAll();
    }

    /**
     * An application message handed over while a retransmission this session asked for was under way, kept whole -
     * encoder included, so that no MsgSeqNum(34) is spent on it - until the recovery is over.
     * <p>
     * Held by {@link HeldOutgoingMessagesComponent} in the order the application sent them, and handed back when the
     * session is synchronized again. Only ever allocated on that path: a session that never loses a message never builds
     * one.
     */
    @RequiredArgsConstructor
    private static class HeldOutgoingMessage {

        private final FixMessageEncoder<?> encoder;
        private final UTCTime sendingTime;
        private final FixSession.MessageSendOperationCallback<Object, Object> messageSendOperationCallback;
        private final Object param1;
        private final Object param2;

        /**
         * The callback and its parameters are kept without their types, the way {@link FixSessionFixMessageContext} keeps
         * them: what is held here only ever gets handed straight back to {@link #send}, which pairs them up again.
         */
        @SuppressWarnings("unchecked")
        private static <P1, P2> HeldOutgoingMessage of(FixMessageEncoder<?> encoder, UTCTime sendingTime,
                                                       FixSession.MessageSendOperationCallback<P1, P2> messageSendOperationCallback,
                                                       P1 param1, P2 param2) {
            return new HeldOutgoingMessage(encoder, sendingTime,
                    (FixSession.MessageSendOperationCallback<Object, Object>) messageSendOperationCallback, param1, param2);
        }

        private void send(OutgoingMessagesComponent outgoingMessages) {
            outgoingMessages.send(encoder, sendingTime, messageSendOperationCallback, param1, param2);
        }
    }
}
