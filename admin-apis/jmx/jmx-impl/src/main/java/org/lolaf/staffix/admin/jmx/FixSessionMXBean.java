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

import java.util.List;
import java.util.Map;

/**
 * Per-session JMX management surface. One bean is exposed for each session managed by the engine's initiators and
 * acceptors; the session is identified by the bean's {@code ObjectName} (its {@code group}, {@code session} and
 * {@code role} properties), so the operations take no session-identifying parameters.
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

    /**
     * The session's probes, in the order they were added.
     */
    List<Probe> getProbes();

    /**
     * Where each probe stood at its last evaluation; a probe not evaluated yet is absent.
     */
    List<ProbeStatus> getProbeStates();

    /**
     * Asks the probe's evaluator to end its ongoing alert, which then clears at the next evaluation tick.
     *
     * @return false when the probe is not in alert, or its evaluator refuses, as a polled condition does
     */
    boolean clearAlert(String probeName);

    /**
     * Intervals are ISO-8601 durations, the reminder absent for a probe that publishes transitions only, and the
     * runbook absent for a probe without one.
     */
    interface Probe {

        String getName();

        String getDescription();

        String getEvaluationInterval();

        String getReminderInterval();

        Map<String, String> getLabels();

        String getRunbookUrl();

        boolean isOnlyWhileLoggedIn();
    }

    /**
     * Level, message and since are absent while the probe is normal; times are ISO-8601 instants.
     */
    interface ProbeStatus {

        String getProbe();

        String getLevel();

        String getMessage();

        String getSince();

        String getLastEvaluation();

        /**
         * Not evaluated while the session is not logged in; the last evaluation then predates the logout.
         */
        boolean isSuspended();
    }
}
