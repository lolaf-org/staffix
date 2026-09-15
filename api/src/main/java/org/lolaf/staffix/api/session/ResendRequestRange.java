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
package org.lolaf.staffix.api.session;

/**
 * How EndSeqNo(16) is spelled on a ResendRequest(35=2) this session sends, and the spelling itself: each constant
 * turns the end of the gap into the number that goes on the wire, so an engine asking for a retransmission has one
 * place to get that number from and no reason to know which form it is sending.
 * <p>
 * Section 4.8.2 of the FIX Session Layer specification lets a request name "a single message, a range of messages or
 * all messages", so both forms are correct and the choice is the counterparty's to drive: some peers answer only one
 * of them. This engine defaults to {@link #CLOSED}; QuickFIX/J's {@code ClosedResendInterval=N} - its default - is
 * {@link #OPEN_ENDED} here.
 * <p>
 * Only requests this session sends are concerned. What it accepts is not configurable: an incoming EndSeqNo(16) of 0
 * or 999999 is answered as open ended whatever this says, the peer having chosen that form.
 *
 * @see FixSessionSettings#getResendRequestRange()
 */
public enum ResendRequestRange {

    /**
     * EndSeqNo(16) names the last message of the gap, so the request asks for exactly what is missing and nothing
     * else. The peer's answer ends where the gap ends.
     */
    CLOSED {
        @Override
        public long endSeqNo(long endSeqNo) {
            return endSeqNo;
        }
    },
    /**
     * EndSeqNo(16) carries the "infinity" form, asking for everything from BeginSeqNo(7) onwards.
     * <p>
     * The peer may then retransmit past the end of the gap, since the message that revealed the gap - and anything
     * sent after it - is inside the range it was given. Those arrive as duplicates of messages already held and are
     * discarded on the spot, and the gap fill that ends the answer is tolerated rather than taken for an unsolicited
     * one; a session asking a closed range treats both as the protocol violations they would be. What it buys is
     * peers that answer an open ended request and nothing else.
     */
    OPEN_ENDED {
        @Override
        public long endSeqNo(long endSeqNo) {
            return 0;
        }
    };

    /**
     * The EndSeqNo(16) to send for a gap ending at {@code endSeqNo}.
     * <p>
     * The gap's real end is what the caller keeps for its own bookkeeping - a session asking {@link #OPEN_ENDED}
     * still closes its pending resend at the sequence number it was actually missing - so the value returned here
     * never leaves the encoding of the message.
     * <p>
     * {@link #OPEN_ENDED} answers 0, which is how FIX.4.2 and later spell infinity; FIX.4.1 and earlier spell it
     * 999999, and returning it would mean asking the session's FIX version, which every version this engine speaks
     * makes redundant - {@link org.lolaf.staffix.api.version.FixRegularVersion} starts at FIX.4.2, and FIXT.1.1
     * carrying FIX 5.0 is covered by the same 0.
     *
     * @param endSeqNo MsgSeqNum(34) of the last message the gap is missing
     * @return the value to put in EndSeqNo(16), which for an open ended range stands for "and everything after that"
     */
    public abstract long endSeqNo(long endSeqNo);
}
