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
package org.lolaf.staffix.ha.replication.follower;

import com.github.valfirst.slf4jtest.LoggingEvent;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lolaf.betty.api.ss.WakeupSelectStrategy;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.staffix.ha.replication.TestingPorts;
import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import org.lolaf.staffix.ha.replication.encryption.KeyExchange;
import org.lolaf.staffix.ha.replication.encryption.TestingKeyExchanges;
import org.lolaf.staffix.ha.replication.leader.FollowerConnection;
import org.lolaf.staffix.ha.replication.leader.ReplicationServer;
import org.lolaf.staffix.ha.replication.leader.ReplicationStats;
import org.lolaf.staffix.ha.replication.journal.Journal;
import org.lolaf.staffix.ha.replication.journal.JournalCopy;
import org.lolaf.staffix.ha.replication.journal.JournalReplica;
import org.lolaf.staffix.ha.replication.journal.JournalStream;
import org.lolaf.staffix.ha.replication.leader.SlowFollowers;
import org.lolaf.staffix.ha.replication.spi.TestingReplicationClock;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Every row of the encryption behaviour table over a real connection, with the two secret and cipher mismatches, and
 * what a connected pair replicates.
 */
class TestReplicationEncryptionHandshake {

    private static final long EPOCH = 7;
    private static final String ENTRY = "an order-text entry";
    private static final String MAIN = "main";

    private final Journal leader;
    private final JournalStream leaderMain;
    private final JournalCopy main;
    private final FollowerStreams followerStreams;
    private ReplicationServer server;
    private ReplicationClient client;
    private String leaderId;
    private String followerId;

    TestReplicationEncryptionHandshake() {
        this.leader = Journal.ofCapacity(1 << 16);
        this.leaderMain = leader.add(MAIN, StreamReplicationMode.ASYNC);
        JournalReplica replica = new JournalReplica();
        this.main = replica.configure(MAIN, StreamReplicationMode.ASYNC);
        this.followerStreams = new FollowerStreams(replica);
    }

    private static KeyExchange exchange(EncryptionMode mode, String secret, String cipher) {
        return mode == EncryptionMode.DISABLED ? TestingKeyExchanges.CLEAR : TestingKeyExchanges.of(mode, secret, cipher);
    }

    /**
     * The events of the case, by the ids only it uses: the classes of this module run concurrently.
     */
    private static List<LoggingEvent> events(Class<?> logger, String id, Level level) {
        return TestLoggerFactory.getTestLogger(logger).getAllLoggingEvents().stream()
                .filter(event -> event.getLevel() == level && event.getFormattedMessage().contains(id))
                .collect(Collectors.toList());
    }

    private InetSocketAddress connect(KeyExchange leaderExchange, KeyExchange followerExchange, int sinkWriterThreads) {
        leaderId = "leader-" + System.identityHashCode(this);
        followerId = "follower-" + System.identityHashCode(this);
        InetSocketAddress address = new InetSocketAddress("localhost", TestingPorts.findFree());
        server = new ReplicationServer(address, leader.getLogs(), leader, leader.getFollowers(),
                () -> EPOCH, leaderId, Duration.ofMillis(100), Duration.ofSeconds(5), 4, Integer.MAX_VALUE,
                BackoffIdleStrategy::new, new WakeupSelectStrategy(10),
                new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingReplicationClock())), new TestingReplicationClock(),
                leaderExchange);
        server.start();
        client = new ReplicationClient(address, followerStreams, () -> EPOCH, followerId,
                leader.getLogs().get(0).getMaxRecordFrameBytes(), Duration.ofMillis(50),
                new WakeupSelectStrategy(10), sinkWriterThreads, BackoffIdleStrategy::new, Duration.ofSeconds(1),
                followerExchange);
        client.start();
        return address;
    }

    /**
     * The stream in sync, then an entry and one longer than a cipher's chunk, both written on the follower.
     */
    private void awaitReplicated() {
        await().until(() -> leader.getFollowers().connections().stream().anyMatch(connection -> connection.isStreamInSync(0)));
        String longEntry = ENTRY + "x".repeat(20_000);
        leaderMain.append(ENTRY);
        leaderMain.append(longEntry);
        await().untilAsserted(() -> assertThat(main.getWrites()).contains("1 " + ENTRY, "2 " + longEntry));
    }

    @ParameterizedTest(name = "{0}, {1} sink writer threads")
    @CsvSource({"AES/GCM/NoPadding, 1", "AES/CTR/NoPadding, 1", "ChaCha20, 0", "ChaCha20-Poly1305, 1"})
    void anEncryptedPairReplicatesWhicheverThreadWritesTheFollowersSinks(String cipher, int sinkWriterThreads) {
        connect(exchange(EncryptionMode.REQUIRED, "pair-secret-0123", cipher),
                exchange(EncryptionMode.REQUIRED, "pair-secret-0123", cipher), sinkWriterThreads);

        awaitReplicated();

        assertThat(leader.getFollowers().connections().get(0).isEncrypted()).isTrue();
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.stop(Deadline.of(Duration.ofSeconds(2)));
        }
        if (server != null) {
            server.stop(Deadline.of(Duration.ofSeconds(2)));
        }
        followerStreams.close(Deadline.of(Duration.ofSeconds(2)));
    }

    @ParameterizedTest(name = "leader {0}, follower {1}, {6}")
    @CsvSource({
            "DISABLED,  DISABLED,  pair-secret-0123, pair-secret-0123, AES/GCM/NoPadding, AES/GCM/NoPadding, CLEAR",
            "DISABLED,  PREFERRED, pair-secret-0123, pair-secret-0123, AES/GCM/NoPadding, AES/GCM/NoPadding, CLEAR_FOLLOWER_ERROR",
            "DISABLED,  REQUIRED,  pair-secret-0123, pair-secret-0123, AES/GCM/NoPadding, AES/GCM/NoPadding, ENCRYPTION_REQUIRED",
            "PREFERRED, DISABLED,  pair-secret-0123, pair-secret-0123, AES/GCM/NoPadding, AES/GCM/NoPadding, CLEAR_LEADER_ERROR",
            "REQUIRED,  DISABLED,  pair-secret-0123, pair-secret-0123, AES/GCM/NoPadding, AES/GCM/NoPadding, ENCRYPTION_REQUIRED",
            "PREFERRED, PREFERRED, pair-secret-0123, pair-secret-0123, AES/GCM/NoPadding, AES/GCM/NoPadding, ENCRYPTED",
            "PREFERRED, REQUIRED,  pair-secret-0123, pair-secret-0123, AES/CTR/NoPadding, AES/CTR/NoPadding, ENCRYPTED",
            "REQUIRED,  PREFERRED, pair-secret-0123, pair-secret-0123, ChaCha20,          ChaCha20,          ENCRYPTED",
            "REQUIRED,  REQUIRED,  pair-secret-0123, pair-secret-0123, ChaCha20-Poly1305, ChaCha20-Poly1305, ENCRYPTED",
            "REQUIRED,  REQUIRED,  pair-secret-0123, other-secret-456, AES/GCM/NoPadding, AES/GCM/NoPadding, KEY_MISMATCH",
            "PREFERRED, PREFERRED, pair-secret-0123, other-secret-456, AES/GCM/NoPadding, AES/GCM/NoPadding, KEY_MISMATCH",
            "REQUIRED,  REQUIRED,  pair-secret-0123, pair-secret-0123, AES/GCM/NoPadding, ChaCha20-Poly1305, KEY_MISMATCH"})
    void theModesSecretsAndCiphersDecideTheConnection(EncryptionMode leaderMode, EncryptionMode followerMode,
                                                      String leaderSecret, String followerSecret, String leaderCipher,
                                                      String followerCipher, String outcome) {
        InetSocketAddress address = connect(exchange(leaderMode, leaderSecret, leaderCipher),
                exchange(followerMode, followerSecret, followerCipher), 0);

        // named by its address until its WELCOME, which a refusal may come before
        String leaderEnd = outcome.equals("ENCRYPTION_REQUIRED") ? ":" + address.getPort() : leaderId;
        if (outcome.equals("ENCRYPTION_REQUIRED") || outcome.equals("KEY_MISMATCH")) {
            await().untilAsserted(() -> {
                assertThat(events(FollowerConnection.class, followerId, Level.ERROR)).extracting(LoggingEvent::getFormattedMessage)
                        .anyMatch(message -> message.contains(outcome));
                assertThat(events(LeaderConnection.class, leaderEnd, Level.ERROR)).extracting(LoggingEvent::getFormattedMessage)
                        .anyMatch(message -> message.contains(outcome));
            });
            assertThat(leader.getFollowers().connections()).noneMatch(connection -> connection.isStreamInSync(0));
            assertThat(events(LeaderConnection.class, leaderEnd, Level.INFO)).as("the follower never counted the lane connected")
                    .extracting(LoggingEvent::getFormattedMessage).noneMatch(message -> message.startsWith("Following leader"));
            return;
        }
        awaitReplicated();
        FollowerConnection leaderSide = leader.getFollowers().connections().get(0);
        LeaderConnection followerSide = client.getConnection(0);
        boolean encrypted = outcome.equals("ENCRYPTED");
        assertThat(leaderSide.isEncrypted()).as("the leader encrypts").isEqualTo(encrypted);
        assertThat(followerSide.isEncrypted()).as("the follower encrypts").isEqualTo(encrypted);
        assertThat(events(FollowerConnection.class, followerId, Level.ERROR)).as("an error on the leader")
                .hasSize(outcome.equals("CLEAR_LEADER_ERROR") ? 1 : 0);
        assertThat(events(LeaderConnection.class, leaderId, Level.ERROR)).as("an error on the follower")
                .hasSize(outcome.equals("CLEAR_FOLLOWER_ERROR") ? 1 : 0);
    }
}
