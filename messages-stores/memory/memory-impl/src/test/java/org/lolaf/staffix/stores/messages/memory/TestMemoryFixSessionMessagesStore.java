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
package org.lolaf.staffix.stores.messages.memory;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.stores.messages.testkit.AbstractFixSessionMessagesStoreTest;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for {@link MemoryFixSessionMessagesStore} using the generic test kit.
 */
class TestMemoryFixSessionMessagesStore extends AbstractFixSessionMessagesStoreTest<MemoryFixSessionMessagesStore> {

    private MemoryMessageStoreSettings settings;

    @Override
    protected MemoryFixSessionMessagesStore createSessionStore(FixSessionId sessionId, BiPredicate<MessageType, ByteBuffer> messagesFilter) {
        if (super.messageStore != null) {
            return messageStore;
        }
        settings = MemoryMessageStoreSettings.builder()
                .useDirectMemory(false)
                .messageFilter(messagesFilter)
                .build();
        return new MemoryFixSessionMessagesStore(settings);
    }

    @Test
    protected void shouldIncrementOutgoingSequenceNumberWhenDisabled() {
        setupDisabledStore(0);
        messageStore.start();

        // Given
        messageStore.storeMessageSent(1, ByteBuffer.wrap(("test-message").getBytes()));

        // Then
        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(2L);

    }

    private void setupDisabledStore(int maxEntriesInMemory) {
        settings = MemoryMessageStoreSettings.builder()
                .maxEntriesInMemory(maxEntriesInMemory)
                .useDirectMemory(false)
                .messageFilter(messagesFilter)
                .build();
        messageStore = new MemoryFixSessionMessagesStore(settings);
    }

    @Test
    protected void shouldOnlyTrackSequenceNumbersWhenDisabled() {
        setupDisabledStore(0);
        messageStore.start();

        // Given
        messageStore.storeMessageSent(1, ByteBuffer.wrap(("test-message").getBytes()));

        // When
        List<FixMessagesStore.FixSessionMessagesStore.StoreMessage> messages = messageStore.find(1, 2);
        // Then
        assertThat(messages).isEmpty();
        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(2L);

        messageStore.storeNextIncomingSeqNum(555L);
        messageStore.storeNextOutgoingSeqNum(666L);

        assertThat(messageStore.getIncomingSeqNum()).isEqualTo(555L);
        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(666L);

        messageStore.resetSequenceNumbers();

        assertThat(messageStore.getIncomingSeqNum()).isEqualTo(1L);
        assertThat(messageStore.getOutgoingSeqNum()).isEqualTo(1L);
    }

    @Test
    protected void shouldReuseBufferWhenRollingMessages() {
        setupDisabledStore(4);
        messageStore.start();

        // Given
        for (int i = 1; i <= 4; i++) {
            messageStore.storeMessageSent(i, ByteBuffer.wrap(("test-long-message" + i).getBytes()));
        }
        // When
        List<FixMessagesStore.FixSessionMessagesStore.StoreMessage> messages = messageStore.find(1, 4);
        // Then
        assertThat(messages).hasSize(4);
        for (int i = 1; i <= 4; i++) {
            assertMessage(messages, i - 1, "test-long-message" + i);
        }

        // Given
        for (int i = 1; i <= 4; i++) {
            messageStore.storeMessageSent(i, ByteBuffer.wrap(("test-message" + i).getBytes()));
        }
        // When
        messages = messageStore.find(1, 4);
        // Then
        assertThat(messages).hasSize(4);
        for (int i = 1; i <= 4; i++) {
            assertMessage(messages, i - 1, "test-message" + i);
        }

        // Given
        for (int i = 1; i <= 4; i++) {
            messageStore.storeMessageSent(i, ByteBuffer.wrap(("test-event-longer-message" + i).getBytes()));
        }
        // When
        messages = messageStore.find(1, 4);
        // Then
        assertThat(messages).hasSize(4);
        for (int i = 1; i <= 4; i++) {
            assertMessage(messages, i - 1, "test-event-longer-message" + i);
        }
    }
}