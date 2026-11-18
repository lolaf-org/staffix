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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.wire.FrameEncoder;

import java.util.List;
import java.util.function.ToIntBiFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TestStoreWrites extends LeaderConnectionHarness {

    @Test
    void storeEventsAreAppliedInOrderAndAcknowledgedOncePerRead() {
        streaming();

        read(event(EPOCH, 0, 0, StoreEventType.STORE_MESSAGE, 5, MESSAGE),
                event(EPOCH, 64, 0, StoreEventType.STORE_OUTGOING_SEQ_NUM, 7, null),
                event(EPOCH, 96, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 3, null),
                event(EPOCH, 128, 0, StoreEventType.RESET_SEQ_NUM, 0, null));

        assertThat(engine.store(MAIN).writes).as("seqnums coalesced, written before the reset")
                .containsExactly("message 5 " + MESSAGE, "incoming 3", "outgoing 7", "reset");
        assertThat(replicaSessions.getLastAppliedEpoch(0)).isEqualTo(EPOCH);
        assertThat(replicaSessions.getLastAppliedOffset(0)).isEqualTo(128);
        assertThat(sent.frames).containsExactly("ACK epoch=7 applied=128 inSync=false");
    }

    @Test
    void aStoreWriteFailingWithItsResourceDownClosesTheStreamWithoutApplyingOrAcknowledgingWhatFollowsIt() {
        streaming();
        engine.store(MAIN).messageWritesFail = true;
        engine.store(MAIN).resourceDown = true;

        read(event(EPOCH, 0, 0, StoreEventType.STORE_OUTGOING_SEQ_NUM, 4, null),
                event(EPOCH, 32, 0, StoreEventType.STORE_MESSAGE, 5, MESSAGE),
                event(EPOCH, 96, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 3, null));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(engine.store(MAIN).writes).as("the seqnum before it still pending").isEmpty();
        assertThat(replicaSessions.getLastAppliedOffset(0)).as("resumed before the read").isEqualTo(-1);
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 APPLY_FAILED");
    }

    @Test
    void aReadsMessagesGoToABatchingStoreAsOneBatchThenItsLastSeqnums() {
        engine.batchingStores = true;
        streaming();

        read(event(EPOCH, 0, 0, StoreEventType.STORE_MESSAGE, 5, "five"),
                event(EPOCH, 64, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 2, null),
                event(EPOCH, 96, 0, StoreEventType.STORE_MESSAGE, 6, "six"),
                event(EPOCH, 160, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 3, null));

        assertThat(engine.store(MAIN).writes).containsExactly("batch 5 five, 6 six", "incoming 3", "outgoing 7");
        assertThat(sent.frames).containsExactly("ACK epoch=7 applied=160 inSync=false");
    }

    @Test
    void anOutgoingSeqnumKeepsItsPlaceAmongTheMessagesOfARead() {
        engine.batchingStores = true;
        streaming();

        read(event(EPOCH, 0, 0, StoreEventType.STORE_MESSAGE, 5, "five"),
                event(EPOCH, 64, 0, StoreEventType.STORE_OUTGOING_SEQ_NUM, 10, null));
        read(event(EPOCH, 96, 0, StoreEventType.STORE_OUTGOING_SEQ_NUM, 20, null),
                event(EPOCH, 128, 0, StoreEventType.STORE_MESSAGE, 20, "twenty"));

        assertThat(engine.store(MAIN).writes).containsExactly("batch 5 five", "outgoing 10", "batch 20 twenty", "outgoing 21");
    }

    @Test
    void aFullBatchIsWrittenWithinTheRead() {
        engine.batchingStores = true;
        streaming();

        read(IntStream.rangeClosed(1, 300)
                .mapToObj(seq -> event(EPOCH, seq * 64L, 0, StoreEventType.STORE_MESSAGE, seq, "m"))
                .toArray(ToIntBiFunction[]::new));

        List<String> writes = engine.store(MAIN).writes;
        assertThat(writes).hasSize(3);
        assertThat(writes.get(0)).startsWith("batch 1 m, ").endsWith(", 256 m");
        assertThat(writes.get(1)).startsWith("batch 257 m, ").endsWith(", 300 m");
        assertThat(writes.get(2)).isEqualTo("outgoing 301");
    }

    @Test
    void aBatchThatFailsLeavesTheReadReceivedButUnapplied() {
        engine.batchingStores = true;
        streamingSyncReceived();
        engine.store(MAIN).messageWritesFail = true;
        engine.store(MAIN).resourceDown = true;

        read(event(EPOCH, 0, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 2, null),
                event(EPOCH, 32, 0, StoreEventType.STORE_MESSAGE, 5, "five"));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(engine.store(MAIN).writes).isEmpty();
        assertThat(replicaSessions.getLastAppliedOffset(0)).isEqualTo(-1);
        assertThat(sent.frames).hasSize(2).first().isEqualTo("RECEIVED epoch=7 received=32");
        assertThat(sent.frames.get(1)).as("never acknowledged as applied").startsWith("CLOSE epoch=7 APPLY_FAILED");
    }

    @Test
    void aStoreWriteFailingWithItsResourceUpCostsThatMessageAlone() {
        streaming();
        engine.store(MAIN).refusedSeqNum = 5;

        read(event(EPOCH, 0, 0, StoreEventType.STORE_MESSAGE, 5, "five"),
                event(EPOCH, 32, 0, StoreEventType.STORE_MESSAGE, 6, "six"));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.STREAMING);
        assertThat(engine.store(MAIN).writes).containsExactly("message 6 six");
        assertThat(replicaSessions.getLastAppliedOffset(0)).isEqualTo(32);
        assertThat(lostWrites()).isEqualTo(1);
    }

    @Test
    void aBatchFailingWithItsResourceUpCostsTheMessageTheStoreRefusesAlone() {
        engine.batchingStores = true;
        streaming();
        engine.store(MAIN).refusedSeqNum = 5;

        read(event(EPOCH, 0, 0, StoreEventType.STORE_MESSAGE, 4, "four"),
                event(EPOCH, 32, 0, StoreEventType.STORE_MESSAGE, 5, "five"),
                event(EPOCH, 64, 0, StoreEventType.STORE_MESSAGE, 6, "six"));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.STREAMING);
        assertThat(engine.store(MAIN).writes).contains("message 4 four", "message 6 six");
        assertThat(lostWrites()).isEqualTo(1);
    }

    @Test
    void aSnapshotForgetsTheWritesLost() {
        streaming();
        engine.store(MAIN).refusedSeqNum = 5;
        read(event(EPOCH, 0, 0, StoreEventType.STORE_MESSAGE, 5, "five"));
        assertThat(lostWrites()).isEqualTo(1);

        read((out, index) -> FrameEncoder.snapshotBegin(out, index, EPOCH, 0, 512, 21, 13));

        assertThat(lostWrites()).isZero();
    }
}
