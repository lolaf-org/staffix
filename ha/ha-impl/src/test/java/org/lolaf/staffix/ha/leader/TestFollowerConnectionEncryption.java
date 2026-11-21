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
import com.github.valfirst.slf4jtest.TestLogger;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.ha.FixEngineHa.ReplicatingStore;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.serde.Hashing;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreMessagesConsumer;
import org.lolaf.staffix.ha.EncryptionMode;
import org.lolaf.staffix.ha.follower.RecordingProbes;
import org.lolaf.staffix.ha.log.TestingLogFragments;
import org.lolaf.staffix.ha.encryption.KeyExchange;
import org.lolaf.staffix.ha.encryption.TestingKeyExchanges;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.FrameType;
import org.lolaf.staffix.ha.wire.SessionAnswer;
import org.lolaf.staffix.ha.wire.WireFormat;
import org.slf4j.event.Level;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * The leader's side of the encryption handshake: the rows of the behaviour table it decides, and the KEY_CONFIRMATION it
 * waits for before anything streams.
 */
class TestFollowerConnectionEncryption extends FollowerConnectionHarness {

    private static final MessageType QUOTE = MessageType.of("S", false);
    private static final MessageType ORDER = MessageType.of("D", false);
    private static final String QUOTE_TEXT = "8=FIX.4.4\u00019=5\u000135=S\u0001quote-text\u000110=000\u0001";
    private static final String ORDER_TEXT = "8=FIX.4.4\u00019=5\u000135=D\u0001order-text\u000110=000\u0001";

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
        leaderEncryption(EncryptionMode.REQUIRED);
        KeyExchange follower = TestingKeyExchanges.of(EncryptionMode.REQUIRED);
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
        ByteBuffer hello = ByteBuffer.allocate(FrameEncoder.bytesWith(FrameType.HELLO, "follower-b"));
        int helloLength = FrameEncoder.hello(hello, 0, EPOCH, "follower-b", 0, -1, 0, EncryptionMode.REQUIRED, followerNonce);
        ByteBuffer welcome = ByteBuffer.allocate(FrameEncoder.bytesWith(FrameType.WELCOME, "leader-a"));
        int welcomeLength = FrameEncoder.welcome(welcome, 0, EPOCH, "leader-a", WireFormat.NO_RESUME, 1,
                EncryptionMode.REQUIRED, leaderNonce);
        byte[] helloBytes = new byte[helloLength];
        hello.get(helloBytes);
        byte[] welcomeBytes = new byte[welcomeLength];
        welcome.get(welcomeBytes);
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
                .contains("Replicating encrypted to follower follower-b lane 0, AES/GCM/NoPadding 256");
    }

    @Test
    void aKeyConfirmationOfAnotherSecretClosesWithKeyMismatch() {
        leaderEncryption(EncryptionMode.REQUIRED);
        KeyExchange follower = TestingKeyExchanges.of(EncryptionMode.REQUIRED, "another-secret-entirely",
                "AES/GCM/NoPadding");
        byte[] followerNonce = follower.newNonce();
        hello(EncryptionMode.REQUIRED, followerNonce);

        connection.onKeyConfirmation(EPOCH, followerConfirmation(follower, followerNonce));

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 KEY_MISMATCH the follower's secret or cipher "
                + "differ from this leader's AES/GCM/NoPadding 256");
        assertThat(errors()).isEqualTo(1);
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
    void aClearConnectionSendsEveryTypeAsItIsWithoutTheUnencryptedFlag() {
        ReplicatingStore quotes = session("quotes", "S");
        hello(EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);

        quotes.storeMessageSent(QUOTE, 1, ascii(QUOTE_TEXT));
        fill();

        assertThat(sent.frames).last().asString().endsWith("seq=1 message=" + QUOTE_TEXT);
    }
}
