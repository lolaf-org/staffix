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
package org.lolaf.staffix.ha.spring;

import org.lolaf.betty.api.ss.IdleStrategySelectStrategy;
import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.idling.BusySpinIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.api.ha.HaSettings;
import org.lolaf.staffix.api.ha.LeaderElectionSettings;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.spring.boot.spi.HaSettingsContributor;

import java.net.InetSocketAddress;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Contributes store replication to the engine being built, elected by the application context's leader election.
 */
public class ReplicationHaContributor implements HaSettingsContributor {

    private final ReplicationHaProps props;
    private final LeaderElectionSettings leaderElection;

    public ReplicationHaContributor(ReplicationHaProps props, LeaderElectionSettings leaderElection) {
        this.props = props;
        this.leaderElection = leaderElection;
    }

    @Override
    public void contribute(Consumer<HaSettings> sink) {
        if (props.getNodeId() == null || props.getNodeId().isBlank()) {
            throw new IllegalStateException("staffix.ha-replication.node-id is required: it names this node among its peers");
        }
        ReplicationHaSettings.ReplicationHaSettingsBuilder<?, ?> settings = ReplicationHaSettings.builder()
                .leaderElection(leaderElection)
                .nodeId(props.getNodeId())
                .pairName(props.getPairName())
                .monitoringInstanceId(props.getMonitoringInstanceId())
                .alertsPublisherInstanceId(props.getAlertsPublisherInstanceId());
        props.getPeers().forEach((nodeId, address) -> settings.peer(nodeId, address(nodeId, address)));
        ifSet(props.getLogCapacity(), settings::logCapacity);
        ifSet(props.getReplicationLanes(), settings::replicationLanes);
        ifSet(props.getReconnectInterval(), settings::reconnectInterval);
        ifSet(props.getHeartbeatInterval(), settings::heartbeatInterval);
        ifSet(props.getSendBuffers(), settings::sendBuffers);
        ifSet(props.getSnapshotChunkBytes(), settings::snapshotChunkBytes);
        ifBusySpin(props.getSenderIdleStrategy(), settings::senderIdleStrategy);
        ifBusySpin(props.getSyncAckIdleStrategy(), settings::syncAckIdleStrategy);
        ifSet(props.getFollowerStoreWriterThreads(), settings::followerStoreWriterThreads);
        ifBusySpin(props.getFollowerStoreWriterIdleStrategy(), settings::followerStoreWriterIdleStrategy);
        if (props.getSelectStrategy() == ReplicationHaProps.Waiting.BUSY_SPIN) {
            settings.selectStrategy(() -> new IdleStrategySelectStrategy(BusySpinIdleStrategy::getInstance));
        }
        ifSet(props.getPromotionOutgoingSeqNumJump(), settings::promotionOutgoingSeqNumJump);
        ifSet(props.getHandOverTimeout(), settings::handOverTimeout);
        ifSet(props.getSwitchoverTimeout(), settings::switchoverTimeout);
        ifSet(props.getSwitchoverMaxPendingWrites(), settings::switchoverMaxPendingWrites);
        ifSet(props.getFollowerLagAlertThreshold(), settings::followerLagAlertThreshold);
        ifSet(props.getFollowerStallTimeout(), settings::followerStallTimeout);
        ifSet(props.getLogFullTimeout(), settings::logFullTimeout);
        ifSet(props.getSlowFollowerHoldOff(), settings::slowFollowerHoldOff);
        sink.accept(settings.build());
    }

    private static <T> void ifSet(@Nullable T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private static void ifBusySpin(ReplicationHaProps.@Nullable Waiting waiting, Consumer<Supplier<IdleStrategy>> setter) {
        if (waiting == ReplicationHaProps.Waiting.BUSY_SPIN) {
            setter.accept(BusySpinIdleStrategy::getInstance);
        }
    }

    private static InetSocketAddress address(String nodeId, String hostAndPort) {
        int colon = hostAndPort.lastIndexOf(':');
        try {
            if (colon <= 0) {
                throw new NumberFormatException();
            }
            return new InetSocketAddress(hostAndPort.substring(0, colon),
                    Integer.parseInt(hostAndPort.substring(colon + 1)));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("staffix.ha-replication.peers." + nodeId + "='" + hostAndPort
                    + "' is not host:port", e);
        }
    }
}
