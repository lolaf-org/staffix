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

/**
 * The engine's high availability, registered only for an engine that has one. Mirrors
 * {@link org.lolaf.staffix.api.ha.HaAdmin} with enums, instants and durations as text.
 */
public interface FixHaMXBean {

    Status getStatus();

    List<Session> getSessionsStatus();

    /**
     * See {@link org.lolaf.staffix.api.ha.HaStatus}; leader is null unless this node follows one.
     */
    interface Status {

        String getNodeId();

        String getRole();

        long getEpoch();

        String getLeaderId();

        boolean isLeadershipValid();

        String getLeaderElection();

        String getRoleSince();

        List<Follower> getFollowers();

        Leader getLeader();
    }

    interface Follower {

        String getNodeId();

        String getAddress();

        boolean isConnected();

        boolean isInSync();

        boolean isSnapshotInProgress();

        long getUnsentBytes();
    }

    interface Leader {

        String getLeaderId();

        String getAddress();

        boolean isConnected();

        boolean isInSync();
    }

    /**
     * See {@link org.lolaf.staffix.api.ha.HaSessionStatus}; the session in its {@code toString()} form.
     */
    interface Session {

        String getFixSessionId();

        String getFixSessionType();

        String getReplicationMode();

        String getReplicationAckTimeout();

        boolean isInSync();

        boolean isSharedStorage();

        String getNotReplicatedReason();

        boolean isUnprotected();

        long getUnacknowledgedSends();

        long getNextIncomingSeqNum();

        long getNextOutgoingSeqNum();

        boolean isHandedOver();
    }
}
