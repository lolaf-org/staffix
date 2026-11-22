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
import com.github.valfirst.slf4jtest.TestLogger;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.EncryptionMode;
import org.lolaf.staffix.ha.encryption.KeyExchange;
import org.lolaf.staffix.ha.encryption.TestingKeyExchanges;
import org.lolaf.staffix.ha.wire.TestingFrameEncoder;
import org.lolaf.staffix.ha.wire.CloseReason;
import org.lolaf.staffix.ha.wire.FrameCipher;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.FrameType;
import org.lolaf.staffix.ha.wire.WireFormat;
import org.slf4j.event.Level;

import java.nio.ByteBuffer;
import java.util.function.ToIntBiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The follower's side of the encryption handshake: what its HELLO says, the KEY_CONFIRMATION it answers an encrypting
 * WELCOME with, and the rows of the behaviour table it decides on the WELCOME.
 */
class TestLeaderConnectionEncryption extends LeaderConnectionHarness {

    private final TestLogger connectionLog;

    TestLeaderConnectionEncryption() {
        this.connectionLog = TestLoggerFactory.getTestLogger(LeaderConnection.class);
    }

    @BeforeEach
    void clearThisThreadsLogs() {
        TestLoggerFactory.clear();
    }

    private static ToIntBiFunction<ByteBuffer, Integer> welcome(EncryptionMode leaderMode, byte[] leaderNonce) {
        return (out, index) -> TestingFrameEncoder.welcome(out, index, EPOCH, "leader-a", WireFormat.NO_RESUME, 1, leaderMode,
                leaderNonce);
    }

    @SafeVarargs
    private static void read(LeaderConnection connection, ToIntBiFunction<ByteBuffer, Integer>... frames) {
        connection.read(writeFrames(frames), new FrameDecoder(1 << 20));
    }

    private static ToIntBiFunction<ByteBuffer, Integer> sealed(FrameCipher sealer, ToIntBiFunction<ByteBuffer, Integer> frame) {
        return (out, index) -> sealer.seal(out, index, frame.applyAsInt(out, index));
    }

    /**
     * The leader's sealer for the connection's last HELLO.
     */
    private FrameCipher leaderSealer(KeyExchange leader, byte[] leaderNonce) {
        return leader.newSealer(leader.deriveKeys(sent.lastNonce, leaderNonce).getLeaderToFollower());
    }

    /**
     * Encrypted, the session announced and complete by snapshot, so a clean hand over would hand it over.
     */
    private FrameCipher streamingInSync(LeaderConnection encrypting, String cipher) {
        return streamingInSync(encrypting, MAIN, cipher);
    }

    private FrameCipher streamingInSync(LeaderConnection encrypting, FixSessionSettings settings, String cipher) {
        KeyExchange leader = required(cipher);
        encrypting.hello();
        byte[] leaderNonce = leader.newNonce();
        FrameCipher sealer = leaderSealer(leader, leaderNonce);
        read(encrypting, welcome(EncryptionMode.REQUIRED, leaderNonce), sealed(sealer, session(0, settings)),
                sealed(sealer, announced(1)),
                sealed(sealer, (out, index) -> TestingFrameEncoder.snapshotBegin(out, index, EPOCH, 0, 0, 1, 1)),
                sealed(sealer, (out, index) -> TestingFrameEncoder.snapshotEnd(out, index, EPOCH, 0, 0, 1, 1)));
        assertThat(encrypting.getState()).isEqualTo(LeaderConnection.State.STREAMING);
        return sealer;
    }

    private static KeyExchange required(String cipher) {
        return TestingKeyExchanges.of(EncryptionMode.REQUIRED, TestingKeyExchanges.SECRET, cipher);
    }

    private boolean handedOver() {
        HaSessionStatus.HaSessionStatusBuilder status = HaSessionStatus.builder();
        replicaSessions.describe(FixSessionType.INITIATOR, MAIN.getFixSessionId().toString(), status);
        return status.build().isHandedOver();
    }

    private long errors() {
        return connectionLog.getLoggingEvents().stream().filter(event -> event.getLevel() == Level.ERROR).count();
    }

    private static byte[] bytes(ByteBuffer frame, int length) {
        byte[] bytes = new byte[length];
        frame.get(bytes);
        return bytes;
    }

    @Test
    void anEncryptingFollowerSaysSoInItsHelloWithANonce() {
        newConnection(TestingKeyExchanges.of(EncryptionMode.PREFERRED)).hello();

        assertThat(sent.frames).singleElement().asString().endsWith("lane=0 encryption=PREFERRED nonce=random");
    }

    @Test
    void bothEncryptingTheFollowerConfirmsItsKeysBeforeItAnswersAnything() {
        KeyExchange leader = TestingKeyExchanges.of(EncryptionMode.REQUIRED);
        LeaderConnection encrypting = newConnection(TestingKeyExchanges.of(EncryptionMode.PREFERRED));
        encrypting.hello();
        byte[] followerNonce = sent.lastNonce;
        byte[] leaderNonce = leader.newNonce();

        read(encrypting, welcome(EncryptionMode.REQUIRED, leaderNonce));

        assertThat(encrypting.getState()).isEqualTo(LeaderConnection.State.AWAITING_KEY_ACCEPTANCE);
        assertThat(encrypting.getEncryptionKeys()).isNotNull();
        assertThat(sent.frames).hasSize(2);
        assertThat(sent.frames.get(1)).isEqualTo("KEY_CONFIRMATION epoch=7 bytes=32");
        ByteBuffer hello = ByteBuffer.allocate(FrameEncoder.bytesWith(FrameType.HELLO, "follower-b"));
        int helloLength = TestingFrameEncoder.hello(hello, 0, EPOCH, "follower-b", replicaSessions.getLastAppliedEpoch(0),
                replicaSessions.getLastAppliedOffset(0), 0, EncryptionMode.PREFERRED, followerNonce);
        ByteBuffer welcome = ByteBuffer.allocate(FrameEncoder.bytesWith(FrameType.WELCOME, "leader-a"));
        int welcomeLength = welcome(EncryptionMode.REQUIRED, leaderNonce).applyAsInt(welcome, 0);
        assertThat(leader.confirms(leader.deriveKeys(followerNonce, leaderNonce), bytes(hello, helloLength),
                bytes(welcome, welcomeLength), sent.lastKeyConfirmation)).as("the leader verifies it").isTrue();
        assertThat(connectionLog.getLoggingEvents()).extracting(LoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.startsWith("Following leader"));
    }

    @Test
    void anEncryptedLaneStreamsOnTheLeadersFirstFrameAfterTheKeyConfirmation() {
        KeyExchange leader = TestingKeyExchanges.of(EncryptionMode.REQUIRED);
        LeaderConnection encrypting = newConnection(TestingKeyExchanges.of(EncryptionMode.REQUIRED));
        encrypting.hello();
        byte[] leaderNonce = leader.newNonce();
        FrameCipher sealer = leaderSealer(leader, leaderNonce);
        read(encrypting, welcome(EncryptionMode.REQUIRED, leaderNonce));

        read(encrypting, sealed(sealer, heartbeat()));

        assertThat(encrypting.getState()).isEqualTo(LeaderConnection.State.STREAMING);
        assertThat(sent.frames).last().asString().startsWith("ACK epoch=7");
        assertThat(connectionLog.getLoggingEvents()).extracting(LoggingEvent::getFormattedMessage)
                .contains("Following leader leader-a encrypted on lane 0, AES/CTR/NoPadding 256 HmacSHA256");
    }

    @Test
    void anEncryptedLaneTheLeaderRefusesTheKeysOfIsNeverConnected() {
        LeaderConnection encrypting = newConnection(TestingKeyExchanges.of(EncryptionMode.REQUIRED));
        encrypting.hello();
        read(encrypting, welcome(EncryptionMode.REQUIRED, TestingKeyExchanges.of(EncryptionMode.REQUIRED).newNonce()));

        read(encrypting, close(CloseReason.KEY_MISMATCH));

        assertThat(encrypting.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(connectionLog.getLoggingEvents()).extracting(LoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.startsWith("Following leader"));
        assertThat(errors()).isEqualTo(1);
    }

    @Test
    void aFollowerRequiringEncryptionRefusesAWelcomeWithoutIt() {
        LeaderConnection requiring = newConnection(TestingKeyExchanges.of(EncryptionMode.REQUIRED));
        requiring.hello();

        read(requiring, welcome(EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE));

        assertThat(requiring.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).last().asString()
                .startsWith("CLOSE epoch=7 ENCRYPTION_REQUIRED this follower's encryption is REQUIRED, the leader's DISABLED");
        assertThat(errors()).isEqualTo(1);
    }

    @Test
    void aFollowerPreferringEncryptionFollowsALeaderWithoutItInClearWithAnError() {
        LeaderConnection preferring = newConnection(TestingKeyExchanges.of(EncryptionMode.PREFERRED));
        preferring.hello();

        read(preferring, welcome(EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE));

        assertThat(preferring.getState()).isEqualTo(LeaderConnection.State.STREAMING);
        assertThat(preferring.getEncryptionKeys()).isNull();
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("KEY_CONFIRMATION"));
        assertThat(errors()).isEqualTo(1);
    }

    @Test
    void aLeaderRefusingTheEncryptionIsAnError() {
        connection.hello();

        read((out, index) -> FrameEncoder.close(out, index, EPOCH, CloseReason.KEY_MISMATCH, "secret or cipher differ"));

        assertThat(errors()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/CTR/NoPadding", "AES/GCM/NoPadding"})
    void aFrameFailingItsTagClosesTheConnectionAndIsNeverApplied(String cipher) {
        KeyExchange leader = required(cipher);
        LeaderConnection encrypting = newConnection(required(cipher));
        encrypting.hello();
        byte[] leaderNonce = leader.newNonce();
        FrameCipher sealer = leaderSealer(leader, leaderNonce);
        read(encrypting, welcome(EncryptionMode.REQUIRED, leaderNonce), sealed(sealer, session(0, MAIN)),
                sealed(sealer, announced(1)),
                sealed(sealer, (out, index) -> TestingFrameEncoder.snapshotBegin(out, index, EPOCH, 0, 0, 1, 1)),
                sealed(sealer, (out, index) -> TestingFrameEncoder.snapshotEnd(out, index, EPOCH, 0, 0, 1, 1)),
                sealed(sealer, event(EPOCH, 0, 0, StoreEventType.STORE_MESSAGE, 1, MESSAGE)));
        assertThat(engine.store(MAIN).writes).contains("message 1 " + MESSAGE);
        ByteBuffer tampered = writeFrames(sealed(sealer, event(EPOCH, 100, 0, StoreEventType.STORE_MESSAGE, 2, MESSAGE)));
        tampered.put(tampered.limit() - 1, (byte) (tampered.get(tampered.limit() - 1) ^ 1));

        encrypting.read(tampered, new FrameDecoder(1 << 20));

        assertThat(encrypting.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).last().asString()
                .isEqualTo("CLOSE epoch=7 PROTOCOL_ERROR RECORD fails its authentication tag");
        assertThat(engine.store(MAIN).writes).noneMatch(write -> write.startsWith("message 2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/CTR/NoPadding", "AES/GCM/NoPadding"})
    void anAuthenticShutdownHandsTheSessionOver(String cipher) {
        LeaderConnection encrypting = newConnection(required(cipher));
        FrameCipher sealer = streamingInSync(encrypting, cipher);

        read(encrypting, sealed(sealer, close(CloseReason.SHUTDOWN)));

        assertThat(handedOver()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/CTR/NoPadding", "AES/GCM/NoPadding"})
    void aShutdownWithoutItsTagHandsNothingOver(String cipher) {
        LeaderConnection encrypting = newConnection(required(cipher));
        streamingInSync(encrypting, cipher);

        read(encrypting, close(CloseReason.SHUTDOWN));

        assertThat(handedOver()).isFalse();
        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/CTR/NoPadding", "AES/GCM/NoPadding"})
    void aShutdownAfterADroppedFrameFailsItsTagAndHandsNothingOver(String cipher) {
        LeaderConnection encrypting = newConnection(required(cipher));
        FrameCipher sealer = streamingInSync(encrypting, cipher);
        writeFrames(sealed(sealer, event(EPOCH, 0, 0, StoreEventType.STORE_MESSAGE, 1, MESSAGE)));

        read(encrypting, sealed(sealer, close(CloseReason.SHUTDOWN)));

        assertThat(handedOver()).isFalse();
        assertThat(sent.frames).last().asString()
                .isEqualTo("CLOSE epoch=7 PROTOCOL_ERROR CLOSE fails its authentication tag");
    }

    @Test
    void withAStoreWriterThreadAnEncryptedConnectionAcknowledgesReceiptOnceTheTagVerified() {
        FixSessionSettings syncReceived = MAIN.toBuilder().highAvailability(FixSessionSettings.HaSessionSettings.builder()
                .replicationMode(ReplicationMode.SYNC_RECEIVED).build()).build();
        engine.replace(syncReceived);
        assertThat(newStoreWriterConnection(TestingKeyExchanges.CLEAR).isReceivedAckedOnRead())
                .as("in clear, the network thread acknowledges what it reads").isTrue();
        LeaderConnection encrypting = newStoreWriterConnection(TestingKeyExchanges.of(EncryptionMode.REQUIRED));
        FrameCipher sealer = streamingInSync(encrypting, syncReceived, TestingKeyExchanges.CIPHER);
        assertThat(encrypting.isReceivedAckedOnRead()).as("encrypted, it does not").isFalse();

        read(encrypting, sealed(sealer, event(EPOCH, 0, 0, StoreEventType.STORE_MESSAGE, 1, MESSAGE)));

        assertThat(sent.frames).contains("RECEIVED epoch=7 received=0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/CTR/NoPadding", "AES/GCM/NoPadding"})
    void theAnswersAfterTheKeyConfirmationAreSealed(String cipher) {
        LeaderConnection encrypting = newConnection(required(cipher));

        streamingInSync(encrypting, cipher);

        assertThat(sent.frames).as("opened as the leader opens them").contains("STREAM_ANSWER epoch=7 key=0 REPLICATING");
    }
}
