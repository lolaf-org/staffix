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
package org.lolaf.staffix.admin.jmx;

/**
 * Per-session JMX management surface. One bean is exposed for each session managed by the engine's initiators and
 * acceptors; the session is identified by the bean's {@code ObjectName} (its {@code session} and {@code role}
 * properties), so the operations take no session-identifying parameters.
 */
public interface FixSessionMXBean {

    String getFixSessionId();

    void logon();

    void logout();

    void reset(String resetMode);

    /**
     * Sends a FIX message written as a string - fields separated by SOH or {@code |}, typically a line copied out of a
     * log. Only its body is sent: the session recomputes the header and the trailer, and refuses anything its
     * dictionary does not describe.
     */
    void sendFixMessage(String fixMessage);

    /**
     * As {@link #sendFixMessage(String)}, naming the character between the message's fields - {@code |} for a message
     * written by hand - and whether the message is the same one going out again, which sends it under
     * PossDupFlag(43)=Y with its SendingTime(52) carried into OrigSendingTime(122).
     */
    void sendFixMessage(String fixMessage, char separator, boolean possDupFlag);

    long getIncomingSeqNum();

    void setIncomingSeqNum(long seqNum);

    long getOutgoingSeqNum();

    void setOutgoingSeqNum(long seqNum);
}
