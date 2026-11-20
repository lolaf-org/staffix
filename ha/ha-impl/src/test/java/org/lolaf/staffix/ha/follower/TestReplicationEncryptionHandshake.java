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
package org.lolaf.staffix.ha.follower;

import com.github.valfirst.slf4jtest.LoggingEvent;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lolaf.betty.api.ss.WakeupSelectStrategy;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.ha.EncryptionMode;
import org.lolaf.staffix.ha.ReplicationFixEngineHa;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.ha.encryption.KeyExchange;
import org.lolaf.staffix.ha.encryption.TestingKeyExchanges;
import org.lolaf.staffix.ha.leader.FollowerConnection;
import org.lolaf.staffix.ha.leader.ReplicationServer;
import org.lolaf.staffix.ha.leader.ReplicationStats;
import org.lolaf.staffix.ha.leader.SlowFollowers;
import org.lolaf.staffix.tests.TestPorts;
import org.lolaf.staffix.tests.TestingClock;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Every row of the encryption behaviour table over a real connection, with the two secret and cipher mismatches.
 */
class TestReplicationEncryptionHandshake {

    private static final long EPOCH = 7;
    private static final FixSessionSettings MAIN = RecordingEngine.session("main", FixSessionType.ACCEPTOR).toBuilder()
            .highAvailability(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.ASYNC).build())
            .build();

    private final ReplicationFixEngineHa leader = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1 << 16).build());
    private final RecordingEngine follower = new RecordingEngine().with(MAIN);
    private final ReplicaSessions replicaSessions = new ReplicaSessions(follower);
    private ReplicationServer server;
    private ReplicationClient client;

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

    @AfterEach
    void stop() {
        if (client != null) {
            client.stop(Deadline.of(Duration.ofSeconds(2)));
        }
        if (server != null) {
            server.stop(Deadline.of(Duration.ofSeconds(2)));
        }
        replicaSessions.close(Deadline.of(Duration.ofSeconds(2)));
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
        String leaderId = "leader-" + System.identityHashCode(this);
        String followerId = "follower-" + System.identityHashCode(this);
        InetSocketAddress address = new InetSocketAddress("localhost", TestPorts.findFree());
        leader.replicatingStore(MAIN, new RecordingEngine.RecordingStore().start(), new RecordingProbes());
        server = new ReplicationServer(address, leader.getReplicationLogs(), leader.getSessions(), leader.getFollowers(),
                () -> EPOCH, leaderId, Duration.ofMillis(100), Duration.ofSeconds(5), 4, Integer.MAX_VALUE,
                BackoffIdleStrategy::new, new WakeupSelectStrategy(10),
                new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingClock())), new TestingClock(),
                exchange(leaderMode, leaderSecret, leaderCipher));
        server.start();
        client = new ReplicationClient(address, replicaSessions, () -> EPOCH, followerId,
                leader.getReplicationLogs().get(0).getMaxEventFrameBytes(), Duration.ofMillis(50),
                new WakeupSelectStrategy(10), 0, BackoffIdleStrategy::new, Duration.ofSeconds(1),
                exchange(followerMode, followerSecret, followerCipher));
        client.start();

        // named by its address until its WELCOME, which a refusal may come before
        String leaderEnd = outcome.equals("ENCRYPTION_REQUIRED") ? ":" + address.getPort() : leaderId;
        if (outcome.equals("ENCRYPTION_REQUIRED") || outcome.equals("KEY_MISMATCH")) {
            await().untilAsserted(() -> {
                assertThat(events(FollowerConnection.class, followerId, Level.ERROR)).extracting(LoggingEvent::getFormattedMessage)
                        .anyMatch(message -> message.contains(outcome));
                assertThat(events(LeaderConnection.class, leaderEnd, Level.ERROR)).extracting(LoggingEvent::getFormattedMessage)
                        .anyMatch(message -> message.contains(outcome));
            });
            assertThat(leader.getFollowers().connections()).noneMatch(connection -> connection.isSessionInSync(0));
            return;
        }
        await().until(() -> leader.getFollowers().connections().stream().anyMatch(connection -> connection.isSessionInSync(0)));
        FollowerConnection leaderSide = leader.getFollowers().connections().get(0);
        LeaderConnection followerSide = client.getConnection(0);
        boolean encrypted = outcome.equals("ENCRYPTED");
        assertThat(leaderSide.getEncryptionKeys() != null).as("the leader encrypts").isEqualTo(encrypted);
        assertThat(followerSide.getEncryptionKeys() != null).as("the follower encrypts").isEqualTo(encrypted);
        assertThat(events(FollowerConnection.class, followerId, Level.ERROR)).as("an error on the leader")
                .hasSize(outcome.equals("CLEAR_LEADER_ERROR") ? 1 : 0);
        assertThat(events(LeaderConnection.class, leaderId, Level.ERROR)).as("an error on the follower")
                .hasSize(outcome.equals("CLEAR_FOLLOWER_ERROR") ? 1 : 0);
    }
}
