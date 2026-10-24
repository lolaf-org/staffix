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
package org.lolaf.staffix.ha.primos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lolaf.primos.ClusterConfig;
import org.lolaf.primos.Peer;
import org.lolaf.primos.PrimosNode;
import org.lolaf.primos.store.MemoryStateStore;
import org.lolaf.primos.transport.UdpTransport;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.LeaderElection;
import org.lolaf.staffix.api.ha.LeaderElection.Role;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * A three node primos cluster on localhost, each node run by staffix.
 */
class TestPrimosLeaderElection {

    private static final int NODES = 3;

    @TempDir
    Path state;

    private final int[] ports = new int[NODES + 1];
    private final List<LeaderElection> elections = new ArrayList<>();

    TestPrimosLeaderElection() {
        for (int id = 1; id <= NODES; id++) {
            ports[id] = freeUdpPort();
        }
    }

    private static int freeUdpPort() {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private ClusterConfig cluster(int nodeId) {
        ClusterConfig.ClusterConfigBuilder builder = ClusterConfig.builder().applicationName("staffix-test").nodeId(nodeId);
        for (int id = 1; id <= NODES; id++) {
            if (id != nodeId) {
                builder.peer(Peer.application(id, "localhost", ports[id]));
            }
        }
        return builder.build();
    }

    private PrimosLeaderElectionSettings.PrimosLeaderElectionSettingsBuilder<?, ?> owned(int nodeId) {
        return PrimosLeaderElectionSettings.builder()
                .cluster(cluster(nodeId))
                .stateDirectory(state.resolve("node-" + nodeId))
                .bindAddress(new InetSocketAddress("localhost", ports[nodeId]))
                .clusterSecret("secret".getBytes(StandardCharsets.US_ASCII));
    }

    private List<LeaderElection> startCluster() {
        for (int id = 1; id <= NODES; id++) {
            LeaderElection election = owned(id).build().instance();
            election.start();
            elections.add(election);
        }
        return elections;
    }

    private static Optional<LeaderElection> leader(List<LeaderElection> elections) {
        List<LeaderElection> leaders = elections.stream().filter(e -> e.getRole() == Role.LEADER).collect(Collectors.toList());
        return leaders.size() == 1 ? Optional.of(leaders.get(0)) : Optional.empty();
    }

    @AfterEach
    void stop() {
        elections.forEach(election -> election.stop(Deadline.of(Duration.ofSeconds(5))));
    }

    @Test
    void oneNodeLeadsAndTheOthersFollowItByItsNodeIdAtItsEpoch() {
        List<LeaderElection> cluster = startCluster();

        await().until(() -> leader(cluster).isPresent()
                && cluster.stream().filter(e -> e.getRole() == Role.FOLLOWER).count() == NODES - 1);
        LeaderElection leader = leader(cluster).orElseThrow();
        String leaderId = leader.getLeaderId().orElseThrow();
        await().until(() -> cluster.stream().allMatch(e -> e.getLeaderId().equals(Optional.of(leaderId))
                && e.getEpoch() == leader.getEpoch()));
        await().until(leader::isLeadershipValid);
        assertThat(cluster.stream().filter(e -> e != leader)).noneMatch(LeaderElection::isLeadershipValid);
    }

    @Test
    void aLeaderStoppingHandsOverToAnotherAtAHigherEpoch() {
        List<LeaderElection> cluster = startCluster();
        await().until(() -> leader(cluster).isPresent());
        LeaderElection first = leader(cluster).orElseThrow();
        long firstEpoch = first.getEpoch();
        List<String> changes = new CopyOnWriteArrayList<>();
        cluster.forEach(e -> e.addListener((previous, current, epoch) -> changes.add(previous + "->" + current)));

        first.stop(Deadline.of(Duration.ofSeconds(5)));
        List<LeaderElection> remaining = cluster.stream().filter(e -> e != first).collect(Collectors.toList());

        await().until(() -> leader(remaining).isPresent());
        assertThat(leader(remaining).orElseThrow().getEpoch()).isGreaterThan(firstEpoch);
        assertThat(changes).as("no leader known between the two").contains("FOLLOWER->UNKNOWN", "UNKNOWN->LEADER");
    }

    @Test
    void aNodeTheApplicationRunsIsNeitherStartedNorStoppedByStaffix() {
        PrimosNode node = PrimosNode.builder()
                .clusterConfig(cluster(1))
                .store(MemoryStateStore::new)
                .transport(UdpTransport.builder()
                        .bindAddress(new InetSocketAddress("localhost", ports[1]))
                        .peers(cluster(1).allPeers())
                        .clusterName("staffix-test"))
                .build();
        LeaderElection election = PrimosLeaderElectionSettings.builder().node(node).build().instance();

        election.start();
        assertThat(node.isRunning()).isFalse();
        node.start();
        election.stop(Deadline.of(Duration.ofSeconds(5)));
        assertThat(node.isRunning()).isTrue();
        node.stop();
    }

    @Test
    void exactlyOneOfANodeOrAClusterIsTaken() {
        assertThatThrownBy(() -> PrimosLeaderElectionSettings.builder().build().instance())
                .hasMessageContaining("exactly one of a node");
        assertThatThrownBy(() -> owned(1).stateDirectory(null).build().instance())
                .hasMessageContaining("needs a stateDirectory");
    }
}
