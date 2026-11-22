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

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.FixEngineHa.ReplicatingStore;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.ha.SwitchoverOutcome;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.ha.ReplicationHaSettings.ReplicationHaSettingsBuilder;
import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import org.lolaf.staffix.ha.replication.encryption.ReplicationEncryptionSettings;
import org.lolaf.staffix.ha.replication.encryption.TestingKeyExchanges;
import org.lolaf.staffix.ha.RecordingEngine.RecordingStore;
import org.lolaf.staffix.tests.TestPorts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Two engines replicating encrypted: through a failover, through the rollout from clear to required, refusing a wrong
 * secret or cipher, and what an encrypted lane shows on the network.
 */
@ParameterizedClass
@ValueSource(booleans = {false, true})
class TestEncryptedReplication extends HaPairFixture {

    private static final String CIPHER = "AES/CTR/NoPadding 256 HmacSHA256";
    private static final String ORDER = "8=FIX.4.4\u00019=5\u000135=D\u0001order-text\u000110=000\u0001";
    private static final String QUOTE = "8=FIX.4.4\u00019=5\u000135=S\u0001quote-text\u000110=000\u0001";
    private static final FixSessionSettings QUOTES = quotes();

    TestEncryptedReplication(boolean storeWriterThreads) {
        super(storeWriterThreads);
    }

    private static FixSessionSettings quotes() {
        FixSessionSettings settings = RecordingEngine.session("quotes", FixSessionType.ACCEPTOR);
        return settings.toBuilder().highAvailability(settings.getHighAvailability().toBuilder()
                .unencryptedMessageType("S").build()).build();
    }

    private static ReplicationEncryptionSettings encryption(EncryptionMode mode, String secret, String cipher) {
        return ReplicationEncryptionSettings.builder().mode(mode).secret(secret.getBytes(StandardCharsets.UTF_8))
                .cipher(cipher).build();
    }

    private Node encrypting(String nodeId, EncryptionMode mode) {
        return tuned(nodeId, settings -> settings.encryption(encryption(mode, TestingKeyExchanges.SECRET,
                "AES/CTR/NoPadding")));
    }

    private static void storeOrder(Node leader, long seq) {
        leader.store.storeMessageSent(MessageType.of("D", false), seq,
                ByteBuffer.wrap(ORDER.getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * Once the follower holds every session, so the write is streamed to it: a snapshot carries no message.
     */
    private static void replicates(Node leader, Node follower, long seq) {
        await().until(() -> follower.ha.getReplicaSessions().isInSync());
        await().until(() -> leader.ha.getStatus().getFollowerConnections().stream()
                .anyMatch(connection -> connection.isConnected() && connection.isInSync()));
        storeOrder(leader, seq);
        await().until(() -> follower.engine.store(MAIN) != null
                && follower.engine.store(MAIN).writes.contains("message " + seq + " " + ORDER));
    }

    private static @Nullable String cipherSeenByLeader(Node leader) {
        for (HaStatus.FollowerConnection connection : leader.ha.getStatus().getFollowerConnections()) {
            if (connection.isConnected()) {
                return connection.getCipher();
            }
        }
        return "not connected";
    }

    private Node restartedFollower(Node node, EncryptionMode mode, long epoch, String leaderId) {
        String nodeId = node.ha.getSettings().getNodeId();
        node.ha.stop(Deadline.of(Duration.ofSeconds(2)));
        started.remove(node.ha);
        Node restarted = encrypting(nodeId, mode);
        restarted.election.elect(Role.FOLLOWER, epoch, leaderId);
        return restarted.start();
    }

    private static void switchOver(Node from, Node to, long epoch) {
        String toId = to.ha.getSettings().getNodeId();
        from.election.transfersWith(() -> {
            from.election.elect(Role.FOLLOWER, epoch, toId);
            to.election.elect(Role.LEADER, epoch, toId);
        });
        assertThat(from.ha.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);
        await().until(() -> to.engine.activity.contains("activate, stores open 0"));
    }

    @Test
    void anEncryptedPairFailsOverAndReplicatesEncryptedTheOtherWay() {
        a = encrypting("a", EncryptionMode.REQUIRED);
        b = encrypting("b", EncryptionMode.REQUIRED);
        startPairInSync();
        replicates(a, b, 1);
        assertThat(cipherSeenByLeader(a)).isEqualTo(CIPHER);

        a.election.elect(Role.FOLLOWER, 4, "b");
        b.election.elect(Role.LEADER, 4, "b");

        await().until(() -> b.engine.activity.contains("activate, stores open 0"));
        assertThat(b.engine.store(MAIN).writes).contains("message 1 " + ORDER);
        replicates(b, a, 10_002);
        assertThat(cipherSeenByLeader(b)).isEqualTo(CIPHER);
        assertThat(a.ha.getStatus().getLeader().getCipher()).isEqualTo(CIPHER);
    }

    @Test
    void anEncryptedSwitchoverHandsTheSessionsOverWithoutMovingThemAhead() {
        a = encrypting("a", EncryptionMode.REQUIRED);
        b = encrypting("b", EncryptionMode.REQUIRED);
        startPairInSync();
        replicates(a, b, 1);
        a.store.storeNextIncomingSeqNum(7);

        switchOver(a, b, 4);

        assertThat(b.engine.store(MAIN).writes).as("the authenticated SHUTDOWN accepted").contains("incoming 7")
                .doesNotContain("outgoing 10001");
    }

    @Test
    void theRolloutFromClearToRequiredKeepsReplicatingThroughout() {
        startPairInSync();
        replicates(a, b, 1);

        b = restartedFollower(b, EncryptionMode.PREFERRED, 3, "a");
        replicates(a, b, 2);
        assertThat(cipherSeenByLeader(a)).as("b preferring, a disabled").isNull();
        switchOver(a, b, 4);
        replicates(b, a, 10_002);

        a = restartedFollower(a, EncryptionMode.PREFERRED, 4, "b");
        replicates(b, a, 10_003);
        assertThat(cipherSeenByLeader(b)).as("both preferring").isEqualTo(CIPHER);

        a = restartedFollower(a, EncryptionMode.REQUIRED, 4, "b");
        replicates(b, a, 10_004);
        switchOver(b, a, 5);
        replicates(a, b, 20_004);
        b = restartedFollower(b, EncryptionMode.REQUIRED, 5, "a");
        replicates(a, b, 20_005);
        assertThat(cipherSeenByLeader(a)).as("both requiring").isEqualTo(CIPHER);
    }

    @ParameterizedTest(name = "follower secret {0}, cipher {1}")
    @CsvSource({"another-secret-of-the-pair, AES/CTR/NoPadding", TestingKeyExchanges.SECRET + ", ChaCha20"})
    void aWrongSecretOrCipherNeverReplicates(String secret, String cipher) {
        a = encrypting("a", EncryptionMode.PREFERRED);
        b = tuned("b", settings -> settings.encryption(encryption(EncryptionMode.PREFERRED, secret, cipher)));
        a.election.elect(Role.LEADER, 3, "a");
        b.election.elect(Role.FOLLOWER, 3, "a");
        a.start();
        b.start();
        await().until(() -> a.engine.activity.contains("activate, stores open 0"));
        storeOrder(a, 1);

        await().during(Duration.ofMillis(500)).until(() -> a.ha.getStatus().getFollowerConnections().stream()
                .noneMatch(HaStatus.FollowerConnection::isConnected));
        assertThat(b.engine.store(MAIN)).as("never replicated").isNull();
    }

    @Test
    void anEncryptedLaneShowsNoMessageButTheTypesItsSessionLeavesUnencrypted() throws IOException {
        try (RecordingProxy proxy = new RecordingProxy(addressA)) {
            a = withQuotes("a", settings -> settings);
            b = withQuotes("b", settings -> settings.clearPeers().peer("a", proxy.address).peer("b", addressB));
            ReplicatingStore quotes = a.other;
            startPairInSync();

            replicates(a, b, 1);
            quotes.storeMessageSent(MessageType.of("S", false), 1, ByteBuffer.wrap(QUOTE.getBytes(StandardCharsets.US_ASCII)));
            await().until(() -> b.engine.store(QUOTES).writes.contains("message 1 " + QUOTE));

            String sent = proxy.fromTarget();
            assertThat(sent).as("the quote, left unencrypted").contains(QUOTE);
            assertThat(sent.replace(QUOTE, "")).doesNotContain("8=FIX", "order-text",
                    MAIN.getFixSessionId().toString(), QUOTES.getFixSessionId().toString());
        }
    }

    private Node withQuotes(String nodeId, UnaryOperator<ReplicationHaSettingsBuilder> peers) {
        Node node = new Node(nodeId, new RecordingEngine().with(MAIN).with(QUOTES), Duration.ofMillis(50), 1,
                settings -> peers.apply(settings.encryption(encryption(EncryptionMode.REQUIRED, TestingKeyExchanges.SECRET,
                        "AES/CTR/NoPadding"))));
        node.other = node.ha.replicatingStore(QUOTES, new RecordingStore().start(), new RecordingProbes());
        return node;
    }

    /**
     * Forwards every connection to the target, recording what the target sends back.
     */
    private static final class RecordingProxy implements AutoCloseable {

        final InetSocketAddress address;
        private final InetSocketAddress target;
        private final ServerSocket server;
        private final ByteArrayOutputStream fromTarget;
        private final List<Socket> sockets;

        RecordingProxy(InetSocketAddress target) throws IOException {
            this.address = new InetSocketAddress("localhost", TestPorts.findFree());
            this.target = target;
            this.server = new ServerSocket(address.getPort());
            this.fromTarget = new ByteArrayOutputStream();
            this.sockets = new CopyOnWriteArrayList<>();
            daemon(this::accept);
        }

        private static void daemon(Runnable task) {
            Thread thread = new Thread(task, "recording-proxy");
            thread.setDaemon(true);
            thread.start();
        }

        private void accept() {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    Socket upstream;
                    try {
                        upstream = new Socket(target.getHostString(), target.getPort());
                    } catch (IOException e) {
                        client.close();
                        continue;
                    }
                    sockets.add(client);
                    sockets.add(upstream);
                    daemon(() -> pump(client, upstream, null));
                    daemon(() -> pump(upstream, client, fromTarget));
                } catch (IOException e) {
                    return;
                }
            }
        }

        private static void pump(Socket from, Socket to, ByteArrayOutputStream recorded) {
            byte[] buffer = new byte[8192];
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (recorded != null) {
                        synchronized (recorded) {
                            recorded.write(buffer, 0, read);
                        }
                    }
                    out.write(buffer, 0, read);
                }
            } catch (IOException e) {
                // either side closed
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException e) {
                // closing
            }
        }

        String fromTarget() {
            synchronized (fromTarget) {
                return fromTarget.toString(StandardCharsets.ISO_8859_1);
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            sockets.forEach(RecordingProxy::closeQuietly);
        }
    }
}
