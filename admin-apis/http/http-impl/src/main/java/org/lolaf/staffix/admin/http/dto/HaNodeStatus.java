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

import java.util.List;

/**
 * The engine's high availability, as {@link HaStatus} with its instant as text.
 */
@Value
public class HaNodeStatus {
    String nodeId;
    Role role;
    long epoch;
    String leaderId;
    boolean leadershipValid;
    String leaderElection;
    /**
     * ISO-8601, absent before the engine took a role.
     */
    String roleSince;
    List<HaStatus.Follower> followers;
    HaStatus.Leader leader;

    public static HaNodeStatus of(HaStatus status) {
        return new HaNodeStatus(status.getNodeId(), status.getRole(), status.getEpoch(), status.getLeaderId(),
                status.isLeadershipValid(), status.getLeaderElection(),
                status.getRoleSince() == null ? null : status.getRoleSince().toString(), status.getFollowers(), status.getLeader());
    }
}
