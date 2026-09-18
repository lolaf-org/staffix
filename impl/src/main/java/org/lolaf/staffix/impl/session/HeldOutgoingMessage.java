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
import org.lolaf.staffix.api.time.UTCTime;

/**
 * An application message handed over while a retransmission this session asked for was under way, kept whole -
 * encoder included, so that no MsgSeqNum(34) is spent on it - until the recovery is over.
 * <p>
 * Held in {@link FixSessionImplState}, which keeps them in the order the application sent them, and handed back to
 * {@link FixSessionImpl#releaseHeldOutgoingMessages()} when the session is synchronized again. Only ever allocated on
 * that path: a session that never loses a message never builds one.
 */
@RequiredArgsConstructor
class HeldOutgoingMessage {

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
    static <P1, P2> HeldOutgoingMessage of(FixMessageEncoder<?> encoder, UTCTime sendingTime,
                                           FixSession.MessageSendOperationCallback<P1, P2> messageSendOperationCallback,
                                           P1 param1, P2 param2) {
        return new HeldOutgoingMessage(encoder, sendingTime,
                (FixSession.MessageSendOperationCallback<Object, Object>) messageSendOperationCallback, param1, param2);
    }

    void send(FixSessionImpl fixSession) {
        fixSession.sendWithoutHolding(encoder, sendingTime, messageSendOperationCallback, param1, param2);
    }
}
