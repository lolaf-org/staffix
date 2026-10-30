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

import lombok.Data;
import org.lolaf.staffix.api.metadata.ConfiguresComponent;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Properties for replicating the sessions' stores to a standby engine.
 *
 * <p>Bound from {@code staffix.ha-replication.*}. The leader election is the application context's
 * {@code LeaderElectionSettings} bean, such as the one {@code staffix-ha-primos-spring-boot} declares.
 */
@ConfiguresComponent(ReplicationHaSettings.class)
@Data
@ConfigurationProperties(prefix = "staffix.ha-replication")
public class ReplicationHaProps {

    /**
     * Names this engine to its peer in the replication handshake, and in their logs. With primos, its node id.
     */
    private String nodeId;

    /**
     * Where each node accepts replication while it leads, as host:port by node id, the same on every node.
     */
    private Map<String, String> peers = new LinkedHashMap<>();

    /**
     * The instance id of the monitoring publishing the replication's meters, such as the one staffix.monitoring sets up;
     * none when unset.
     */
    private String monitoringInstanceId;

    /**
     * The instance id of the alerts publisher publishing the replication's probes; none when unset.
     */
    private String alertsPublisherInstanceId;

    /**
     * Bytes of off-heap memory holding the store events not yet sent to the follower, a power of two; unset keeps
     * 16 MiB.
     */
    private Integer logCapacity;

    /**
     * How long a follower waits between two attempts to connect to its leader; unset keeps 1s.
     */
    private Duration reconnectInterval;

    /**
     * How often a leader with nothing to send tells its follower where the log ends; unset keeps 1s.
     */
    private Duration heartbeatInterval;

    /**
     * The leader's pooled write buffers per follower, a power of two, one kept spare; unset keeps 4.
     */
    private Integer sendBuffers;

    /**
     * The most recent messages of a session a snapshot copies to a follower catching up; unset keeps 10000.
     */
    private Integer snapshotMaxMessages;

    /**
     * How far a node that becomes leader moves the outgoing MsgSeqNum(34) of every replicated session; unset keeps
     * 10000.
     */
    private Long promotionOutgoingSeqNumJump;

    /**
     * How long a leader stopping cleanly waits to send its follower every event; unset keeps 5s.
     */
    private Duration handOverTimeout;

    /**
     * How long a switchover may take before this node leads its sessions again; unset keeps 30s.
     */
    private Duration switchoverTimeout;

    /**
     * How long a connected follower may go without applying a write before the leader alerts; unset keeps 5s.
     */
    private Duration followerLagAlertThreshold;

    /**
     * Whether an operator may, through staffix, hand the leadership over or take this node out of elections (the
     * switchover and maintenance mode) when the election is one the application shares, such as the primos starter's
     * node with staffix-ha-primos-spring-boot. Off by default, since that leadership is the application's too.
     */
    private boolean leadershipControl;

    /**
     * How much sooner than any successor's election this node stops leading when the election is primos: the time to
     * drop the sessions, plus clock drift. Unset keeps 50ms; less than primos's minimum election timeout.
     */
    private Duration fencingMargin;
}
