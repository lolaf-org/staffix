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
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.impl.session.FixSessionImpl;
import org.lolaf.staffix.impl.session.FixSessionImplState;

/**
 * Decodes a Logout(35=5), the orderly end of a session.
 *
 * <p>A Logout that answers one already sent completes the exchange; an unsolicited one has to be answered
 * before the connection closes.
 */
@Setter
public class LogoutFixMessageDecoder extends AbstractAdminFixMessageDecoder {

    private String logoutMessage;

    public LogoutFixMessageDecoder(MessageType messageType, AdminMessageCodecContext adminMessageCodecContext, FixAdminMessagesCodec fixAdminMessagesCodec) {
        super(messageType, adminMessageCodecContext, fixAdminMessagesCodec);
    }

    @Override
    public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
        fixFieldsDecoderMapper.mapStringField(getFieldsRegistry().find(CoreFields.TEXT), this::setLogoutMessage, null);
        super.mapFieldsForDecoding(fixFieldsDecoderMapper, fieldsRegistry);
    }

    @Override
    public void onDecodedLocal(FixSession fixSession, boolean possDupFlag, boolean possResend) {
        FixSessionImplState fixSessionImplState = getFixSessionImplState();
        boolean isLogoutInitiatedRemotely = fixSessionImplState.isLogoutInitiatedRemotely();
        fixSessionImplState.onLogoutReceived();
        FixSessionImpl fixSessionImpl = getFixSession();
        String message = logoutMessage != null && !logoutMessage.isEmpty() ? logoutMessage : fixSessionImplState.getSentLogoutMessage();
        getFixApplication().onLogout(fixSession, message, getDecodedFixMessage());
        // whoever asked for the logout is the one that closes the connection, and either way the logout is only
        // finished with once it is gone - FixSessionImpl.onDisconnection() completes it for both branches
        if (isLogoutInitiatedRemotely) {
            fixSessionImpl.send(getFixAdminMessagesCodec().generateLogout(null), null);
            // the counterparty asked for it, so this side acknowledges and waits for it to close, forcing the
            // disconnection only if it never does (FIX Session Testcases scenario 13 case B step 2). Dropping the
            // socket here instead would close it under an acknowledgement the peer may not have read.
            fixSessionImpl.awaitCounterpartyDisconnectAfterAcknowledgedLogout();
        } else {
            // the Logout just received acknowledges the one this session sent: the handshake is over and the side
            // that initiated it closes, otherwise the socket is left open with a session that is logged out on both
            // ends (FIX Session Testcases scenario 12 step 3, and scenario 13 case A)
            fixSessionImpl.processTask(fixSessionImpl::disconnect);
        }
    }
}