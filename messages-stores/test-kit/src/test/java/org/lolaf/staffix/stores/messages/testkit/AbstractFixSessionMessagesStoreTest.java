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
package org.lolaf.staffix.stores.messages.testkit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract test class for {@link FixMessagesStore.FixSessionMessagesStore} implementations.
 * Concrete test classes should extend this class and implement the required abstract methods
 * to provide their specific session store implementation.
 */
public abstract class AbstractFixSessionMessagesStoreTest<T extends FixMessagesStore.FixSessionMessagesStore> {

    protected T messageStore;
    protected FixSessionId sessionId;
    protected BiPredicate<MessageType, ByteBuffer> messagesFilter;

    /**
     * Create and return a new instance of the session store implementation to be tested.
     * This method is called before each test.
     *
     * @param sessionId the session ID to use for the store
     * @return a new session store instance
     */
    protected abstract T createSessionStore(FixSessionId sessionId, BiPredicate<MessageType, ByteBuffer> messagesFilter) throws Exception;

    /**
     * Optional lifecycle hook called before each test, after the store is created.
     * Override this method to perform additional setup (e.g., database initialization).
     */
    protected void beforeSetUp() throws Exception {
        // Override in subclasses if needed
    }

    /**
     * Optional lifecycle hook called before each test, after the store is created.
     * Override this method to perform additional setup (e.g., database initialization).
     */
    protected void afterSetUp() throws Exception {
        // Override in subclasses if needed
    }

    /**
     * Optional lifecycle hook called after each test, before the store is stopped.
     * Override this method to perform additional cleanup.
     */
    protected void beforeTearDown() throws Exception {
        // Override in subclasses if needed
    }

    /**
     * Optional lifecycle hook called after each test, before the store is stopped.
     * Override this method to perform additional cleanup.
     */
    protected void afterTearDown() throws Exception {
        // Override in subclasses if needed
    }

    @BeforeEach
    final void setUpBase() throws Exception {
        beforeSetUp();
        sessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        messagesFilter = (mt, bb) -> mt.isAdmin();
        messageStore = createSessionStore(sessionId, messagesFilter);
        afterSetUp();
    }

    @AfterEach
    final void tearDownBase() throws Exception {
        beforeTearDown();
        if (messageStore != null) {
            messageStore.stop(Deadline.unlimited());
            messageStore = null;
        }
        afterTearDown();
    }


    @Test
    protected void shouldUnderlyingStorageResourceAvailable() {
        // Given
        messageStore.start();

        // Then
        assertThat(messageStore.isUnderlyingStorageResourceAvailable()).isTrue();
    }

    @Test
    protected void shouldHaveSequenceNumbersStartingWithOneForNewSessionsStores() {
        // Given
        messageStore.start();

        // Then
        assertThat(messageStore.getIncomingSeqNum()).isEqualTo(1L);
        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(1L);
    }

    @Test
    protected void shouldLoadExistingSequenceNumbers() {
        // Given - insert existing state
        messageStore.start();
        messageStore.storeNextIncomingSeqNum(50L);
        messageStore.storeNextOutgoingSeqNum(75L);
        messageStore.stop(Deadline.unlimited());

        // When
        messageStore.start();

        // Then
        assertThat(messageStore.getIncomingSeqNum()).isEqualTo(50L);
        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(75L);
    }

    @Test
    protected void shouldReturnEmptyListForNonExistentMessages() {
        // Given
        messageStore.start();

        // When
        List<FixMessagesStore.FixSessionMessagesStore.StoreMessage> messages = messageStore.find(100L, 200L);

        // Then
        assertThat(messages).isEmpty();
    }

    @Test
    protected void shouldStoreAndRetrieveSingleMessage() {
        // Given
        messageStore.start();

        String messageContent = "test-message";
        ByteBuffer message = ByteBuffer.wrap(messageContent.getBytes(StandardCharsets.UTF_8));

        // When
        messageStore.storeMessageSent(1L, message);

        // Then, using async assertions as store implementation can be asynchronous
        Awaitility.await().untilAsserted(() -> {
            var storedMessages = messageStore.find(1L, 1L);
            assertThat(storedMessages).hasSize(1);
            assertThat(storedMessages.get(0).getSeqNum()).isEqualTo(1L);
            assertMessage(storedMessages, 0, messageContent);
        });
    }

    @Test
    protected void shouldIncrementByOneNextOutgoingMessageAfterCall() {
        // Given
        messageStore.start();

        // Then
        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(1L);

        assertThat(messageStore.getNextOutgoingSeqNum()).isEqualTo(1L);

        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(2L);
    }

    @Test
    protected void shouldIncrementByOneOutgoingSeqNumWhenStoringAMessage() {
        // Given
        messageStore.start();

        // Then
        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(1L);

        // When
        messageStore.storeMessageSent(10L, ByteBuffer.wrap("test".getBytes()));

        // Then
        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(11L);
    }

    @Test
    protected void shouldStoreMultipleMessagesAndRetrieveByRange() {
        // Given
        messageStore.start();

        // When - store 100 messages
        for (long i = 1; i <= 100; i++) {
            String messageContent = "message-" + i;
            messageStore.storeMessageSent(i, ByteBuffer.wrap(messageContent.getBytes(StandardCharsets.UTF_8)));
        }

        // Then retrieve different ranges, using async assertions as store implementation can be asynchronous
        Awaitility.await().untilAsserted(() -> {
            var messages1to10 = messageStore.find(1L, 10L);
            assertThat(messages1to10).hasSize(10);
            assertThat(messages1to10.get(0).getSeqNum()).isEqualTo(1L);
            assertThat(messages1to10.get(9).getSeqNum()).isEqualTo(10L);

            var messages50to60 = messageStore.find(50L, 60L);
            assertThat(messages50to60).hasSize(11);
            assertThat(messages50to60.get(0).getSeqNum()).isEqualTo(50L);
            assertThat(messages50to60.get(10).getSeqNum()).isEqualTo(60L);

            var messages95to100 = messageStore.find(95L, 100L);
            assertThat(messages95to100).hasSize(6);
        });
    }

    @Test
    protected void shouldKeepInitialSequenceNumbersWhenStoreRestarted() {
        // Given
        messageStore.start();

        // Then
        assertThat(messageStore.getIncomingSeqNum()).isEqualTo(1L);
        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(1L);

        // When
        messageStore.stop(Deadline.unlimited()).start();

        assertThat(messageStore.getIncomingSeqNum()).isEqualTo(1L);
        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(1L);
    }

    @Test
    protected void shouldUpdateIncomingSequenceNumber() {
        // Given
        messageStore.start();

        // When
        messageStore.storeNextIncomingSeqNum(42L);

        // Then - check in-memory value
        assertThat(messageStore.getIncomingSeqNum()).isEqualTo(42L);
    }

    @Test
    protected void shouldUpdateOutgoingSequenceNumber() {
        // Given
        messageStore.start();

        // When
        messageStore.storeNextOutgoingSeqNum(99L);

        // Then - check in-memory value
        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(99L);
    }

    @Test
    protected void shouldResetSequenceNumbersAndDeleteMessages() {
        // Given
        messageStore.start();

        // Store some data
        messageStore.storeNextIncomingSeqNum(100L);
        messageStore.storeNextOutgoingSeqNum(200L);
        for (long i = 1; i <= 10; i++) {
            messageStore.storeMessageSent(i, ByteBuffer.wrap(("msg-" + i).getBytes(StandardCharsets.UTF_8)));
        }

        // Then
        assertThat(messageStore.getIncomingSeqNum()).isEqualTo(100L);
        // storeMessageSent() increase the outgoing sequence number so we do not expect 200
        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(11L);

        // When
        messageStore.resetSequenceNumbers();

        // Then - sequence numbers should be reset
        assertThat(messageStore.getIncomingSeqNum()).isEqualTo(1L);
        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(1L);

        // Then - messages should be deleted
        var messages = messageStore.find(1L, 100L);
        assertThat(messages).isEmpty();
    }

    @Test
    protected void shouldHandleEmptyMessageRetrieval() {
        // Given
        messageStore.start();

        // When - retrieve messages that don't exist
        var messages = messageStore.find(100L, 200L);

        // Then
        assertThat(messages).isEmpty();
    }

    @Test
    protected void shouldHandleLargeMessages() {
        // Given
        messageStore.start();

        // Create a large message (64Kb)
        byte[] largeMessageBytes = new byte[64 * 1024];
        for (int i = 0; i < largeMessageBytes.length; i++) {
            largeMessageBytes[i] = (byte) (i % 256);
        }

        // When
        messageStore.storeMessageSent(1L, ByteBuffer.wrap(largeMessageBytes));

        // Then, using async assertions as store implementation can be asynchronous
        Awaitility.await().untilAsserted(() -> {
            var messages = messageStore.find(1L, 1L);

            assertThat(messages).hasSize(1);
            ByteBuffer retrievedMessage = messages.get(0).getMessage();
            byte[] retrievedBytes = new byte[retrievedMessage.remaining()];
            retrievedMessage.get(retrievedBytes);
            assertThat(retrievedBytes).isEqualTo(largeMessageBytes);
        });
    }

    @Test
    protected void shouldMaintainMessageOrderBySequenceNumber() {
        // Given
        messageStore.start();

        // When - store messages in non-sequential order
        messageStore.storeMessageSent(5L, ByteBuffer.wrap("msg5".getBytes(StandardCharsets.UTF_8)));
        messageStore.storeMessageSent(3L, ByteBuffer.wrap("msg3".getBytes(StandardCharsets.UTF_8)));
        messageStore.storeMessageSent(1L, ByteBuffer.wrap("msg1".getBytes(StandardCharsets.UTF_8)));
        messageStore.storeMessageSent(4L, ByteBuffer.wrap("msg4".getBytes(StandardCharsets.UTF_8)));
        messageStore.storeMessageSent(2L, ByteBuffer.wrap("msg2".getBytes(StandardCharsets.UTF_8)));

        // Then, retrieve should be in order, using async assertions as store implementation can be asynchronous
        Awaitility.await().untilAsserted(() -> {
            var messages = messageStore.find(1L, 5L);
            assertThat(messages).hasSize(5);
            for (int i = 0; i < 5; i++) {
                assertThat(messages.get(i).getSeqNum()).isEqualTo(i + 1L);
                assertThat(StandardCharsets.UTF_8.decode(messages.get(i).getMessage()).toString())
                        .hasToString("msg" + (i + 1));
            }
        });
    }

    @Test
    protected void shouldPersistDataAcrossStoreRestarts() throws Exception {
        // Given
        messageStore.start();

        messageStore.storeNextIncomingSeqNum(555L);
        messageStore.storeNextOutgoingSeqNum(666L);
        for (long i = 1; i <= 5; i++) {
            messageStore.storeMessageSent(i, ByteBuffer.wrap(("persistent-msg-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        messageStore.stop(Deadline.unlimited());

        // When - create new store instance
        messageStore = createSessionStore(sessionId, messagesFilter);
        messageStore.start();

        // Then - data should be persisted
        assertThat(messageStore.getIncomingSeqNum()).isEqualTo(555L);
        // storeMessageSent() increase the outgoing sequence number so we do not expect 666
        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(6L);

        var messages = messageStore.find(1L, 5L);
        assertThat(messages).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(messages.get(i).getSeqNum()).isEqualTo(i + 1L);
        }

        messages = messageStore.find(1L, 10L);
        assertThat(messages).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(messages.get(i).getSeqNum()).isEqualTo(i + 1L);
        }
    }

    @Test
    protected void testBatchingInsertMessage() {
        if (!(messageStore instanceof FixMessagesStore.BatchingFixSessionMessagesStore)) {
            return;
        }

        // Given
        messageStore.start();

        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(1L);

        // When - batch insert messages
        FixMessagesStore.BatchingFixSessionMessagesStore batchingStore =
                (FixMessagesStore.BatchingFixSessionMessagesStore) messageStore;

        batchingStore.storeSentFixMessages(
                new FixMessagesStore.BatchingFixSessionMessagesStore.SentFixMessage[]{
                        new TestingSentFixMessage(3L, ByteBuffer.wrap("message3".getBytes())),
                        new TestingSentFixMessage(4L, ByteBuffer.wrap("message4".getBytes())),
                        new TestingSentFixMessage(5L, ByteBuffer.wrap("message5".getBytes())),
                        new TestingSentFixMessage(6L, ByteBuffer.wrap("message6".getBytes()))
                }, 4);

        List<FixMessagesStore.FixSessionMessagesStore.StoreMessage> messages = messageStore.find(4L, 5L);
        assertThat(messages).hasSize(2);
        assertMessage(messages, 0, "message4");
        assertMessage(messages, 1, "message5");

        batchingStore.storeSentFixMessages(
                new FixMessagesStore.BatchingFixSessionMessagesStore.SentFixMessage[]{
                        new TestingSentFixMessage(7L, ByteBuffer.wrap("message7".getBytes())),
                        new TestingSentFixMessage(8L, ByteBuffer.wrap("message8".getBytes()))
                }, 2);

        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(1L);

        messageStore.stop(Deadline.immediate()).start();

        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(1L);

        messages = messageStore.find(1L, 10L);
        assertThat(messages).hasSize(6);
        assertMessage(messages, 0, "message3");
        assertMessage(messages, 1, "message4");
        assertMessage(messages, 2, "message5");
        assertMessage(messages, 3, "message6");
        assertMessage(messages, 4, "message7");
        assertMessage(messages, 5, "message8");

        messages = messageStore.find(9L, 10L);
        assertThat(messages).isEmpty();

    }

    @Test
    protected void shouldHandleMessageFiltering() {
        // Given - store with custom filter
        messageStore.start();

        // Test with admin and non-admin message types, admin message should only be allowed
        assertThat(messageStore.filter(MessageType.of("a", false), ByteBuffer.allocate(10))).isFalse();
        assertThat(messageStore.filter(MessageType.of("b", true), ByteBuffer.allocate(10))).isTrue();

    }

    protected void assertMessage(List<FixMessagesStore.FixSessionMessagesStore.StoreMessage> messages,
                                 int index, String expectedContent) {
        ByteBuffer message = messages.get(index).getMessage();
        byte[] msgBytes = message.array();
        assertThat(new String(msgBytes, 0, message.limit())).isEqualTo(expectedContent);
    }

    /**
     * Helper class for testing batching functionality
     */
    protected static class TestingSentFixMessage implements FixMessagesStore.BatchingFixSessionMessagesStore.SentFixMessage {
        private final long sequenceNumber;
        private final ByteBuffer message;

        public TestingSentFixMessage(long sequenceNumber, ByteBuffer message) {
            this.sequenceNumber = sequenceNumber;
            this.message = message;
        }

        @Override
        public long getSequenceNumber() {
            return sequenceNumber;
        }

        @Override
        public ByteBuffer getMessage() {
            return message;
        }
    }
}
