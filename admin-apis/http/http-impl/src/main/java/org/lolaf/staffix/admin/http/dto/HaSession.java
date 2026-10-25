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
package org.lolaf.staffix.admin.http.dto;

import lombok.Value;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;

/**
 * A replicated session, as {@link HaSessionStatus} with its id split and its timeout as ISO-8601 text.
 */
@Value
public class HaSession {
    String group;
    String name;
    FixSessionType fixSessionType;
    ReplicationMode replicationMode;
    String replicationAckTimeout;
    boolean inSync;
    boolean sharedStorage;
    String notReplicatedReason;
    boolean unprotected;
    long unacknowledgedSends;
    long nextIncomingSeqNum;
    long nextOutgoingSeqNum;
    boolean handedOver;

    public static HaSession of(HaSessionStatus status) {
        return new HaSession(status.getFixSessionId().getGroup(), status.getFixSessionId().getName(),
                status.getFixSessionType(), status.getReplicationMode(), status.getReplicationAckTimeout().toString(),
                status.isInSync(), status.isSharedStorage(), status.getNotReplicatedReason(), status.isUnprotected(),
                status.getUnacknowledgedSends(), status.getNextIncomingSeqNum(), status.getNextOutgoingSeqNum(),
                status.isHandedOver());
    }
}
