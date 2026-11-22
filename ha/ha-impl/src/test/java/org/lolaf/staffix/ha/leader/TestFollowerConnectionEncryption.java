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

import com.github.valfirst.slf4jtest.LoggingEvent;
import lombok.extern.slf4j.Slf4j;
import com.github.valfirst.slf4jtest.TestLogger;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.staffix.api.ha.FixEngineHa.ReplicatingStore;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.serde.Hashing;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreException;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreMessagesConsumer;
import org.lolaf.staffix.ha.EncryptionMode;
import org.lolaf.staffix.ha.follower.RecordingProbes;
import org.lolaf.staffix.ha.log.LogFullWait;
import org.lolaf.staffix.ha.log.ReplicationLog;
import org.lolaf.staffix.ha.log.StoreEventRecord;
import org.lolaf.staffix.ha.log.TestingLogFragments;
import org.lolaf.staffix.ha.encryption.JceAeadAllocation;
import org.lolaf.staffix.ha.encryption.KeyExchange;
import org.lolaf.staffix.ha.encryption.TestingKeyExchanges;
import org.lolaf.staffix.ha.wire.AllocatedBytes;
import org.lolaf.staffix.ha.wire.CloseReason;
import org.lolaf.staffix.ha.wire.FrameCipher;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.FrameType;
import org.lolaf.staffix.ha.wire.SessionAnswer;
import org.lolaf.staffix.ha.wire.WireFormat;
import org.lolaf.staffix.ha.wire.WireFormatException;
import org.slf4j.event.Level;

import javax.crypto.Cipher;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * The leader's side of the encryption handshake: the rows of the behaviour table it decides, and the KEY_CONFIRMATION it
 * waits for before anything streams.
 */
@Slf4j
class TestFollowerConnectionEncryption extends FollowerConnectionHarness {

    private static final MessageType QUOTE = MessageType.of("S", false);
    private static final MessageType ORDER = MessageType.of("D", false);
    private static final String QUOTE_TEXT = "8=FIX.4.4\u00019=5\u000135=S\u0001quote-text\u000110=000\u0001";
    private static final String ORDER_TEXT = "8=FIX.4.4\u00019=5\u000135=D\u0001order-text\u000110=000\u0001";
    private static final int ORDER_HASH = Hashing.hash("D");
    private static final int FILLED_MESSAGE_BYTES = 200;
    private static final int EVENTS_PER_BATCH = 10;

    private final TestLogger connectionLog;

    TestFollowerConnectionEncryption() {
        this.connectionLog = TestLoggerFactory.getTestLogger(FollowerConnection.class);
    }

    private static ByteBuffer ascii(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static FixSessionSettings sessionSettings(String name, String... unencryptedMessageTypes) {
        return settings(name).toBuilder().highAvailability(FixSessionSettings.HaSessionSettings.builder()
                .replicationMode(ReplicationMode.ASYNC).unencryptedMessageTypes(List.of(unencryptedMessageTypes)).build())
                .build();
    }

    private ReplicatingStore session(String name, String... unencryptedMessageTypes) {
        return ha.replicatingStore(sessionSettings(name, unencryptedMessageTypes), startedStore(), new RecordingProbes());
    }

    /**
     * Streams to a follower that confirmed its keys, the sessions registered so far announced.
     */
    private void streamingEncrypted() {
        streamingEncrypted(TestingKeyExchanges.CIPHER);
    }

    private void streamingEncrypted(String cipher) {
        keyExchange = TestingKeyExchanges.of(EncryptionMode.REQUIRED, TestingKeyExchanges.SECRET, cipher);
        connection = connection();
        KeyExchange follower = TestingKeyExchanges.of(EncryptionMode.REQUIRED, TestingKeyExchanges.SECRET, cipher);
        byte[] followerNonce = follower.newNonce();
        hello(EncryptionMode.REQUIRED, followerNonce);
        connection.onKeyConfirmation(EPOCH, followerConfirmation(follower, followerNonce));
        fill();
    }

    @BeforeEach
    void clearThisThreadsLogs() {
        TestLoggerFactory.clear();
    }

    private void leaderEncryption(EncryptionMode mode) {
        keyExchange = mode == EncryptionMode.DISABLED ? TestingKeyExchanges.CLEAR : TestingKeyExchanges.of(mode);
        connection = connection();
    }

    private void hello(EncryptionMode followerMode, byte[] followerNonce) {
        connection.onHello(EPOCH, WireFormat.VERSION, "follower-b", 0, -1, 0, followerMode, followerNonce);
    }

    private long errors() {
        return connectionLog.getLoggingEvents().stream().filter(event -> event.getLevel() == Level.ERROR).count();
    }

    /**
     * What an encrypting follower would send after the WELCOME the connection sent.
     */
    private byte[] followerConfirmation(KeyExchange follower, byte[] followerNonce) {
        byte[] leaderNonce = sent.lastNonce;
        byte[] helloBytes = FrameEncoder.helloBytes(EPOCH, "follower-b", 0, -1, 0, EncryptionMode.REQUIRED, followerNonce);
        byte[] welcomeBytes = FrameEncoder.welcomeBytes(EPOCH, "leader-a", WireFormat.NO_RESUME, 1,
                EncryptionMode.REQUIRED, leaderNonce);
        return follower.confirm(follower.deriveKeys(followerNonce, leaderNonce), helloBytes, welcomeBytes);
    }

    @Test
    void aLeaderRequiringEncryptionRefusesAFollowerWithoutIt() {
        leaderEncryption(EncryptionMode.REQUIRED);

        hello(EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        assertThat(sent.frames).singleElement().asString()
                .startsWith("CLOSE epoch=7 ENCRYPTION_REQUIRED this leader's encryption is REQUIRED, the follower's DISABLED");
        assertThat(errors()).isEqualTo(1);
    }

    @Test
    void aLeaderWithoutEncryptionRefusesAFollowerRequiringIt() {
        hello(EncryptionMode.REQUIRED, TestingKeyExchanges.of(EncryptionMode.REQUIRED).newNonce());

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 ENCRYPTION_REQUIRED");
    }

    @Test
    void aLeaderPreferringEncryptionStreamsInClearToAFollowerWithoutItAndLogsAnError() {
        leaderEncryption(EncryptionMode.PREFERRED);

        hello(EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(connection.getEncryptionKeys()).isNull();
        assertThat(sent.frames).first().asString().endsWith("encryption=PREFERRED nonce=random");
        assertThat(errors()).isEqualTo(1);
    }

    @Test
    void aLeaderWithoutEncryptionStreamsInClearToAFollowerPreferringIt() {
        hello(EncryptionMode.PREFERRED, TestingKeyExchanges.of(EncryptionMode.PREFERRED).newNonce());

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(errors()).as("the follower logs it").isZero();
    }

    @Test
    void nothingStreamsBeforeTheKeyConfirmationAndTheRightOneStartsTheStream() {
        replicated("main");
        leaderEncryption(EncryptionMode.REQUIRED);
        KeyExchange follower = TestingKeyExchanges.of(EncryptionMode.REQUIRED);
        byte[] followerNonce = follower.newNonce();

        hello(EncryptionMode.REQUIRED, followerNonce);
        fill();

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.AWAITING_KEY_CONFIRMATION);
        assertThat(connection.getEncryptionKeys()).isNotNull();
        assertThat(sent.frames).singleElement().asString().endsWith("encryption=REQUIRED nonce=random");

        connection.onKeyConfirmation(EPOCH, followerConfirmation(follower, followerNonce));
        fill();

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(sent.frames).anyMatch(frame -> frame.startsWith("SESSION "));
        assertThat(connectionLog.getLoggingEvents()).extracting(LoggingEvent::getFormattedMessage)
                .contains("Replicating encrypted to follower follower-b lane 0, AES/CTR/NoPadding 256 HmacSHA256");
    }

    @Test
    void aKeyConfirmationOfAnotherSecretClosesWithKeyMismatch() {
        leaderEncryption(EncryptionMode.REQUIRED);
        KeyExchange follower = TestingKeyExchanges.of(EncryptionMode.REQUIRED, "another-secret-entirely",
                TestingKeyExchanges.CIPHER);
        byte[] followerNonce = follower.newNonce();
        hello(EncryptionMode.REQUIRED, followerNonce);

        connection.onKeyConfirmation(EPOCH, followerConfirmation(follower, followerNonce));

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 KEY_MISMATCH the follower's secret or cipher "
                + "differ from this leader's AES/CTR/NoPadding 256 HmacSHA256");
        assertThat(errors()).isEqualTo(1);
    }

    @Test
    void aSecondKeyConfirmationWhileStreamingIsAProtocolError() {
        replicated("main");
        streamingEncrypted();

        connection.onKeyConfirmation(EPOCH, new byte[WireFormat.KEY_CONFIRMATION_BYTES]);

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        assertThat(sent.frames).last().asString()
                .isEqualTo("CLOSE epoch=7 PROTOCOL_ERROR a KEY_CONFIRMATION on a connection not waiting for one");
    }

    @Test
    void aKeyConfirmationOnAClearConnectionIsAProtocolError() {
        hello(EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        connection.onKeyConfirmation(EPOCH, new byte[WireFormat.KEY_CONFIRMATION_BYTES]);

        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void aFollowerNotConfirmingItsKeysWithinTheStallTimeoutIsClosed() throws InterruptedException {
        keyExchange = TestingKeyExchanges.of(EncryptionMode.REQUIRED);
        Thread[] sender = new Thread[1];
        connection = connectionStallingAfter(Duration.ofMillis(100), sender);
        hello(EncryptionMode.REQUIRED, TestingKeyExchanges.of(EncryptionMode.REQUIRED).newNonce());
        awaitSenderTurn();
        senderClock.advance(Duration.ofMillis(100));
        awaitSenderTurn();
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.AWAITING_KEY_CONFIRMATION);

        senderClock.advance(Duration.ofNanos(1));

        sender[0].join(5_000);
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR no KEY_CONFIRMATION within 100 ms");
    }

    @Test
    void anEncryptedStreamSendsNeitherASessionIdNorAMessageReadable() {
        ReplicatingStore main = session("main");
        streamingEncrypted();

        main.storeMessageSent(ORDER, 1, ascii(ORDER_TEXT));
        fill();

        assertThat(sent.frames).anyMatch(frame -> frame.startsWith("SESSION ") && frame.contains(SESSION));
        assertThat(sent.frames).last().asString().endsWith("seq=1 message=" + ORDER_TEXT);
        assertThat(sentBytesContain("SENDER")).as("the session id on the socket").isFalse();
        assertThat(sentBytesContain("8=FIX")).as("a message on the socket").isFalse();
    }

    @Test
    void theTypesASessionListsGoReadableTheOthersEncrypted() {
        ReplicatingStore quotes = session("quotes", "S");
        streamingEncrypted();

        quotes.storeMessageSent(QUOTE, 1, ascii(QUOTE_TEXT));
        quotes.storeMessageSent(ORDER, 2, ascii(ORDER_TEXT));
        fill();

        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("STORE_EVENT")).extracting(frame -> frame.substring(frame.indexOf("seq=")))
                .containsExactly("seq=1 message=" + QUOTE_TEXT, "seq=2 message=" + ORDER_TEXT);
        assertThat(sentBytesContain("quote-text")).isTrue();
        assertThat(sentBytesContain("order-text")).isFalse();
    }

    @Test
    void aFragmentedUnencryptedMessageInterleavedWithAnotherSessionsEncryptedOneOpensWhole() {
        session("quotes", "S");
        ReplicatingStore orders = session("orders");
        streamingEncrypted();

        TestingLogFragments.appendFragment(ha.getReplicationLogs().get(0), 0, 1, Hashing.hash("S"), ascii("quote-part-one"),
                WireFormat.MORE_FRAGMENTS);
        orders.storeMessageSent(ORDER, 1, ascii(ORDER_TEXT));
        TestingLogFragments.appendFragment(ha.getReplicationLogs().get(0), 0, 1, Hashing.hash("S"), ascii("quote-part-two"),
                WireFormat.CONTINUED_FRAGMENT);
        fill();

        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("STORE_EVENT")).extracting(frame -> frame.substring(frame.indexOf("key=")))
                .containsExactly("key=0 STORE_MESSAGE seq=1 message=quote-part-one more",
                        "key=1 STORE_MESSAGE seq=1 message=" + ORDER_TEXT,
                        "key=0 STORE_MESSAGE seq=1 message=quote-part-two continued");
        assertThat(sentBytesContain("quote-part-one")).isTrue();
        assertThat(sentBytesContain("quote-part-two")).isTrue();
        assertThat(sentBytesContain("order-text")).isFalse();
    }

    @Test
    void aSnapshotSendsTheTypesTheSessionListsReadableTheOthersEncrypted() {
        FixSessionMessagesStore local = startedStore();
        when(local.getIncomingSeqNum()).thenReturn(1L);
        when(local.getOutgoingSeqNum()).thenReturn(3L);
        doAnswer(invocation -> {
            StoreMessagesConsumer consumer = invocation.getArgument(2);
            consumer.onMessage(1, ascii(QUOTE_TEXT));
            consumer.onMessage(2, ascii(ORDER_TEXT));
            return null;
        }).when(local).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));
        ha.replicatingStore(sessionSettings("quotes", "S"), local, new RecordingProbes());
        streamingEncrypted();

        connection.onSessionAnswer(EPOCH, 0, SessionAnswer.SNAPSHOT_NEEDED);
        snapshot();

        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("SNAPSHOT_MESSAGE"))
                .extracting(frame -> frame.substring(frame.indexOf("seq=")))
                .containsExactly("seq=1 message=" + QUOTE_TEXT, "seq=2 message=" + ORDER_TEXT);
        assertThat(sentBytesContain("quote-text")).isTrue();
        assertThat(sentBytesContain("order-text")).isFalse();
    }

    @Test
    void aSnapshotFailingMidwaySendsWhatItSealedBeforeItsClose() {
        FixSessionMessagesStore local = startedStore();
        when(local.getIncomingSeqNum()).thenReturn(1L);
        when(local.getOutgoingSeqNum()).thenReturn(3L);
        doAnswer(invocation -> {
            StoreMessagesConsumer consumer = invocation.getArgument(2);
            consumer.onMessage(1, ascii(ORDER_TEXT));
            throw new StoreException("storage down");
        }).when(local).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));
        ha.replicatingStore(sessionSettings("main"), local, new RecordingProbes());
        streamingEncrypted();

        connection.onSessionAnswer(EPOCH, 0, SessionAnswer.SNAPSHOT_NEEDED);
        snapshot();

        assertThat(sent.frames).as("opened in order, the CLOSE included")
                .filteredOn(frame -> frame.startsWith("SNAPSHOT_MESSAGE")).hasSize(1);
        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 SNAPSHOT_FAILED");
    }

    @Test
    void aCloseWhileTheSenderSnapshotsIsSealedAfterWhatTheSnapshotSealed() {
        FixSessionMessagesStore local = startedStore();
        when(local.getIncomingSeqNum()).thenReturn(1L);
        when(local.getOutgoingSeqNum()).thenReturn(3L);
        doAnswer(invocation -> {
            StoreMessagesConsumer consumer = invocation.getArgument(2);
            consumer.onMessage(1, ascii(ORDER_TEXT));
            connection.close(CloseReason.PROTOCOL_ERROR, "closed mid-snapshot");
            consumer.onMessage(2, ascii(ORDER_TEXT));
            return null;
        }).when(local).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));
        ha.replicatingStore(sessionSettings("main"), local, new RecordingProbes());
        keyExchange = TestingKeyExchanges.of(EncryptionMode.REQUIRED);
        connection = senderRunningConnection();
        KeyExchange follower = TestingKeyExchanges.of(EncryptionMode.REQUIRED);
        byte[] followerNonce = follower.newNonce();
        hello(EncryptionMode.REQUIRED, followerNonce);
        connection.onKeyConfirmation(EPOCH, followerConfirmation(follower, followerNonce));
        await().alias("the session's announcement").until(() -> batchesSent.get() == 1);

        connection.onSessionAnswer(EPOCH, 0, SessionAnswer.SNAPSHOT_NEEDED);

        await().ignoreExceptions().untilAsserted(() -> assertThat(sent.frames).last().asString()
                .isEqualTo("CLOSE epoch=7 PROTOCOL_ERROR closed mid-snapshot"));
        assertThat(sent.frames).as("opened in order, the CLOSE included")
                .anyMatch(frame -> frame.startsWith("SNAPSHOT_MESSAGE"));
    }

    @Test
    void aClearConnectionSendsEveryTypeAsItIsWithoutTheUnencryptedFlag() {
        ReplicatingStore quotes = session("quotes", "S");
        hello(EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        quotes.storeMessageSent(QUOTE, 1, ascii(QUOTE_TEXT));
        fill();

        assertThat(sent.frames).last().asString().endsWith("seq=1 message=" + QUOTE_TEXT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"clear", "AES/CTR/NoPadding", "ChaCha20"})
    void fillingABatchAllocatesNothingInClearOrWithAStreamCipher(String cipher) {
        double allocated = fillBytesPerEvent(cipher);

        assertThat(allocated).as("bytes per event filled").isLessThan(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/GCM/NoPadding", "ChaCha20-Poly1305"})
    void fillingABatchWithAnAeadAllocatesNoMoreThanTheJceItself(String cipher) {
        double allocated = fillBytesPerEvent(cipher);
        double jce = JceAeadAllocation.bytesPerFrame(cipher, Cipher.ENCRYPT_MODE,
                WireFormat.HEADER_BYTES + FrameType.STORE_EVENT.getFixedPayloadBytes(), FILLED_MESSAGE_BYTES);

        assertThat(allocated).as("bytes per event filled, the JCE's %s", jce)
                .isLessThan(jce + JceAeadAllocation.JIT_SLACK_BYTES);
    }

    /**
     * Appends events to the log and fills a batch every few, as a session thread and the sender would, on this thread.
     */
    private double fillBytesPerEvent(String cipher) {
        session("main", "S");
        if (cipher.equals("clear")) {
            hello(EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
            fill();
        } else {
            streamingEncrypted(cipher);
        }
        ReplicationLog replicationLog = ha.getReplicationLogs().get(0);
        StoreEventRecord record = new StoreEventRecord();
        LogFullWait wait = new LogFullWait(Duration.ofSeconds(1));
        ByteBuffer message = ByteBuffer.wrap(new byte[FILLED_MESSAGE_BYTES]);
        ByteBuffer batch = ByteBuffer.allocateDirect(1 << 16);
        int warmUp = JceAeadAllocation.warmUpFrames(FILLED_MESSAGE_BYTES);

        appendAndFill(replicationLog, record, wait, message, batch, 0, warmUp);
        long before = AllocatedBytes.ofCurrentThread();
        long filled = appendAndFill(replicationLog, record, wait, message, batch, warmUp, JceAeadAllocation.MEASURED);
        long allocated = AllocatedBytes.ofCurrentThread() - before;

        assertThat(filled).as("every event filled").isGreaterThan((long) JceAeadAllocation.MEASURED * FILLED_MESSAGE_BYTES);
        double perEvent = (double) allocated / JceAeadAllocation.MEASURED;
        log.info("Filling with {}: {} bytes per {} byte event", cipher, perEvent, FILLED_MESSAGE_BYTES);
        return perEvent;
    }

    private long appendAndFill(ReplicationLog replicationLog, StoreEventRecord record, LogFullWait wait, ByteBuffer message,
                               ByteBuffer batch, long firstSeq, int events) {
        long filled = 0;
        for (long seq = firstSeq; seq < firstSeq + events; seq++) {
            replicationLog.append(record.set(0, StoreEventType.STORE_MESSAGE, seq, ORDER_HASH, message), wait);
            if (seq % EVENTS_PER_BATCH == EVENTS_PER_BATCH - 1) {
                filled += connection.fillBatch(batch);
            }
        }
        return filled + connection.fillBatch(batch);
    }

    /**
     * An ACK of {@code appliedOffset} as the follower sends it, sealed by {@code sealer} unless null.
     */
    private static ByteBuffer ack(@Nullable FrameCipher sealer, long appliedOffset) {
        ByteBuffer frame = ByteBuffer.allocate(64);
        int length = FrameEncoder.ack(frame, 0, EPOCH, appliedOffset, true, 0);
        return frame.limit(sealer == null ? length : sealer.seal(frame, 0, length));
    }

    /**
     * As the leader reads the follower's frames.
     */
    private void read(ByteBuffer frames) {
        new FrameDecoder(1024).decode(frames, 0, frames.limit(), connection, connection.getAnswerOpener());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/CTR/NoPadding", "AES/GCM/NoPadding"})
    void aCloseOnceStreamingIsSealedAfterTheLastBatch(String cipher) {
        replicated("main");
        streamingEncrypted(cipher);

        connection.close(CloseReason.PROTOCOL_ERROR, "test");

        assertThat(sent.frames).last().asString().isEqualTo("CLOSE epoch=7 PROTOCOL_ERROR test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/CTR/NoPadding", "AES/GCM/NoPadding"})
    void anAckWithoutItsTagIsRefusedOnceTheKeysAreConfirmed(String cipher) {
        replicated("main");
        streamingEncrypted(cipher);

        assertThatThrownBy(() -> read(ack(null, 1_000))).isInstanceOf(WireFormatException.class);

        assertThat(connection.getAppliedOffset()).isEqualTo(-1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/CTR/NoPadding", "AES/GCM/NoPadding"})
    void aReplayedAckFailsItsTag(String cipher) {
        replicated("main");
        streamingEncrypted(cipher);
        ByteBuffer acked = ack(keyExchange.newSealer(connection.getEncryptionKeys().getFollowerToLeader()), 0);
        read(acked.duplicate());
        assertThat(connection.getAppliedOffset()).isZero();

        assertThatThrownBy(() -> read(acked.duplicate())).isInstanceOf(WireFormatException.class)
                .hasMessage("ACK fails its authentication tag");
    }
}
