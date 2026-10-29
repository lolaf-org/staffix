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
        HaStatus.Leader leader = status.getLeader();
        return new StatusImpl(status.getNodeId(), status.getRole().name(), status.getEpoch(), status.getLeaderId(),
                status.isLeadershipValid(), status.getLeaderElection(),
                status.getRoleSince() == null ? null : status.getRoleSince().toString(),
                status.isTransferLeadershipSupported(), status.isCandidacySupported(), status.isCandidate(),
                status.getFollowers().stream()
                        .map(follower -> new FollowerImpl(follower.getNodeId(), follower.getAddress(), follower.isConnected(),
                                follower.isInSync(), follower.isSnapshotInProgress(), follower.getUnsentBytes(),
                                follower.getLagMillis()))
                        .collect(Collectors.toList()),
                leader == null ? null : new LeaderImpl(leader.getLeaderId(), leader.getAddress(), leader.isConnected(), leader.isInSync()));
    }

    @Override
    public List<Session> getSessionsStatus() {
        return delegate.getSessionsStatus().stream().map(FixHaMBeanImpl::session).collect(Collectors.toList());
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
        HaSessionStatus session = delegate.getSessionsStatus().stream()
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

    private static Session session(HaSessionStatus status) {
        return new SessionImpl(status.getFixSessionId().toString(), status.getFixSessionType().name(),
                status.getReplicationMode().name(), status.getReplicationAckTimeout().toString(), status.isInSync(),
                status.isSharedStorage(), status.getNotReplicatedReason(), status.isUnprotected(),
                status.getUnacknowledgedSends(), status.getNextIncomingSeqNum(), status.getNextOutgoingSeqNum(),
                status.isHandedOver());
    }

    @Value
    private static class StatusImpl implements Status {
        String nodeId;
        String role;
        long epoch;
        String leaderId;
        boolean leadershipValid;
        String leaderElection;
        String roleSince;
        boolean transferLeadershipSupported;
        boolean candidacySupported;
        boolean candidate;
        List<Follower> followers;
        Leader leader;
    }

    @Value
    private static class FollowerImpl implements Follower {
        String nodeId;
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
        boolean sharedStorage;
        String notReplicatedReason;
        boolean unprotected;
        long unacknowledgedSends;
        long nextIncomingSeqNum;
        long nextOutgoingSeqNum;
        boolean handedOver;
    }
}
