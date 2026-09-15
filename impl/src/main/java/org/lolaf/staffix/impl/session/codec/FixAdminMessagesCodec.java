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

import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixApiVersion;

import java.util.Map;
import java.util.Set;

/**
 * Builds the session-layer messages - Logon, Logout, Heartbeat, Reject, ResendRequest, SequenceReset.
 *
 * <p>One implementation per FIX version, because the admin messages are the part of the protocol that changed
 * most between them: fields appear, and from FIX.5.0 they move into FIXT.1.1 entirely.
 */
public interface FixAdminMessagesCodec {

    FixMessageEncoder generateLogin(int heartbeatInterval, Boolean resetNumFlag, FixSessionSettings fixSessionSettings, FixApiVersion applicationFixApiVersion,
                                    Long nextExpectedSeqNum, Set<MessageType> incomingMessageTypes, Set<MessageType> outgoingMessageTypes);

    FixMessageEncoder generateLogout(String logoutMessage);

    FixMessageEncoder generateHeartbeat(String testRequestId);

    FixMessageEncoder generateBusinessReject(String rejectText, int businessRejectReason, long refSeqNum, String businessRejectRefId, MessageType refMsgType);

    FixMessageEncoder generateReject(String rejectText, int sessionRejectReasonCode, long refSeqNum, int refTagId, MessageType refMsgType);

    FixMessageEncoder generateSequenceReset(long newSequenceNumber, boolean gapFill);

    FixMessageEncoder generateTestRequest(String testRequestId);

    FixMessageEncoder generateResendRequest(long fromSeqNum, long toSeqNum);

    FixMessageDecoder getDecoderForAdminMessage(MessageType messageType);

    boolean isAdminMessage(MessageType messageType);

    Map<MessageType, FixMessageDecoder> getAdminMessageDecoders();

}