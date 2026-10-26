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

import org.lolaf.staffix.api.ha.HaSettings;
import org.lolaf.staffix.api.ha.LeaderElectionSettings;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.spring.boot.spi.HaSettingsContributor;

import java.net.InetSocketAddress;
import java.util.function.Consumer;

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
        ReplicationHaSettings.ReplicationHaSettingsBuilder<?, ?> b = ReplicationHaSettings.builder()
                .leaderElection(leaderElection)
                .nodeId(props.getNodeId())
                .monitoringInstanceId(props.getMonitoringInstanceId())
                .alertsPublisherInstanceId(props.getAlertsPublisherInstanceId());
        props.getPeers().forEach((nodeId, address) -> b.peer(nodeId, address(nodeId, address)));
        if (props.getLogCapacity() != null) b.logCapacity(props.getLogCapacity());
        if (props.getReconnectInterval() != null) b.reconnectInterval(props.getReconnectInterval());
        if (props.getHeartbeatInterval() != null) b.heartbeatInterval(props.getHeartbeatInterval());
        if (props.getSnapshotMaxMessages() != null) b.snapshotMaxMessages(props.getSnapshotMaxMessages());
        if (props.getPromotionOutgoingSeqNumJump() != null) b.promotionOutgoingSeqNumJump(props.getPromotionOutgoingSeqNumJump());
        if (props.getHandOverTimeout() != null) b.handOverTimeout(props.getHandOverTimeout());
        if (props.getSwitchoverTimeout() != null) b.switchoverTimeout(props.getSwitchoverTimeout());
        sink.accept(b.build());
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
