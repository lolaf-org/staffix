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
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.codec.SessionRejectReasonCodes;
import org.lolaf.staffix.codec.decoders.MessageReject;
import org.lolaf.staffix.impl.session.codec.FixAdminMessagesCodec;

import java.util.List;

/**
 * Section 3.6: what this session sends back when it will not accept a message, and the two reasons it stops talking
 * altogether rather than answering.
 *
 * <p>A Reject(35=3) for a session level fault and a BusinessMessageReject(35=j) for an application one, which is
 * the only thing the choice between them turns on.
 */
@RequiredArgsConstructor
public class MessageRejectsComponent implements FixSessionLayerComponent {

    private final FixSessionImpl fixSession;
    private final FixAdminMessagesCodec fixAdminMessagesCodec;
    private final FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore;
    private final Clock clock;

    public void onMessageRejects(MessageType messageType, long incomingSeqNum, List<MessageReject> rejects) {
        rejects.forEach(reject -> onMessageReject(messageType, reject, incomingSeqNum));
    }

    void sendBusinessMessageReject(String rejectText, int businessRejectReason, String businessRejectRefId, MessageType refMsgType) {
        fixSession.send(fixAdminMessagesCodec.generateBusinessReject(
                rejectText, businessRejectReason, fixSessionMessagesStore.getIncomingSeqNum(), businessRejectRefId, refMsgType), null);
    }

    private void onMessageReject(MessageType messageType, MessageReject messageReject, long refSeqNum) {
        boolean isAdminMessage = messageType.isAdmin();
        if (messageReject.getBusinessRejectReasonCode() != null && !isAdminMessage) {
            fixSession.send(fixAdminMessagesCodec.generateBusinessReject(messageReject.getMessage(),
                    messageReject.getBusinessRejectReasonCode().getCode(), refSeqNum,
                    String.valueOf(messageReject.getRefTagId()), messageType), clock.now());
        } else {
            fixSession.send(fixAdminMessagesCodec.generateReject(messageReject.getMessage(),
                    messageReject.getSessionRejectReasonCode().getCode(), refSeqNum,
                    messageReject.getRefTagId(), messageType), clock.now());
        }

        if (messageReject.getSessionRejectReasonCode().equals(SessionRejectReasonCodes.SENDING_TIME_ACCURACY_PROBLEM)) {
            fixSession.logout("Logout due to sending accuracy problem");
        } else if (messageReject.getSessionRejectReasonCode().equals(SessionRejectReasonCodes.COMPID_PROBLEM)
                && (messageReject.getRefTagId() == CoreFields.SENDER_COMP_ID
                || messageReject.getRefTagId() == CoreFields.TARGET_COMP_ID)) {
            // only the session's own CompIDs are worth dropping the connection over: a wrong SenderCompID(49) or
            // TargetCompID(56) means the peer is not the one this session is for. OnBehalfOfCompID(115) and
            // DeliverToCompID(128) share the CompID problem reject reason but are a routing error inside an otherwise
            // valid session, so the message is rejected and the session carries on.
            fixSession.logout("Logout due to incorrect received TARGET_COMP_ID or SENDER_COMP_ID");
        }
    }
}
