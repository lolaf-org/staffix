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
package org.lolaf.staffix.stores.messages.async;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.stores.core.async.AsyncStoreSettings;
import org.lolaf.staffix.stores.core.async.StoreUnderlyingResourceStateListener;
import org.lolaf.staffix.tests.TestingFixMessagesStoreSettings;
import org.mockito.Mockito;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TestAsyncMessagesStore {

    private static final String TEST_MESSAGE_STORE_DESCRIPTION = "test message store description";

    AsyncMessagesStore asyncMessagesStore;
    AsyncMessageStore asyncMessageStore;
    FixMessagesStore.FixSessionMessagesStore messageStore;
    FixSessionId fixSessionId;
    AsyncMessagesStoreSettings asyncMessagesStoreSettings;
    ByteBuffer testMessage;
    BiPredicate<MessageType, ByteBuffer> messageFilter;
    StoreUnderlyingResourceStateListener storeUnderlyingResourceStateListener;

    private static FixMessagesStore.BatchingFixSessionMessagesStore getBatchingFixSessionMessagesStoreMock() {
        FixMessagesStore.BatchingFixSessionMessagesStore batchingFixSessionMessagesStore = mock(FixMessagesStore.BatchingFixSessionMessagesStore.class);
        when(batchingFixSessionMessagesStore.isUnderlyingStorageResourceAvailable()).thenReturn(true);
        return batchingFixSessionMessagesStore;
    }

    @BeforeEach
    void setup() {
        storeUnderlyingResourceStateListener = mock(StoreUnderlyingResourceStateListener.class);
        testMessage = ByteBuffer.wrap("test".getBytes());
        fixSessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "sender", "target");
        messageStore = mock(FixMessagesStore.FixSessionMessagesStore.class);
        when(messageStore.isUnderlyingStorageResourceAvailable()).thenReturn(true);
        when(messageStore.getUnderlyingStorageResourceDescription()).thenReturn(TEST_MESSAGE_STORE_DESCRIPTION);
        when(messageStore.filter(any(), any())).thenAnswer(invocation -> messageFilter.test(invocation.getArgument(0), invocation.getArgument(1)));
        messageFilter = mock(BiPredicate.class);
        when(messageFilter.test(any(), any())).thenReturn(false);
        asyncMessagesStoreSettings = AsyncMessagesStoreSettings.builder()
                .asyncStoreSettings(AsyncStoreSettings.builder()
                        .asyncQueueDirectory("./target/" + System.currentTimeMillis())
                        .storeUnderlyingResourceStateListener(storeUnderlyingResourceStateListener)
                        .build())
                .wrappedFixMessagesStoreSettings(TestingFixMessagesStoreSettings.builder()
                        .testingFixSessionMessagesStore(messageStore)
                        .messageFilter(messageFilter)
                        .build())
                .build();
        asyncMessagesStore = new AsyncMessagesStore(asyncMessagesStoreSettings);
        asyncMessagesStore.start();

        asyncMessageStore = (AsyncMessageStore) asyncMessagesStore.getStore(fixSessionId);
    }

    @Test
    void testSequencesAreRetrievedWhenStoreIsStarted() {
        when(messageStore.getIncomingSeqNum()).thenReturn(1234L);
        when(messageStore.getOutgoingSeqNum()).thenReturn(4321L);

        assertThat(asyncMessageStore.getIncomingSeqNum()).isEqualTo(1L);
        assertThat(asyncMessageStore.getOutgoingSeqNum()).isEqualTo(1L);

        asyncMessageStore.start();

        assertThat(asyncMessageStore.getIncomingSeqNum()).isEqualTo(1234L);
        assertThat(asyncMessageStore.getOutgoingSeqNum()).isEqualTo(4321L);
    }

    @Test
    void testStartWithUnavailableUnderlyingResource() {
        when(messageStore.isUnderlyingStorageResourceAvailable()).thenReturn(false);

        assertThatThrownBy(() -> asyncMessageStore.start())
                .isInstanceOf(Startable.StartStopException.class)
                .hasMessageStartingWith("Underlying messages storage resource 'test message store description' is not available for FIX session");
    }

    @Test
    void testMessageStorageWithFailureDueToUnderlyingResourceDown() {
        asyncMessageStore.start();

        verify(messageStore).isUnderlyingStorageResourceAvailable();
        verify(storeUnderlyingResourceStateListener).onStoreStateUp(fixSessionId, TEST_MESSAGE_STORE_DESCRIPTION);

        asyncMessageStore.storeMessageSent(1L, testMessage);

        await().untilAsserted(() -> verify(messageStore).storeMessageSent(1L, testMessage));

        when(messageStore.isUnderlyingStorageResourceAvailable()).thenReturn(false);
        doThrow(new IllegalStateException("test exception")).when(messageStore).storeMessageSent(anyLong(), any());

        ByteBuffer testMessage2 = ByteBuffer.wrap("test2".getBytes());

        asyncMessageStore.storeMessageSent(2L, testMessage2);

        await().untilAsserted(() -> verify(messageStore).storeMessageSent(2L, testMessage2));

        // the watchdog is started from the catch around the failing storeMessageSent call, so seeing that call does
        // not mean it is up yet
        await().untilAsserted(() -> assertThat(asyncMessageStore.hasInactiveUnderlyingResourceWatchDog()).isFalse());
        // awaited: the listener is told the resource is down after the write that discovered it, which is what the
        // await above waits for
        await().untilAsserted(() -> verify(storeUnderlyingResourceStateListener)
                .onStoreStateDown(fixSessionId, TEST_MESSAGE_STORE_DESCRIPTION));

        reset(messageStore);
        reset(storeUnderlyingResourceStateListener);
        when(messageStore.isUnderlyingStorageResourceAvailable()).thenReturn(true);
        await().untilAsserted(() -> verify(messageStore).storeMessageSent(2L, testMessage2));
        // and symmetrically it stops itself only after replaying, in UnderlyingResourceWatchContext
        // .checkUnderlyingResourceState, so the replay just observed does not mean it is down yet
        await().untilAsserted(() -> assertThat(asyncMessageStore.hasInactiveUnderlyingResourceWatchDog()).isTrue());
        // awaited, for the same reason as the down notification above
        await().untilAsserted(() -> verify(storeUnderlyingResourceStateListener)
                .onStoreStateUp(fixSessionId, TEST_MESSAGE_STORE_DESCRIPTION));
    }

    @Test
    void testBatchingWithFailureDueToUnderlyingResourceDown() {
        asyncMessageStore.stop(Deadline.immediate());

        FixMessagesStore.BatchingFixSessionMessagesStore batchingFixSessionMessagesStore = getBatchingFixSessionMessagesStoreMock();

        asyncMessagesStoreSettings = asyncMessagesStoreSettings.toBuilder()
                .asyncStoreSettings(asyncMessagesStoreSettings.getAsyncStoreSettings().toBuilder()
                        .eventsBatching(2)
                        .batchingFlushInterval(Duration.ofSeconds(1))
                        .build())
                .wrappedFixMessagesStoreSettings(TestingFixMessagesStoreSettings.builder()
                        .testingFixSessionMessagesStore(batchingFixSessionMessagesStore)
                        .build())
                .build();

        asyncMessagesStore = new AsyncMessagesStore(asyncMessagesStoreSettings);
        asyncMessagesStore.start();
        asyncMessageStore = (AsyncMessageStore) asyncMessagesStore.getStore(fixSessionId);
        asyncMessageStore.start();

        verify(batchingFixSessionMessagesStore).isUnderlyingStorageResourceAvailable();

        asyncMessageStore.storeMessageSent(1L, testMessage);

        assertBatchingMessageProcessed(1, 2, 1, batchingFixSessionMessagesStore, testMessage);

        reset(batchingFixSessionMessagesStore);
        when(batchingFixSessionMessagesStore.isUnderlyingStorageResourceAvailable()).thenReturn(false);
        Mockito.doThrow(new IllegalStateException("test exception")).when(batchingFixSessionMessagesStore).storeSentFixMessages(any(), anyInt());

        ByteBuffer testMessage2 = ByteBuffer.wrap("test message2".getBytes());
        asyncMessageStore.storeMessageSent(1L, testMessage2);

        assertBatchingMessageProcessed(1, 2, 1, batchingFixSessionMessagesStore, testMessage2);
        // the watchdog is started from the catch around the failing storeSentFixMessages call, so seeing that call
        // does not mean it is up yet
        await().untilAsserted(() -> assertThat(asyncMessageStore.hasInactiveUnderlyingResourceWatchDog()).isFalse());

        when(batchingFixSessionMessagesStore.isUnderlyingStorageResourceAvailable()).thenReturn(true);
        assertBatchingMessageProcessed(2, 2, 1, batchingFixSessionMessagesStore, testMessage2);
        // and symmetrically it stops itself only after replaying, in UnderlyingResourceWatchContext
        // .checkUnderlyingResourceState, so the replay just observed does not mean it is down yet
        await().untilAsserted(() -> assertThat(asyncMessageStore.hasInactiveUnderlyingResourceWatchDog()).isTrue());
    }

    @Test
    void testBatchingCorrectlyUpdateOutgoingSequenceNumbers() {
        asyncMessagesStore.stop(Deadline.immediate());

        FixMessagesStore.BatchingFixSessionMessagesStore batchingFixSessionMessagesStore = getBatchingFixSessionMessagesStoreMock();

        asyncMessagesStoreSettings = asyncMessagesStoreSettings.toBuilder()
                .asyncStoreSettings(asyncMessagesStoreSettings.getAsyncStoreSettings().toBuilder()
                        .eventsBatching(10)
                        .batchingFlushInterval(Duration.ofSeconds(1))
                        .build())
                .wrappedFixMessagesStoreSettings(TestingFixMessagesStoreSettings.builder()
                        .testingFixSessionMessagesStore(batchingFixSessionMessagesStore)
                        .build())
                .build();

        asyncMessagesStore = new AsyncMessagesStore(asyncMessagesStoreSettings);
        asyncMessagesStore.start();
        asyncMessageStore = (AsyncMessageStore) asyncMessagesStore.getStore(fixSessionId);
        asyncMessageStore.start();

        asyncMessageStore.storeMessageSent(1L, testMessage);
        asyncMessageStore.storeNextOutgoingSeqNum(2L);

        // storeNextOutgoingSeqNum should be used to save nextOutgoingSeqNum
        await().untilAsserted(() -> verify(batchingFixSessionMessagesStore, times(1)).storeNextOutgoingSeqNum(anyLong()));
        await().untilAsserted(() -> verify(batchingFixSessionMessagesStore).storeNextOutgoingSeqNum(2L));
        assertBatchingMessageProcessed(1, 10, 1, batchingFixSessionMessagesStore, testMessage);

        Mockito.reset(batchingFixSessionMessagesStore);
        when(batchingFixSessionMessagesStore.isUnderlyingStorageResourceAvailable()).thenReturn(true);

        asyncMessageStore.storeNextOutgoingSeqNum(2L);
        asyncMessageStore.storeMessageSent(3L, testMessage);
        asyncMessageStore.storeMessageSent(4L, testMessage);

        // storeMessageSent should be used to save nextOutgoingSeqNum and increment OutgoingSeqNum sequence number saved by one
        await().untilAsserted(() -> verify(batchingFixSessionMessagesStore, times(1)).storeNextOutgoingSeqNum(anyLong()));
        await().untilAsserted(() -> verify(batchingFixSessionMessagesStore).storeNextOutgoingSeqNum(5L));
        assertBatchingMessageProcessed(1, 10, 2, batchingFixSessionMessagesStore, testMessage);
    }

    @Test
    void testBatching() {
        asyncMessagesStore.stop(Deadline.immediate());

        FixMessagesStore.BatchingFixSessionMessagesStore batchingFixSessionMessagesStore = getBatchingFixSessionMessagesStoreMock();

        asyncMessagesStoreSettings = asyncMessagesStoreSettings.toBuilder()
                .asyncStoreSettings(asyncMessagesStoreSettings.getAsyncStoreSettings().toBuilder()
                        .eventsBatching(10)
                        .batchingFlushInterval(Duration.ofSeconds(1))
                        .build())
                .wrappedFixMessagesStoreSettings(TestingFixMessagesStoreSettings.builder()
                        .testingFixSessionMessagesStore(batchingFixSessionMessagesStore)
                        .build())
                .build();

        asyncMessagesStore = new AsyncMessagesStore(asyncMessagesStoreSettings);
        asyncMessagesStore.start();
        asyncMessageStore = (AsyncMessageStore) asyncMessagesStore.getStore(fixSessionId);
        asyncMessageStore.start();

        asyncMessageStore.resetSequenceNumbers();
        asyncMessageStore.storeMessageSent(1L, testMessage);
        for (int i = 0; i < 5; i++) {
            asyncMessageStore.storeNextOutgoingSeqNum(i);
        }
        for (int i = 0; i < 3; i++) {
            asyncMessageStore.storeNextIncomingSeqNum(i);
        }

        await().untilAsserted(() -> verify(batchingFixSessionMessagesStore, times(1)).storeNextOutgoingSeqNum(anyLong()));
        await().untilAsserted(() -> verify(batchingFixSessionMessagesStore).storeNextOutgoingSeqNum(4L));
        await().untilAsserted(() -> verify(batchingFixSessionMessagesStore, times(1)).storeNextIncomingSeqNum(anyLong()));
        await().untilAsserted(() -> verify(batchingFixSessionMessagesStore).storeNextIncomingSeqNum(2L));
        await().untilAsserted(() -> verify(batchingFixSessionMessagesStore).resetSequenceNumbers());
        assertBatchingMessageProcessed(1, 10, 1, batchingFixSessionMessagesStore, testMessage);

        reset(batchingFixSessionMessagesStore);
        for (int i = 0; i < 5; i++) {
            asyncMessageStore.storeMessageSent(i, testMessage);
        }
        await().untilAsserted(() -> verify(batchingFixSessionMessagesStore).storeSentFixMessages(any(), eq(5)));

        reset(batchingFixSessionMessagesStore);
        for (int i = 0; i < 14; i++) {
            asyncMessageStore.storeMessageSent(i, testMessage);
        }

        await().untilAsserted(() -> verify(batchingFixSessionMessagesStore).storeSentFixMessages(any(), eq(10)));
        await().untilAsserted(() -> verify(batchingFixSessionMessagesStore).storeSentFixMessages(any(), eq(4)));
    }

    @Test
    void testRestartWithPendingMessageWillReprocessThem() {
        asyncMessageStore.start();
        for (int i = 0; i < 32 * 1024; i++) {
            asyncMessageStore.storeMessageSent(i, testMessage);
        }

        asyncMessageStore.stop(Deadline.immediate());

        assertThat(asyncMessageStore.hasEmptyQueue()).isFalse();

        asyncMessageStore.start();

        assertThat(asyncMessageStore.hasEmptyQueue()).isTrue();
    }

    @Test
    void testStopWithImmediateFlushDeadline() {
        asyncMessageStore.start();
        for (int i = 0; i < 32 * 1024; i++) {
            asyncMessageStore.storeMessageSent(i, testMessage);
        }

        asyncMessageStore.stop(Deadline.immediate());

        assertThat(asyncMessageStore.hasEmptyQueue()).isFalse();
    }

    @Test
    void testStopWithNonFlushDeadline() {
        asyncMessageStore.start();
        for (int i = 0; i < 32 * 1024; i++) {
            asyncMessageStore.storeMessageSent(i, testMessage);
        }

        asyncMessageStore.stop(Deadline.unlimited());

        assertThat(asyncMessageStore.hasEmptyQueue()).isTrue();
        assertThat(asyncMessageStore.getQueuePollsCount()).isEqualTo(32 * 1024);

        asyncMessageStore.start();
        for (int i = 0; i < 32 * 1024; i++) {
            asyncMessageStore.storeMessageSent(i, testMessage);
        }

        asyncMessageStore.stop(Deadline.of(Duration.ofSeconds(10)));

        assertThat(asyncMessageStore.hasEmptyQueue()).isTrue();
        assertThat(asyncMessageStore.getQueuePollsCount()).isEqualTo(32 * 1024);

        asyncMessageStore.start();
        for (int i = 0; i < 32 * 1024; i++) {
            asyncMessageStore.storeMessageSent(i, testMessage);
        }

        asyncMessageStore.stop(Deadline.of(Duration.ofMillis(2)));

        assertThat(asyncMessageStore.hasEmptyQueue()).isFalse();
        assertThat(asyncMessageStore.getQueuePollsCount()).isGreaterThan(128);
        // unless we have a blazing fast test env it should work
        assertThat(asyncMessageStore.getQueuePollsCount()).isLessThan(8 * 1024);
    }

    private void assertBatchingMessageProcessed(int times, int sentFixMessageArraySize, int sentFixMessageCount, FixMessagesStore.BatchingFixSessionMessagesStore batchingLogger, ByteBuffer message) {
        await().untilAsserted(() -> verify(batchingLogger, times(times))
                .storeSentFixMessages(assertArg((Consumer<FixMessagesStore.BatchingFixSessionMessagesStore.SentFixMessage[]>) sentFixMessages -> {
                    assertThat(sentFixMessages).hasSize(sentFixMessageArraySize);
                    FixMessagesStore.BatchingFixSessionMessagesStore.SentFixMessage sentFixMessage = sentFixMessages[0];
                    assertThat(sentFixMessage).isNotNull();
                    assertThat(sentFixMessage.getMessage()).isEqualTo(message);
                    assertThat(sentFixMessage.getMessage().position()).isZero();
                    for (int i = 1; i < sentFixMessageCount; i++) {
                        assertThat(sentFixMessages[i]).isNotNull();
                    }
                }), eq(sentFixMessageCount)));
    }

    @AfterEach
    void shutdown() {
        asyncMessageStore.stop(Deadline.immediate());
        asyncMessagesStore.stop(Deadline.immediate());
    }

    @Test
    void testFilterIsWorking() {
        asyncMessageStore.start();

        Assertions.assertThat(asyncMessageStore.filter(MessageType.of("A", false), ByteBuffer.allocate(1))).isFalse();

        when(messageFilter.test(any(), any())).thenReturn(true);

        Assertions.assertThat(asyncMessageStore.filter(MessageType.of("A", false), ByteBuffer.allocate(1))).isTrue();
    }

    @Test
    void testFixMessageProcessing() {
        asyncMessageStore.start();

        ByteBuffer tst = ByteBuffer.wrap("test".getBytes());

        asyncMessageStore.storeMessageSent(1, tst);

        await().untilAsserted(() -> verify(messageStore).storeMessageSent(1, tst));
    }

    @Test
    void testResetSequence() {
        asyncMessageStore.start();

        asyncMessageStore.resetSequenceNumbers();

        await().untilAsserted(() -> verify(messageStore).resetSequenceNumbers());
    }

    @Test
    void testStoreOutgoingSequenceNumber() {
        asyncMessageStore.start();

        asyncMessageStore.storeNextOutgoingSeqNum(1234L);

        await().untilAsserted(() -> verify(messageStore).storeNextOutgoingSeqNum(1234L));
    }

    @Test
    void testStoreIncomingSequenceNumber() {
        asyncMessageStore.start();

        asyncMessageStore.storeNextIncomingSeqNum(4321L);

        await().untilAsserted(() -> verify(messageStore).storeNextIncomingSeqNum(4321L));
    }

}