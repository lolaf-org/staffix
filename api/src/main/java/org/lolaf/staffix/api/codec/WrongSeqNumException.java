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
package org.lolaf.staffix.api.codec;

import lombok.Getter;

/**
 * MsgSeqNum(34) is not the one expected.
 *
 * <p>Only one of the three cases is fatal, which is why this carries the direction and PossDupFlag rather than
 * just the numbers: a sequence number too high is a gap, answered with a ResendRequest; one too low with
 * PossDupFlag(43)=Y is a legitimate retransmission and is ignored; one too low without it is numbering neither
 * end agrees about, and the session ends. See section 4.8 of the FIX Session Layer specification.
 *
 * <p>The raw message is kept so the session layer can log what it refused.
 */
@Getter
public class WrongSeqNumException extends DecodingException {

    private final long msgSeqNum;
    private final long expectedMsgSeqNum;
    private final boolean possDup;
    /**
     * The message exactly as it arrived on the wire, from BeginString(8) to the end of CheckSum(10).
     * <p>
     * A message whose MsgSeqNum(34) is higher than expected has to be held on to rather than dropped - section 4.5 of
     * the FIX Session Layer specification, state table rows 11 and 12: "receive too high of MsgSeqNum(34) from
     * counterparty, <b>queue message</b>, and send ResendRequest(35=2)" - and processed once the gap ahead of it has
     * been filled. Keeping the raw bytes lets the session replay it through the parser then, as if it had just been
     * read off the socket, and validate it with the session's real settings.
     * <p>
     * Nothing short of the original bytes will do: rebuilding them from decoded fields would have to recompute
     * BodyLength(9) and CheckSum(10), and would then be validating a message we assembled rather than the one the
     * peer sent. Copied only here, on the out of sequence branch, so the parsing path pays nothing for it.
     */
    private final transient byte[] rawMessage;

    public WrongSeqNumException(long msgSeqNum, long expectedMsgSeqNum, boolean possDup, byte[] rawMessage) {
        super("Wrong sequence number received, expecting " + expectedMsgSeqNum + " but got " + msgSeqNum);
        this.msgSeqNum = msgSeqNum;
        this.expectedMsgSeqNum = expectedMsgSeqNum;
        this.possDup = possDup;
        this.rawMessage = rawMessage;
    }

    @Override
    public boolean shouldTriggerDisconnect(boolean adminMessage) {
        // A MsgSeqNum higher than expected is a recoverable gap: the session answers with a ResendRequest and
        // resynchronizes. A MsgSeqNum lower than expected with PossDupFlag(43)=Y is a legitimate retransmission that is
        // simply ignored. Only a too-low message without PossDupFlag is unrecoverable and tears down the session.
        return msgSeqNum < expectedMsgSeqNum && !possDup;
    }
}