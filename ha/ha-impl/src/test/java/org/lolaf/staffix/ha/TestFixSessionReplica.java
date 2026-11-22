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
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.replication.wire.TestingFrameEncoder;
import org.lolaf.staffix.ha.replication.wire.WireFormatException;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestFixSessionReplica {

    private static HaSessionStatus describe(FixSessionReplica replica) {
        HaSessionStatus.HaSessionStatusBuilder status = HaSessionStatus.builder();
        replica.describe(status);
        return status.build();
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

        assertThatThrownBy(() -> replica.snapshotBegin(ByteBuffer.allocate(3), 0, 3)).isInstanceOf(WireFormatException.class);
        assertThatThrownBy(() -> replica.snapshotRecord(StoreEventType.STORE_INCOMING_SEQ_NUM.getCode(), 1, payload, 0, 0))
                .isInstanceOf(WireFormatException.class);
        assertThatThrownBy(() -> replica.apply((byte) 9, 1, payload, 0, 0)).isInstanceOf(WireFormatException.class)
                .hasMessage("Unknown store event type 9");
        replica.snapshotEnd(TestingFrameEncoder.seqNums(4, 5), 0, 2 * Long.BYTES);
    }
}
