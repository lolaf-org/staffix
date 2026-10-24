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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.FixEngineHa.ReplicatingStore;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.ha.follower.RecordingEngine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Two nodes, each an engine's high availability with an election the test decides, replicating over TCP.
 */
class TestReplicationFixEngineHa {

    private static final FixSessionSettings MAIN = RecordingEngine.session("main", FixSessionType.ACCEPTOR);

    private final InetSocketAddress addressA = new InetSocketAddress("localhost", freePort());
    private final InetSocketAddress addressB = new InetSocketAddress("localhost", freePort());
    private final List<ReplicationFixEngineHa> started = new ArrayList<>();
    private final Node a = new Node("a");
    private final Node b = new Node("b");

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean listening(InetSocketAddress address) {
        try (Socket socket = new Socket(address.getHostString(), address.getPort())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private final class Node {

        final FakeLeaderElection election = new FakeLeaderElection();
        final RecordingEngine engine = new RecordingEngine().with(MAIN);
        final ReplicationFixEngineHa ha;
        final ReplicatingStore store;

        Node(String nodeId) {
            ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder()
                    .nodeId(nodeId)
                    .peer("a", addressA)
                    .peer("b", addressB)
                    .logCapacity(1 << 16)
                    .heartbeatInterval(Duration.ofMillis(50))
                    .reconnectInterval(Duration.ofMillis(50))
                    .leaderElection(FakeLeaderElection.Settings.builder().election(election).build())
                    .build());
            ha.bind(engine);
            store = ha.replicatingStore(MAIN, new RecordingEngine.RecordingStore());
        }

        Node start() {
            ha.start();
            started.add(ha);
            return this;
        }
    }

    @AfterEach
    void stop() {
        started.forEach(ha -> ha.stop(Deadline.of(Duration.ofSeconds(2))));
    }

    /**
     * Until b is in sync, a write is not streamed to it: the snapshot carries the store's state instead.
     */
    private void aLeadsBFollows() {
        a.election.elect(Role.LEADER, 3, "a");
        b.election.elect(Role.FOLLOWER, 3, "a");
        a.start();
        b.start();
        await().until(() -> a.engine.activity.contains("activate, stores open 0"));
        await().until(() -> b.engine.store(MAIN) != null && b.ha.getReplica().isComplete());
    }

    @Test
    void aNodeWhoseRoleIsUnknownRunsNoSessionAndListensToNoFollower() {
        a.start();

        await().during(Duration.ofMillis(200)).until(() -> a.engine.activity.isEmpty());
        assertThat(listening(addressA)).isFalse();
    }

    @Test
    void theLeaderRunsItsSessionsAndItsFollowerReplicatesThemWithoutRunningAny() {
        aLeadsBFollows();

        a.store.storeNextIncomingSeqNum(7);

        await().untilAsserted(() -> assertThat(b.engine.store(MAIN).writes).endsWith("incoming 7"));
        assertThat(b.engine.activity).isEmpty();
    }

    @Test
    void promotionClosesTheReplicaBeforeRunningTheSessions() {
        aLeadsBFollows();
        a.store.storeNextIncomingSeqNum(7);
        await().until(() -> b.engine.store(MAIN) != null && b.engine.store(MAIN).writes.contains("incoming 7"));

        a.election.elect(Role.FOLLOWER, 4, "b");
        b.election.elect(Role.LEADER, 4, "b");

        await().until(() -> a.engine.activity.contains("deactivate"));
        await().until(() -> b.engine.activity.contains("activate, stores open 0"));
        await().until(() -> listening(addressB));
        await().until(() -> !listening(addressA));
    }

    @Test
    void aLeaderThatCannotProveItLeadsStopsItsSessions() {
        a.election.elect(Role.LEADER, 3, "a");
        a.start();
        await().until(() -> a.engine.activity.contains("activate, stores open 0"));

        a.election.leadershipValid(false);

        await().until(() -> a.engine.activity.contains("deactivate"));
        await().until(() -> !listening(addressA));
    }

    @Test
    void anEngineWithoutLeaderElectionIsRefused() {
        ReplicationFixEngineHa withoutElection = new ReplicationFixEngineHa(ReplicationHaSettings.builder()
                .nodeId("a").peer("a", addressA).build());
        withoutElection.bind(new RecordingEngine());

        assertThatThrownBy(withoutElection::start).hasMessageContaining("needs a leaderElection");
    }
}
