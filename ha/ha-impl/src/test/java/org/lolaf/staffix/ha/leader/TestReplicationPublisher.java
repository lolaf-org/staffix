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
package org.lolaf.staffix.ha.leader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lolaf.betty.api.Client;
import org.lolaf.betty.api.ClientBuilder;
import org.lolaf.betty.api.io.IOEventsListener;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.betty.api.settings.IOWorkersGroupSettings;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.ha.ReplicationFixEngineHa;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.FrameType;
import org.lolaf.staffix.ha.wire.RecordingHandler;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

/**
 * The publisher over a real connection, its follower played by a betty client that sends a HELLO and records what
 * comes back.
 */
class TestReplicationPublisher {

    private static final long EPOCH = 7;

    private final ReplicationFixEngineHa ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1 << 16).build());
    private final InetSocketAddress address = new InetSocketAddress("localhost", freePort());
    private final ReplicationPublisher publisher = new ReplicationPublisher(address, ha.getLog(), ha.getSessions(), ha.getFollowers(), () -> EPOCH,
            "leader-a", Duration.ofMillis(100));
    private final RecordingHandler received = new RecordingHandler();
    private Client follower;

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private FixSessionMessagesStore replicated() {
        return ha.replicatingStore(FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of("main", FixRegularVersion.VERSION_44, "SENDER", "TARGET"))
                .fixSessionType(FixSessionType.ACCEPTOR)
                .haSessionSettings(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.ASYNC).build())
                .build(), mock(FixSessionMessagesStore.class));
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
                        ByteBuffer hello = session.borrow(FrameEncoder.bytesWith(FrameType.HELLO, "follower-b")).clear();
                        int length = FrameEncoder.hello(hello, 0, EPOCH, "follower-b", lastAppliedEpoch, lastAppliedOffset);
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
        publisher.stop(Deadline.of(Duration.ofSeconds(2)));
    }

    @Test
    void aResumingFollowerIsStreamedTheEventsAppendedAfterItsLastOne() {
        FixSessionMessagesStore store = replicated();
        store.storeNextIncomingSeqNum(1);
        publisher.start();

        connectFollower(EPOCH, 0);
        await().until(() -> !ha.getFollowers().all().isEmpty()
                && ha.getFollowers().all().get(0).getState() == FollowerLink.State.STREAMING);
        store.storeNextIncomingSeqNum(2);
        store.storeNextOutgoingSeqNum(9);

        await().untilAsserted(() -> assertThat(received())
                .filteredOn(frame -> !frame.startsWith("HEARTBEAT"))
                .containsExactly(
                        "WELCOME epoch=7 version=1 leader=leader-a resume=48",
                        "SESSION epoch=7 key=0 ACCEPTOR ASYNC id=default.main:VERSION_44:SENDER->TARGET identity=null",
                        "STORE_EVENT epoch=7 offset=48 key=0 STORE_INCOMING_SEQ_NUM seq=2 message=",
                        "STORE_EVENT epoch=7 offset=96 key=0 STORE_OUTGOING_SEQ_NUM seq=9 message="));
    }

    @Test
    void anIdleLeaderSendsHeartbeatsWithTheLogTail() {
        replicated().storeNextIncomingSeqNum(1);
        publisher.start();

        connectFollower(0, -1);

        await().untilAsserted(() -> assertThat(received()).contains("HEARTBEAT epoch=7 tail=48"));
    }

    @Test
    void aFollowerThatDisconnectsIsForgotten() {
        publisher.start();
        connectFollower(0, -1);
        await().until(() -> ha.getFollowers().all().size() == 1);

        follower.stop(Deadline.of(Duration.ofSeconds(2)));

        await().until(() -> ha.getFollowers().all().isEmpty());
    }
}
