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
package org.lolaf.staffix.ha;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.ha.FixEngineHa.EngineAccess;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.ha.LeaderElection;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.ha.follower.ReplicationClient;
import org.lolaf.staffix.ha.leader.FollowerConnection;
import org.lolaf.staffix.ha.wire.SessionAnswer;

import java.util.ArrayList;
import java.util.List;

/**
 * The engine's high availability status and its sessions', built from its live state on the caller's thread.
 */
@RequiredArgsConstructor
final class HaStatusReport {

    private final ReplicationFixEngineHa ha;

    HaStatus status() {
        ReplicationHaSettings settings = ha.getSettings();
        LeaderElection election = ha.getElection();
        Role role = ha.getRole();
        HaStatus.HaStatusBuilder status = HaStatus.builder()
                .nodeId(settings.getNodeId())
                .pairName(pairName(settings, election))
                .role(role)
                .epoch(ha.getRoleEpoch())
                .leaderId(election == null ? null : election.getLeaderId().orElse(null))
                .leadershipValid(election != null && election.getRole() == Role.LEADER && election.isLeadershipValid())
                .leaderElectionType(election == null ? "" : election.getClass().getSimpleName())
                .roleSince(ha.getRoleSince())
                .transferLeadershipSupported(election != null && election.isTransferLeadershipSupported())
                .candidacySupported(election != null && election.isCandidacySupported())
                .candidate(election == null || election.isCandidate())
                .switchoverMaxDuration(settings.switchoverMaxDuration())
                .monitoringInstanceId(settings.getMonitoringInstanceId());
        if (role == Role.LEADER) {
            int sessionCount = ha.getSessions().count();
            for (FollowerConnection connection : ha.getFollowers().connections()) {
                status.followerConnection(connection.getStatus(sessionCount));
            }
            status.droppedFollowers(ha.getStats().getSlowFollowers().notBackInSync());
        }
        ReplicationClient client = ha.getClient();
        if (role == Role.FOLLOWER && client != null) {
            status.leader(client.getStatus());
        }
        if (role == Role.FOLLOWER) {
            status.sessionSettingsDifferences(ha.getReplicaSessions().getSettingsComparison().getDifferences());
        }
        return status.build();
    }

    List<HaSessionStatus> sessionStatuses() {
        EngineAccess engine = ha.getEngine();
        if (engine == null) {
            return List.of();
        }
        Role role = ha.getRole();
        List<HaSessionStatus> statuses = new ArrayList<>();
        engine.forEachFixSessionSettings(sessionSettings -> {
            if (!ReplicationMode.NONE.equals(sessionSettings.getHighAvailability().getReplicationMode())) {
                statuses.add(sessionStatus(sessionSettings, role));
            }
        });
        return statuses;
    }

    private static @Nullable String pairName(ReplicationHaSettings settings, @Nullable LeaderElection election) {
        if (settings.getPairName() != null || election == null) {
            return settings.getPairName();
        }
        return election.getPairName().orElse(null);
    }

    private HaSessionStatus sessionStatus(FixSessionSettings sessionSettings, Role role) {
        HaSessionSettings haSettings = sessionSettings.getHighAvailability();
        HaSessionStatus.HaSessionStatusBuilder status = HaSessionStatus.builder()
                .fixSessionId(sessionSettings.getFixSessionId())
                .fixSessionType(sessionSettings.getFixSessionType())
                .replicationMode(haSettings.getReplicationMode())
                .replicationAckTimeout(haSettings.getReplicationAckTimeout())
                .nextIncomingSeqNum(-1)
                .nextOutgoingSeqNum(-1);
        if (Role.LEADER.equals(role)) {
            describeLed(sessionSettings, status);
        } else if (Role.FOLLOWER.equals(role)) {
            ha.getReplicaSessions().describe(sessionSettings.getFixSessionType(), sessionSettings.getFixSessionId().toString(),
                    status);
        }
        return status.build();
    }

    private void describeLed(FixSessionSettings sessionSettings, HaSessionStatus.HaSessionStatusBuilder status) {
        ReplicatedSession session = ha.getSessions().find(sessionSettings.getFixSessionId(), sessionSettings.getFixSessionType());
        if (session == null) {
            return;
        }
        boolean inSync = false;
        String refusal = null;
        for (FollowerConnection connection : ha.getFollowers().connections()) {
            SessionAnswer answer = connection.getSessionAnswer(session.getKey());
            inSync |= connection.isSessionInSync(session.getKey());
            if (answer != null && answer.isRefusal()) {
                refusal = answer.name();
            }
        }
        status.inSync(inSync)
                .notReplicatedReason(inSync ? null : refusal)
                .lastSendUnacknowledged(session.isLastSendUnacknowledged())
                .unacknowledgedSends(session.getUnacknowledgedSends())
                .lostWrites(session.getConfig().getStore().getLostWrites());
    }
}
