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
package org.lolaf.staffix.ha.replication.leader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lolaf.betty.api.Client;
import org.lolaf.betty.api.ClientBuilder;
import org.lolaf.betty.api.io.IOEventsListener;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.betty.api.settings.IOWorkersGroupSettings;
import org.lolaf.betty.api.ss.WakeupSelectStrategy;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.staffix.ha.replication.spi.ReplicationMetrics;
import org.lolaf.staffix.ha.replication.TestingPorts;
import org.lolaf.staffix.ha.replication.EncryptionMode;
import org.lolaf.staffix.ha.replication.testkit.TestingKeyExchanges;
import org.lolaf.staffix.ha.replication.journal.Journal;
import org.lolaf.staffix.ha.replication.journal.JournalStream;
import org.lolaf.staffix.ha.replication.wire.FrameDecoder;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.wire.FrameType;
import org.lolaf.staffix.ha.replication.testkit.RecordingHandler;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;
import org.lolaf.staffix.ha.replication.wire.TestingFrameEncoder;
import org.lolaf.staffix.ha.replication.spi.TestingReplicationClock;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The server over a real connection, its follower played by a betty client that sends a HELLO and records what
 * comes back.
 */
class TestReplicationServer {

    private static final long EPOCH = 7;

    private final Journal journal;
    private final InetSocketAddress address;
    private final ReplicationServer server;
    private final RecordingHandler received;
    private Client follower;

    TestReplicationServer() {
        this.journal = Journal.ofCapacity(1 << 16);
        this.address = new InetSocketAddress("localhost", TestingPorts.findFree());
        this.server = new ReplicationServer(address, journal.getLogs(), journal, journal.getFollowers(), () -> EPOCH,
                "leader-a", Duration.ofMillis(100), Duration.ZERO, 4, Integer.MAX_VALUE,
                BackoffIdleStrategy::new, new WakeupSelectStrategy(10), new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingReplicationClock()), ReplicationMetrics.NONE), new TestingReplicationClock(), TestingKeyExchanges.CLEAR);
        this.received = new RecordingHandler(Journal.APPLICATION_ID);
    }

    private JournalStream replicated() {
        return journal.add("main", StreamReplicationMode.ASYNC);
    }

    private void connectFollower(long lastAppliedEpoch, long lastAppliedOffset) {
        FrameDecoder decoder = new FrameDecoder(1 << 20);
        follower = ClientBuilder.builder()
                .id("test-follower")
                .connectAddress(address)
                .ioWorkersGroup(IOWorkersGroupSettings.builder().id("test-follower").build().newInstance())
                .ioSettings(IOSettings.builder().readBufferSize(1 << 20).build())
                .ioEventsListener(new IOEventsListener() {
                    @Override
                    public void onConnected(IOSession session) {
                        ByteBuffer hello = session.borrow(FrameEncoder.bytesWith(FrameType.HELLO, "follower-b"));
                        int length = TestingFrameEncoder.hello(hello, 0, EPOCH, "follower-b", lastAppliedEpoch, lastAppliedOffset, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
                        session.send(hello.position(length), true);
                    }

                    @Override
                    public void onRead(IOSession session, ByteBuffer message, long localReceiveTimeInNanos) {
                        int index = message.position();
                        int consumed;
                        synchronized (received) {
                            while ((consumed = decoder.decode(message, index, message.limit() - index, received)) > 0) {
                                index += consumed;
                            }
                        }
                        message.position(index);
                    }
                })
                .build().newInstance();
        follower.start();
    }

    private List<String> received() {
        synchronized (received) {
            return new ArrayList<>(received.frames);
        }
    }

    @AfterEach
    void stop() {
        if (follower != null) {
            follower.stop(Deadline.of(Duration.ofSeconds(2)));
        }
        server.stop(Deadline.of(Duration.ofSeconds(2)));
    }

    @Test
    void aResumingFollowerIsStreamedTheRecordsAppendedAfterItsLastOne() {
        JournalStream main = replicated();
        main.append("");
        server.start();

        connectFollower(EPOCH, 0);
        await().until(() -> !journal.getFollowers().connections().isEmpty()
                && journal.getFollowers().connections().get(0).getState() == FollowerConnection.State.STREAMING);
        main.append("");
        main.append("");

        await().untilAsserted(() -> assertThat(received())
                .filteredOn(frame -> !frame.startsWith("HEARTBEAT"))
                .containsExactly(
                        "WELCOME epoch=7 version=1 leader=leader-a resume=48 lanes=1",
                        "STREAM epoch=7 key=0 ASYNC name=main identity=leader-storage",
                        "STREAMS_ANNOUNCED epoch=7 count=1",
                        "RECORD epoch=7 offset=48 key=0 type=1 pos=2 payload=",
                        "RECORD epoch=7 offset=96 key=0 type=1 pos=3 payload="));
    }

    @Test
    void anIdleLeaderSendsHeartbeatsWithTheLogTail() {
        replicated().append("");
        server.start();

        connectFollower(0, -1);

        await().untilAsserted(() -> assertThat(received()).contains("HEARTBEAT epoch=7 tail=48"));
    }

    @Test
    void aConnectedFollowerIsReportedAtItsAddress() {
        server.start();
        connectFollower(0, -1);

        await().untilAsserted(() -> assertThat(journal.getFollowers().connections()).hasSize(1));

        assertThat(journal.getFollowers().connections().get(0).getStatus(0).getAddress()).matches(".*:\\d+");
    }

    @Test
    void aFollowerThatDisconnectsIsForgotten() {
        server.start();
        connectFollower(0, -1);
        await().until(() -> journal.getFollowers().connections().size() == 1);

        follower.stop(Deadline.of(Duration.ofSeconds(2)));

        await().until(() -> journal.getFollowers().connections().isEmpty());
    }
}
