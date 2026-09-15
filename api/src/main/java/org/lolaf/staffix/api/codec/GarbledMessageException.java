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
 * A message breaching the framing rules of the FIX session layer, as defined by section 4.5.2 "Garbled message
 * processing" of the FIX Session Layer specification. A message is garbled when:
 * <ul>
 *     <li>BeginString(8) is not the first tag of the message or is not one of the defined FIX session profile
 *     identifiers ("8=FIX.4.4", "8=FIXT.1.1", ...),</li>
 *     <li>BodyLength(9) is not the second tag of the message or does not contain the correct byte count,</li>
 *     <li>MsgType(35) is not the third tag of the message,</li>
 *     <li>Checksum(10) is not the last tag or contains an incorrect value.</li>
 * </ul>
 * Such a message is presumed to result from a transmission error rather than from a defect of the peer, and to be
 * recoverable from it: it must be disregarded without answering it and without incrementing NextNumIn, and the
 * session must keep accepting messages. The peer keeps incrementing its own MsgSeqNum(34), so the next valid message
 * surfaces as a sequence gap which then drives the usual message recovery.
 * <p>
 * Note that a garbled message must not be considered inbound activity: it does not reset the heartbeat
 * interval timer.
 */
@Getter
public class GarbledMessageException extends DecodingException {

    private final String reason;

    public GarbledMessageException(String reason) {
        super("Garbled message received, ignoring it: " + reason);
        this.reason = reason;
    }

    @Override
    public boolean shouldTriggerDisconnect(boolean adminMessage) {
        // a garbled message is recoverable from the peer: ignore it and keep the session alive
        return false;
    }
}
