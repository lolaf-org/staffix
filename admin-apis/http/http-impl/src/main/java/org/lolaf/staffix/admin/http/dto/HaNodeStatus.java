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
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.ha.LeaderElection.Role;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The engine's high availability, as {@link HaStatus}.
 */
@Value
public class HaNodeStatus {
    String nodeId;
    /**
     * Absent when the pair is unnamed.
     */
    String pairName;
    Role role;
    long epoch;
    String leaderId;
    boolean leadershipValid;
    String leaderElectionType;
    /**
     * Absent before the engine took a role.
     */
    Instant roleSince;
    boolean transferLeadershipSupported;
    boolean candidacySupported;
    boolean candidate;
    /**
     * Absent without high availability.
     */
    Duration switchoverMaxDuration;
    /**
     * Absent when the node publishes no meters of its own.
     */
    String monitoringInstanceId;
    List<HaStatus.FollowerConnection> followerConnections;
    List<HaStatus.DroppedFollower> droppedFollowers;
    List<HaStatus.SessionSettingsDifference> sessionSettingsDifferences;
    HaStatus.Leader leader;

    public static HaNodeStatus of(HaStatus status) {
        return new HaNodeStatus(status.getNodeId(), status.getPairName(), status.getRole(), status.getEpoch(), status.getLeaderId(),
                status.isLeadershipValid(), status.getLeaderElectionType(),
                status.getRoleSince(), status.isTransferLeadershipSupported(), status.isCandidacySupported(),
                status.isCandidate(), status.getSwitchoverMaxDuration(), status.getMonitoringInstanceId(), status.getFollowerConnections(), status.getDroppedFollowers(), status.getSessionSettingsDifferences(), status.getLeader());
    }
}
