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
package org.lolaf.staffix.api.ha;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;

import java.time.Duration;

/**
 * A replicated session, as this node sees it: on a leader whether a follower holds it, on a follower what it holds.
 */
@NullMarked
@Value
@Builder
public class HaSessionStatus {

    FixSessionId fixSessionId;
    FixSessionType fixSessionType;
    ReplicationMode replicationMode;
    Duration replicationAckTimeout;
    /**
     * On a leader, a follower holds it in sync; on a follower, it holds it in full.
     */
    boolean inSync;
    /**
     * Its storage is the same on both nodes, as a shared database, so nothing of it needs replicating.
     */
    boolean sharedStorage;
    /**
     * Why it is not replicated, such as its storage being unidentified on the follower; null when it is.
     */
    @Nullable String notReplicatedReason;
    /**
     * A leader's {@code SYNC} session whose last send went out without the follower's ack.
     */
    boolean unprotected;
    /**
     * The leader's {@code SYNC} sends that went out without the follower's ack, on a timeout or with no follower in
     * sync.
     */
    long unacknowledgedSends;
    /**
     * As replicated to this follower, -1 on a leader or when it holds none.
     */
    long nextIncomingSeqNum;
    /**
     * As replicated to this follower, -1 on a leader or when it holds none.
     */
    long nextOutgoingSeqNum;
    /**
     * The old leader stopped cleanly with this follower holding the session in full: taking it over keeps its
     * outgoing seqnum rather than moving it ahead.
     */
    boolean handedOver;
}
