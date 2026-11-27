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
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreMessagesConsumer;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.replicos.spi.RecordFlag;
import org.lolaf.replicos.spi.WireFormatException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestFixSessionReplica {

    private static HaSessionStatus describe(FixSessionReplica replica) {
        HaSessionStatus.HaSessionStatusBuilder status = HaSessionStatus.builder();
        replica.describe(status);
        return status.build();
    }

    /**
     * A store holding {@code messages} by seqnum, its next outgoing seqnum past the last.
     */
    private static FixSessionMessagesStore storeHolding(Map<Long, String> messages) {
        NavigableMap<Long, String> bySeqNum = new TreeMap<>(messages);
        FixSessionMessagesStore store = mock(FixSessionMessagesStore.class);
        when(store.getOutgoingSeqNum()).thenReturn(bySeqNum.isEmpty() ? 1 : bySeqNum.lastKey() + 3);
        doAnswer(invocation -> {
            StoreMessagesConsumer consumer = invocation.getArgument(2);
            for (Map.Entry<Long, String> entry : bySeqNum.subMap(invocation.getArgument(0), true,
                    invocation.getArgument(1), true).entrySet()) {
                if (!consumer.onMessage(entry.getKey(), ascii(entry.getValue()))) {
                    break;
                }
            }
            return null;
        }).when(store).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));
        return store;
    }

    private static ByteBuffer ascii(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static List<String> readLast(FixSessionReplica replica, int records) {
        List<String> read = new ArrayList<>();
        replica.readLast(records, (contentType, position, buffer, index, length) -> {
            byte[] bytes = new byte[length];
            buffer.duplicate().position(index).get(bytes);
            read.add(StoreEventType.from(contentType) + " " + position + " " + new String(bytes, StandardCharsets.US_ASCII));
            return true;
        });
        return read;
    }

    @Test
    void theLastMessagesAreReadBackAcrossTheSeqNumsAdminMessagesLeftOut() {
        Map<Long, String> messages = new TreeMap<>();
        for (long seqNum = 2; seqNum <= 40; seqNum += 2) {
            messages.put(seqNum, "message " + seqNum);
        }
        FixSessionReplica replica = new FixSessionReplica(storeHolding(messages), "alpha");

        assertThat(readLast(replica, 3)).containsExactly("STORE_MESSAGE 36 message 36", "STORE_MESSAGE 38 message 38",
                "STORE_MESSAGE 40 message 40");
    }

    @Test
    void aStoreHoldingFewerMessagesHandsThemAll() {
        FixSessionReplica replica = new FixSessionReplica(storeHolding(Map.of(2L, "two", 5L, "five")), "alpha");

        assertThat(readLast(replica, 16)).containsExactly("STORE_MESSAGE 2 two", "STORE_MESSAGE 5 five");
    }

    @Test
    void anEmptyStoreHandsNothing() {
        FixSessionMessagesStore store = storeHolding(Map.of());

        assertThat(readLast(new FixSessionReplica(store, "alpha"), 16)).isEmpty();
        verify(store, never()).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));
    }

    @Test
    void aStoreWithWritesPendingHandsNothingAndTheSessionIsCopiedWhole() {
        FixSessionMessagesStore store = storeHolding(Map.of(2L, "two"));
        when(store.getPendingWrites()).thenReturn(1L);

        assertThat(readLast(new FixSessionReplica(store, "alpha"), 16)).isEmpty();
    }

    @Test
    void aSnapshotFromALaterSeqNumKeepsTheStoreAndStoresOnlyWhatItDoesNotHold() {
        FixSessionMessagesStore store = storeHolding(Map.of(1L, "one", 2L, "two"));
        FixSessionReplica replica = new FixSessionReplica(store, "alpha");
        readLast(replica, 16);
        replica.apply(StoreEventType.STORE_MESSAGE.getCode(), RecordFlag.NONE, 3, ascii("three"), 0, 5);

        replica.snapshotBegin(3, FixFrames.seqNums(7, 5), 0, 2 * Long.BYTES);
        replica.snapshotRecord(StoreEventType.STORE_MESSAGE.getCode(), 3, ascii("three"), 0, 5);
        replica.snapshotRecord(StoreEventType.STORE_MESSAGE.getCode(), 4, ascii("four"), 0, 4);

        verify(store, never()).resetSequenceNumbers();
        verify(store).storeNextIncomingSeqNum(7);
        verify(store).storeMessageSent(3, ascii("three"));
        verify(store).storeMessageSent(4, ascii("four"));
    }

    @Test
    void aSnapshotFromTheFirstSeqNumStartsTheStoreOver() {
        FixSessionMessagesStore store = storeHolding(Map.of(1L, "one", 2L, "two"));
        FixSessionReplica replica = new FixSessionReplica(store, "alpha");
        readLast(replica, 16);

        replica.snapshotBegin(0, FixFrames.seqNums(7, 3), 0, 2 * Long.BYTES);
        replica.snapshotRecord(StoreEventType.STORE_MESSAGE.getCode(), 1, ascii("one"), 0, 3);

        verify(store).resetSequenceNumbers();
        verify(store).storeMessageSent(1, ascii("one"));
    }

    @Test
    void aReplicaReportsTheWritesItsStoreHasPendingAndLost() {
        FixSessionMessagesStore store = mock(FixSessionMessagesStore.class);
        when(store.getPendingWrites()).thenReturn(7L);
        when(store.getLostWrites()).thenReturn(4L);

        HaSessionStatus status = describe(new FixSessionReplica(store, "alpha"));

        assertThat(status.getPendingWrites()).isEqualTo(7);
        assertThat(status.getLostWrites()).isEqualTo(4);
    }

    @Test
    void aSnapshotStateOtherThanTheTwoSeqNumsOrARecordOtherThanAMessageIsTheLeadersError() {
        FixSessionReplica replica = new FixSessionReplica(mock(FixSessionMessagesStore.class), "alpha");
        ByteBuffer payload = ByteBuffer.allocate(8);

        assertThatThrownBy(() -> replica.snapshotBegin(0, ByteBuffer.allocate(3), 0, 3)).isInstanceOf(WireFormatException.class);
        assertThatThrownBy(() -> replica.snapshotRecord(StoreEventType.STORE_INCOMING_SEQ_NUM.getCode(), 1, payload, 0, 0))
                .isInstanceOf(WireFormatException.class);
        assertThatThrownBy(() -> replica.apply((byte) 9, RecordFlag.NONE, 1, payload, 0, 0)).isInstanceOf(WireFormatException.class)
                .hasMessage("Unknown store event type 9");
        replica.snapshotEnd(FixFrames.seqNums(4, 5), 0, 2 * Long.BYTES);
    }
}
