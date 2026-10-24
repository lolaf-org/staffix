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
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.FixEngineHa.EngineAccess;
import org.lolaf.staffix.api.ha.LeaderElection;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.ha.ReplicationFixEngineHa;
import org.lolaf.staffix.ha.ReplicationHaSettings;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;

/**
 * Two engines' high availability elected by primos, with a witness voting so either engine can fail.
 */
class TestReplicationOverPrimos {

    private static final int WITNESS = 3;

    @TempDir
    Path state;

    private final int[] electionPorts = {0, freeUdpPort(), freeUdpPort(), freeUdpPort()};
    private final int[] replicationPorts = {0, freeTcpPort(), freeTcpPort()};
    private final Engine[] engines = {null, new Engine(), new Engine()};
    private ReplicationFixEngineHa first;
    private ReplicationFixEngineHa second;
    private LeaderElection voter;

    private static int freeUdpPort() {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int freeTcpPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * An engine with no session, which records whether it runs them.
     */
    private static final class Engine implements EngineAccess {

        volatile boolean active;

        @Override
        public List<FixSessionSettings> getFixSessionsSettings() {
            return List.of();
        }

        @Override
        public FixSessionMessagesStore openStore(FixSessionSettings settings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void activate() {
            active = true;
        }

        @Override
        public void deactivate() {
            active = false;
        }
    }

    private PrimosLeaderElectionSettings election(int nodeId) {
        ClusterConfig.ClusterConfigBuilder cluster = ClusterConfig.builder().applicationName("staffix-test").nodeId(nodeId);
        for (int id = 1; id <= 2; id++) {
            if (id != nodeId) {
                cluster.peer(Peer.application(id, "localhost", electionPorts[id]));
            }
        }
        if (nodeId == WITNESS) {
            cluster.asWitness();
        } else {
            cluster.witness(Peer.witness(WITNESS, "localhost", electionPorts[WITNESS]));
        }
        return PrimosLeaderElectionSettings.builder()
                .cluster(cluster.build())
                .stateDirectory(state.resolve("node-" + nodeId))
                .bindAddress(new InetSocketAddress("localhost", electionPorts[nodeId]))
                .clusterSecret("secret".getBytes(StandardCharsets.US_ASCII))
                .build();
    }

    private ReplicationFixEngineHa ha(int nodeId) {
        ReplicationFixEngineHa ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder()
                .nodeId(String.valueOf(nodeId))
                .peer("1", new InetSocketAddress("localhost", replicationPorts[1]))
                .peer("2", new InetSocketAddress("localhost", replicationPorts[2]))
                .logCapacity(1 << 16)
                .heartbeatInterval(Duration.ofMillis(50))
                .reconnectInterval(Duration.ofMillis(50))
                .leaderElection(election(nodeId))
                .build());
        ha.bind(engines[nodeId]);
        ha.start();
        return ha;
    }

    @AfterEach
    void stop() {
        Deadline deadline = Deadline.of(Duration.ofSeconds(5));
        for (ReplicationFixEngineHa ha : new ReplicationFixEngineHa[]{first, second}) {
            if (ha != null && ha.isStarted()) {
                ha.stop(deadline);
            }
        }
        voter.stop(deadline);
    }

    @Test
    void exactlyOneEngineRunsItsSessionsAndTheOtherTakesOverWhenItStops() {
        voter = election(WITNESS).instance();
        voter.start();
        first = ha(1);
        second = ha(2);

        await().until(() -> engines[1].active ^ engines[2].active);
        int leading = engines[1].active ? 1 : 2;
        int standing = 3 - leading;
        ReplicationFixEngineHa leader = leading == 1 ? first : second;

        leader.stop(Deadline.of(Duration.ofSeconds(5)));

        await().atMost(Duration.ofSeconds(20)).until(() -> engines[standing].active);
    }
}
