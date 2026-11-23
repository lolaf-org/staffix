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

import com.github.valfirst.slf4jtest.LoggingEvent;
import lombok.extern.slf4j.Slf4j;
import com.github.valfirst.slf4jtest.TestLogger;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.staffix.ha.replication.EncryptionMode;
import org.lolaf.staffix.ha.replication.journal.Journal;
import org.lolaf.staffix.ha.replication.journal.JournalStream;
import org.lolaf.staffix.ha.replication.log.LogFullWait;
import org.lolaf.staffix.ha.replication.log.ReplicationLog;
import org.lolaf.staffix.ha.replication.log.LogRecord;
import org.lolaf.staffix.ha.replication.log.TestingLogFragments;
import org.lolaf.staffix.ha.replication.encryption.JceAeadAllocation;
import org.lolaf.staffix.ha.replication.encryption.KeyExchange;
import org.lolaf.staffix.ha.replication.encryption.TestingKeyExchanges;
import org.lolaf.staffix.ha.replication.spi.RecordFlag;
import org.lolaf.staffix.ha.replication.wire.AllocatedBytes;
import org.lolaf.staffix.ha.replication.wire.CloseReason;
import org.lolaf.staffix.ha.replication.wire.FrameCipher;
import org.lolaf.staffix.ha.replication.wire.FrameDecoder;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.wire.FrameType;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;
import org.lolaf.staffix.ha.replication.wire.WireFormat;
import org.lolaf.staffix.ha.replication.spi.WireFormatException;
import org.slf4j.event.Level;

import javax.crypto.Cipher;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The leader's side of the encryption handshake: the rows of the behaviour table it decides, and the KEY_CONFIRMATION it
 * waits for before anything streams.
 */
@Slf4j
class TestFollowerConnectionEncryption extends FollowerConnectionHarness {

    private static final String QUOTE_TEXT = "a quote-text entry, plaintext";
    private static final String ORDER_TEXT = "an order-text entry, encrypted";
    private static final int FILLED_MESSAGE_BYTES = 200;
    private static final int RECORDS_PER_BATCH = 10;

    private final TestLogger connectionLog;

    TestFollowerConnectionEncryption() {
        this.connectionLog = TestLoggerFactory.getTestLogger(FollowerConnection.class);
    }

    private static ByteBuffer ascii(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }

    private JournalStream asyncStream(String name) {
        return journal.add(name, StreamReplicationMode.ASYNC);
    }

    /**
     * Streams to a follower that confirmed its keys, the streams registered so far announced.
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
        connection.onHello(EPOCH, WireFormat.VERSION, Journal.APPLICATION_ID, "follower-b", 0, -1, 0, followerMode, followerNonce);
    }

    private long errors() {
        return connectionLog.getLoggingEvents().stream().filter(event -> event.getLevel() == Level.ERROR).count();
    }

    /**
     * What an encrypting follower would send after the WELCOME the connection sent.
     */
    private byte[] followerConfirmation(KeyExchange follower, byte[] followerNonce) {
        byte[] leaderNonce = sent.lastNonce;
        byte[] helloBytes = FrameEncoder.helloBytes(EPOCH, Journal.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.REQUIRED, followerNonce);
        byte[] welcomeBytes = FrameEncoder.welcomeBytes(EPOCH, Journal.APPLICATION_ID, "leader-a", WireFormat.NO_RESUME, 1,
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
        stream("main");
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
        assertThat(sent.frames).anyMatch(frame -> frame.startsWith("STREAM "));
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
        stream("main");
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
    void anEncryptedConnectionSendsNeitherAStreamNameNorAPayloadReadable() {
        JournalStream main = asyncStream("secret-journal");
        streamingEncrypted();

        main.append(ORDER_TEXT);
        fill();

        assertThat(sent.frames).anyMatch(frame -> frame.startsWith("STREAM ") && frame.contains("name=secret-journal"));
        assertThat(sent.frames).last().asString().endsWith("pos=1 payload=" + ORDER_TEXT);
        assertThat(sentBytesContain("secret-journal")).as("the stream name on the socket").isFalse();
        assertThat(sentBytesContain("order-text")).as("a payload on the socket").isFalse();
    }

    @Test
    void aPlaintextRecordGoesReadableTheOthersEncrypted() {
        JournalStream quotes = asyncStream("quotes");
        streamingEncrypted();

        quotes.appendPlaintext(QUOTE_TEXT);
        quotes.append(ORDER_TEXT);
        fill();

        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("RECORD")).extracting(frame -> frame.substring(frame.indexOf("pos=")))
                .containsExactly("pos=1 payload=" + QUOTE_TEXT, "pos=2 payload=" + ORDER_TEXT);
        assertThat(sentBytesContain("quote-text")).isTrue();
        assertThat(sentBytesContain("order-text")).isFalse();
    }

    @Test
    void aFragmentedPlaintextRecordInterleavedWithAnotherStreamsEncryptedOneOpensWhole() {
        asyncStream("quotes");
        JournalStream orders = asyncStream("orders");
        streamingEncrypted();

        TestingLogFragments.appendFragment(journal.getLogs().get(0), 0, 1, RecordFlag.PLAINTEXT,
                ascii("quote-part-one"), WireFormat.MORE_FRAGMENTS);
        orders.append(ORDER_TEXT);
        TestingLogFragments.appendFragment(journal.getLogs().get(0), 0, 1, RecordFlag.PLAINTEXT,
                ascii("quote-part-two"), WireFormat.CONTINUED_FRAGMENT);
        fill();

        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("RECORD")).extracting(frame -> frame.substring(frame.indexOf("key=")))
                .containsExactly("key=0 type=1 pos=1 payload=quote-part-one more",
                        "key=1 type=1 pos=1 payload=" + ORDER_TEXT,
                        "key=0 type=1 pos=1 payload=quote-part-two continued");
        assertThat(sentBytesContain("quote-part-one")).isTrue();
        assertThat(sentBytesContain("quote-part-two")).isTrue();
        assertThat(sentBytesContain("order-text")).isFalse();
    }

    @Test
    void aSnapshotSendsThePlaintextRecordsReadableTheOthersEncrypted() {
        JournalStream quotes = asyncStream("quotes");
        quotes.hold(RecordFlag.PLAINTEXT, QUOTE_TEXT);
        quotes.hold(RecordFlag.NONE, ORDER_TEXT);
        streamingEncrypted();

        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        snapshot();

        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("SNAPSHOT_RECORD"))
                .extracting(frame -> frame.substring(frame.indexOf("pos=")))
                .containsExactly("pos=1 payload=" + QUOTE_TEXT, "pos=2 payload=" + ORDER_TEXT);
        assertThat(sentBytesContain("quote-text")).isTrue();
        assertThat(sentBytesContain("order-text")).isFalse();
    }

    @Test
    void aSnapshotFailingMidwaySendsWhatItSealedBeforeItsClose() {
        JournalStream main = asyncStream("main");
        main.hold(RecordFlag.NONE, ORDER_TEXT);
        main.hold(RecordFlag.NONE, ORDER_TEXT);
        main.setSnapshotReads(position -> {
            if (position == 2) {
                throw new IllegalStateException("storage down");
            }
        });
        streamingEncrypted();

        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        snapshot();

        assertThat(sent.frames).as("opened in order, the CLOSE included")
                .filteredOn(frame -> frame.startsWith("SNAPSHOT_RECORD")).hasSize(1);
        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 SNAPSHOT_FAILED");
    }

    @Test
    void aCloseWhileTheSenderSnapshotsIsSealedAfterWhatTheSnapshotSealed() {
        JournalStream main = asyncStream("main");
        main.hold(RecordFlag.NONE, ORDER_TEXT);
        main.hold(RecordFlag.NONE, ORDER_TEXT);
        main.setSnapshotReads(position -> {
            if (position == 2) {
                connection.close(CloseReason.PROTOCOL_ERROR, "closed mid-snapshot");
            }
        });
        keyExchange = TestingKeyExchanges.of(EncryptionMode.REQUIRED);
        connection = senderRunningConnection();
        KeyExchange follower = TestingKeyExchanges.of(EncryptionMode.REQUIRED);
        byte[] followerNonce = follower.newNonce();
        hello(EncryptionMode.REQUIRED, followerNonce);
        connection.onKeyConfirmation(EPOCH, followerConfirmation(follower, followerNonce));
        await().alias("the stream's announcement").until(() -> batchesSent.get() == 1);

        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);

        await().ignoreExceptions().untilAsserted(() -> assertThat(sent.frames).last().asString()
                .isEqualTo("CLOSE epoch=7 PROTOCOL_ERROR closed mid-snapshot"));
        assertThat(sent.frames).as("opened in order, the CLOSE included")
                .anyMatch(frame -> frame.startsWith("SNAPSHOT_RECORD"));
    }

    @Test
    void aClearConnectionSendsAPlaintextRecordAsItIs() {
        JournalStream quotes = asyncStream("quotes");
        hello(EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        quotes.appendPlaintext(QUOTE_TEXT);
        fill();

        assertThat(sent.frames).last().asString().endsWith("pos=1 payload=" + QUOTE_TEXT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"clear", "AES/CTR/NoPadding", "ChaCha20"})
    void fillingABatchAllocatesNothingInClearOrWithAStreamCipher(String cipher) {
        double allocated = fillBytesPerRecord(cipher);

        assertThat(allocated).as("bytes per record filled").isLessThan(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/GCM/NoPadding", "ChaCha20-Poly1305"})
    void fillingABatchWithAnAeadAllocatesNoMoreThanTheJceItself(String cipher) {
        double allocated = fillBytesPerRecord(cipher);
        double jce = JceAeadAllocation.bytesPerFrame(cipher, Cipher.ENCRYPT_MODE,
                WireFormat.HEADER_BYTES + FrameType.RECORD.getFixedPayloadBytes(), FILLED_MESSAGE_BYTES);

        assertThat(allocated).as("bytes per record filled, the JCE's %s", jce)
                .isLessThan(jce + JceAeadAllocation.JIT_SLACK_BYTES);
    }

    /**
     * Appends records to the log and fills a batch every few, as an appending thread and the sender would, on this
     * thread.
     */
    private double fillBytesPerRecord(String cipher) {
        asyncStream("main");
        if (cipher.equals("clear")) {
            hello(EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
            fill();
        } else {
            streamingEncrypted(cipher);
        }
        ReplicationLog replicationLog = journal.getLogs().get(0);
        LogRecord record = new LogRecord();
        LogFullWait wait = new LogFullWait(Duration.ofSeconds(1));
        ByteBuffer message = ByteBuffer.wrap(new byte[FILLED_MESSAGE_BYTES]);
        ByteBuffer batch = ByteBuffer.allocateDirect(1 << 16);
        int warmUp = JceAeadAllocation.warmUpFrames(FILLED_MESSAGE_BYTES);

        appendAndFill(replicationLog, record, wait, message, batch, 0, warmUp);
        long before = AllocatedBytes.ofCurrentThread();
        long filled = appendAndFill(replicationLog, record, wait, message, batch, warmUp, JceAeadAllocation.MEASURED);
        long allocated = AllocatedBytes.ofCurrentThread() - before;

        assertThat(filled).as("every record filled").isGreaterThan((long) JceAeadAllocation.MEASURED * FILLED_MESSAGE_BYTES);
        double perRecord = (double) allocated / JceAeadAllocation.MEASURED;
        log.info("Filling with {}: {} bytes per {} byte record", cipher, perRecord, FILLED_MESSAGE_BYTES);
        return perRecord;
    }

    private long appendAndFill(ReplicationLog replicationLog, LogRecord record, LogFullWait wait, ByteBuffer message,
                               ByteBuffer batch, long firstPosition, int records) {
        long filled = 0;
        for (long position = firstPosition; position < firstPosition + records; position++) {
            replicationLog.append(TestingLogFragments.entry(record, 0, position, message), wait);
            if (position % RECORDS_PER_BATCH == RECORDS_PER_BATCH - 1) {
                filled += connection.getSender().fillBatch(batch);
            }
        }
        return filled + connection.getSender().fillBatch(batch);
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
        stream("main");
        streamingEncrypted(cipher);

        connection.close(CloseReason.PROTOCOL_ERROR, "test");

        assertThat(sent.frames).last().asString().isEqualTo("CLOSE epoch=7 PROTOCOL_ERROR test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/CTR/NoPadding", "AES/GCM/NoPadding"})
    void anAckWithoutItsTagIsRefusedOnceTheKeysAreConfirmed(String cipher) {
        stream("main");
        streamingEncrypted(cipher);

        assertThatThrownBy(() -> read(ack(null, 1_000))).isInstanceOf(WireFormatException.class);

        assertThat(connection.getAcks().getAppliedOffset()).isEqualTo(-1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/CTR/NoPadding", "AES/GCM/NoPadding"})
    void aReplayedAckFailsItsTag(String cipher) {
        stream("main");
        streamingEncrypted(cipher);
        ByteBuffer acked = ack(keyExchange.newSealer(connection.getEncryptionKeys().getFollowerToLeader()), 0);
        read(acked.duplicate());
        assertThat(connection.getAcks().getAppliedOffset()).isZero();

        assertThatThrownBy(() -> read(acked.duplicate())).isInstanceOf(WireFormatException.class)
                .hasMessage("ACK fails its authentication tag");
    }
}
