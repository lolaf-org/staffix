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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreMessagesConsumer;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.replication.wire.WireFormat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestFixSessionSnapshotSource {

    private final FixSessionMessagesStore store = mock(FixSessionMessagesStore.class);
    private final FixSessionSnapshotSource source = new FixSessionSnapshotSource(store,
            UnencryptedMessageTypes.of(List.of("S")));

    @Test
    void theStateIsBothSeqNumsAndCoversUpToTheLastOutgoingOne() {
        when(store.getIncomingSeqNum()).thenReturn(21L);
        when(store.getOutgoingSeqNum()).thenReturn(13L);
        ByteBuffer state = ByteBuffer.allocate(WireFormat.MAX_STATE_BYTES);

        assertThat(source.captureState(state)).isEqualTo(12);

        assertThat(state.position()).isEqualTo(FixSessionSnapshotSource.STATE_BYTES);
        assertThat(state.getLong(0)).isEqualTo(21);
        assertThat(state.getLong(Long.BYTES)).isEqualTo(13);
    }

    @Test
    void aReadFromZeroStartsAtTheFirstSeqNumAndAnEmptyRangeReadsNothing() {
        source.read(0, 0, (contentType, flags, position, payload) -> true);
        verify(store, never()).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));

        source.read(0, 5, (contentType, flags, position, payload) -> true);
        verify(store).find(eq(1L), eq(5L), any(StoreMessagesConsumer.class));
    }

    @Test
    void aMessageIsAStoreMessageRecordFlaggedPlaintextWhenItsTypeGoesUnencrypted() {
        doAnswer(invocation -> {
            StoreMessagesConsumer consumer = invocation.getArgument(2);
            return consumer.onMessage(1, ascii("8=FIX.4.4\u00019=5\u000135=S\u000110=000\u0001"))
                    && consumer.onMessage(2, ascii("8=FIX.4.4\u00019=5\u000135=D\u000110=000\u0001"));
        }).when(store).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));
        List<String> records = new ArrayList<>();

        source.read(1, 2, (contentType, flags, position, payload) -> records.add(contentType + " " + flags + " " + position));

        assertThat(records).containsExactly(StoreEventType.STORE_MESSAGE.getCode() + " " + WireFormat.PLAINTEXT + " 1",
                StoreEventType.STORE_MESSAGE.getCode() + " 0 2");
    }

    private static ByteBuffer ascii(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }
}
