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

import lombok.Value;
import org.lolaf.staffix.api.ha.HaAdmin;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.ha.HaStatus;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The engine's high availability, read from its {@link HaAdmin} at each call.
 */
public class FixHaMBeanImpl implements FixHaMXBean {

    private final HaAdmin delegate;

    FixHaMBeanImpl(HaAdmin delegate) {
        this.delegate = delegate;
    }

    @Override
    public Status getStatus() {
        HaStatus status = delegate.getStatus();
        return new StatusImpl(status.getNodeId(), status.getPairName(), status.getRole().name(), status.getEpoch(),
                status.getLeaderId(), status.isLeadershipValid(), status.getLeaderElectionType(),
                toStringOrNull(status.getSwitchoverMaxDuration()), toStringOrNull(status.getRoleSince()),
                status.getMonitoringInstanceId(), status.isTransferLeadershipSupported(), status.isCandidacySupported(),
                status.isCandidate(),
                status.getFollowerConnections().stream().map(FixHaMBeanImpl::followerConnection).collect(Collectors.toList()),
                status.getDroppedFollowers().stream().map(FixHaMBeanImpl::droppedFollower).collect(Collectors.toList()),
                status.getLeader() == null ? null : leader(status.getLeader()));
    }

    @Override
    public List<Session> getSessionStatuses() {
        return delegate.getSessionStatuses().stream().map(FixHaMBeanImpl::session).collect(Collectors.toList());
    }

    @Override
    public void updateCandidacy(boolean candidate) {
        delegate.setCandidate(candidate);
    }

    @Override
    public void resync() {
        delegate.resync();
    }

    /**
     * @throws IllegalArgumentException for a session not replicated
     */
    @Override
    public void resyncSession(String fixSessionId, String fixSessionType) {
        HaSessionStatus session = delegate.getSessionStatuses().stream()
                .filter(candidate -> candidate.getFixSessionId().toString().equals(fixSessionId)
                        && candidate.getFixSessionType().name().equals(fixSessionType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No replicated session " + fixSessionType + " " + fixSessionId));
        delegate.resync(session.getFixSessionId(), session.getFixSessionType());
    }

    @Override
    public String switchover() {
        return delegate.switchover().name();
    }

    private static FollowerConnection followerConnection(HaStatus.FollowerConnection connection) {
        return new FollowerConnectionImpl(connection.getNodeId(), connection.getLane(), connection.getAddress(),
                connection.isConnected(), connection.isInSync(), connection.isSnapshotInProgress(),
                connection.getUnsentBytes(), connection.getLagMillis());
    }

    private static DroppedFollower droppedFollower(HaStatus.DroppedFollower dropped) {
        return new DroppedFollowerImpl(dropped.getNodeId(), dropped.getLane(), dropped.getDroppedAt().toString(),
                toStringOrNull(dropped.getHeldOffUntil()));
    }

    private static Leader leader(HaStatus.Leader leader) {
        return new LeaderImpl(leader.getLeaderId(), leader.getAddress(), leader.isConnected(), leader.isInSync());
    }

    private static String toStringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static Session session(HaSessionStatus status) {
        return new SessionImpl(status.getFixSessionId().toString(), status.getFixSessionType().name(),
                status.getReplicationMode().name(), status.getReplicationAckTimeout().toString(), status.isInSync(),
                status.getNotReplicatedReason(), status.isLastSendUnacknowledged(),
                status.getUnacknowledgedSends(), status.getNextIncomingSeqNum(), status.getNextOutgoingSeqNum(),
                status.isHandedOver(), status.getPendingWrites(), status.getLostWrites());
    }

    @Value
    private static class StatusImpl implements Status {
        String nodeId;
        String pairName;
        String role;
        long epoch;
        String leaderId;
        boolean leadershipValid;
        String leaderElectionType;
        String switchoverMaxDuration;
        String roleSince;
        String monitoringInstanceId;
        boolean transferLeadershipSupported;
        boolean candidacySupported;
        boolean candidate;
        List<FollowerConnection> followerConnections;
        List<DroppedFollower> droppedFollowers;
        Leader leader;
    }

    @Value
    private static class DroppedFollowerImpl implements DroppedFollower {
        String nodeId;
        int lane;
        String droppedAt;
        String heldOffUntil;
    }

    @Value
    private static class FollowerConnectionImpl implements FollowerConnection {
        String nodeId;
        int lane;
        String address;
        boolean connected;
        boolean inSync;
        boolean snapshotInProgress;
        long unsentBytes;
        long lagMillis;
    }

    @Value
    private static class LeaderImpl implements Leader {
        String leaderId;
        String address;
        boolean connected;
        boolean inSync;
    }

    @Value
    private static class SessionImpl implements Session {
        String fixSessionId;
        String fixSessionType;
        String replicationMode;
        String replicationAckTimeout;
        boolean inSync;
        String notReplicatedReason;
        boolean lastSendUnacknowledged;
        long unacknowledgedSends;
        long nextIncomingSeqNum;
        long nextOutgoingSeqNum;
        boolean handedOver;
        long pendingWrites;
        long lostWrites;
    }
}
