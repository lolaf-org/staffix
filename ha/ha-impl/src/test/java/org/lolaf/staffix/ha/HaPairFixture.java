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
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.FixEngineHa.ReplicatingStore;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.ha.follower.RecordingEngine;
import org.lolaf.staffix.ha.follower.RecordingProbes;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.awaitility.Awaitility.await;

/**
 * Two nodes, each an engine's high availability with an election the test decides, replicating over TCP, the follower
 * writing on its network threads or on a store writer thread.
 */
abstract class HaPairFixture {

    static final FixSessionSettings MAIN = RecordingEngine.session("main", FixSessionType.ACCEPTOR);
    static final FixSessionSettings OTHER = RecordingEngine.session("other", FixSessionType.ACCEPTOR);
    final InetSocketAddress addressA = new InetSocketAddress("localhost", freePort());
    final InetSocketAddress addressB = new InetSocketAddress("localhost", freePort());
    final List<ReplicationFixEngineHa> started = new ArrayList<>();
    /**
     * One per lane, so a stalled lane holds back no other.
     */
    final boolean storeWriterThreads;
    Node a;
    Node b;

    HaPairFixture(boolean storeWriterThreads) {
        this.storeWriterThreads = storeWriterThreads;
        a = new Node("a");
        b = new Node("b");
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean listening(InetSocketAddress address) {
        try (Socket socket = new Socket(address.getHostString(), address.getPort())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    final class Node {

        final FakeLeaderElection election = new FakeLeaderElection();
        final RecordingEngine engine;
        final ReplicationFixEngineHa ha;
        final RecordingProbes sessionProbes = new RecordingProbes();
        final ReplicatingStore store;
        ReplicatingStore other;

        Node(String nodeId) {
            this(nodeId, new RecordingEngine().with(MAIN));
        }

        Node(String nodeId, RecordingEngine engine) {
            this(nodeId, engine, Duration.ofMillis(50));
        }

        Node(String nodeId, RecordingEngine engine, Duration heartbeatInterval) {
            this(nodeId, engine, heartbeatInterval, 1);
        }

        Node(String nodeId, RecordingEngine engine, Duration heartbeatInterval, int replicationLanes) {
            this(nodeId, engine, heartbeatInterval, replicationLanes, Duration.ofSeconds(2));
        }

        Node(String nodeId, RecordingEngine engine, Duration heartbeatInterval, int replicationLanes, Duration switchoverTimeout) {
            this.engine = engine;
            ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder()
                    .nodeId(nodeId)
                    .peer("a", addressA)
                    .peer("b", addressB)
                    .logCapacity(1 << 16)
                    .replicationLanes(replicationLanes)
                    .followerStoreWriterThreads(storeWriterThreads ? replicationLanes : 0)
                    .heartbeatInterval(heartbeatInterval)
                    .reconnectInterval(Duration.ofMillis(50))
                    .switchoverTimeout(switchoverTimeout)
                    .followerLagAlertThreshold(Duration.ofMillis(200))
                    .leaderElection(FakeLeaderElection.Settings.builder().election(election).build())
                    .monitoringInstanceId("monitoring")
                    .alertsPublisherInstanceId("alerts")
                    .build());
            ha.bind(engine);
            store = ha.replicatingStore(MAIN, new RecordingEngine.RecordingStore().start(), sessionProbes);
        }

        /**
         * Registers {@link #OTHER} after {@link #MAIN}, so with two lanes each has a lane of its own.
         */
        Node withOther() {
            other = ha.replicatingStore(OTHER, new RecordingEngine.RecordingStore().start(), new RecordingProbes());
            return this;
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
    void aLeadsBFollows() {
        a.election.elect(Role.LEADER, 3, "a");
        b.election.elect(Role.FOLLOWER, 3, "a");
        a.start();
        b.start();
        await().until(() -> a.engine.activity.contains("activate, stores open 0"));
        await().until(() -> b.engine.store(MAIN) != null && b.ha.getReplicaSessions().isInSync());
    }

    Node notWaitingToSwitchOver(String nodeId) {
        return new Node(nodeId, new RecordingEngine().with(MAIN), Duration.ofMillis(50), 1, Duration.ZERO);
    }
}
