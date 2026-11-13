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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.wire.AllocatedBytes;
import org.lolaf.staffix.ha.wire.SessionAnswer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TestReplicaSessions {

    private static final FixSessionSettings REPLICATED = RecordingEngine.session("main", FixSessionType.ACCEPTOR);
    private static final FixSessionSettings SHARED = RecordingEngine.session("shared", FixSessionType.ACCEPTOR);
    private static final FixSessionSettings NOT_REPLICATED = RecordingEngine.session("local", FixSessionType.ACCEPTOR).toBuilder()
            .highAvailability(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.NONE).build())
            .build();

    private final RecordingEngine engine = new RecordingEngine().with(REPLICATED).with(SHARED).with(NOT_REPLICATED);
    private final ReplicaSessions replicaSessions = new ReplicaSessions(engine);

    @Test
    void promotionMovesTheOutgoingSeqNumOfEveryReplicatedSessionAhead() {
        assertThat(replicaSessions.bind(0, REPLICATED.getFixSessionType(), REPLICATED.getFixSessionId().toString(), ReplicationMode.ASYNC, "leader-storage"))
                .isEqualTo(SessionAnswer.SNAPSHOT_NEEDED);
        assertThat(replicaSessions.bind(1, SHARED.getFixSessionType(), SHARED.getFixSessionId().toString(), ReplicationMode.ASYNC, "follower-storage"))
                .isEqualTo(SessionAnswer.SHARED_STORAGE);

        replicaSessions.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(REPLICATED).writes).containsExactly("outgoing 1001");
        assertThat(engine.store(REPLICATED).started).as("still the replica's").isTrue();
        assertThat(engine.store(SHARED).writes).as("refused as the leader's storage").containsExactly("outgoing 1001");
        assertThat(engine.store(SHARED).started).as("opened for the jump only").isFalse();
        assertThat(engine.store(NOT_REPLICATED)).isNull();
    }

    @Test
    void aSessionTheLeaderStopsReplicatingIsClosedThenCaughtUpAgainBySnapshot() {
        String id = REPLICATED.getFixSessionId().toString();
        replicaSessions.bind(0, REPLICATED.getFixSessionType(), id, ReplicationMode.ASYNC, "leader-storage");
        RecordingEngine.RecordingStore first = engine.store(REPLICATED);
        assertThat(replicaSessions.snapshotBegin(0, 0, 1, 1) && replicaSessions.snapshotEnd(0, 0, 0, 1, 1)).isTrue();

        engine.replace(NOT_REPLICATED.toBuilder().fixSessionId(REPLICATED.getFixSessionId()).build());
        assertThat(replicaSessions.bind(0, REPLICATED.getFixSessionType(), id, ReplicationMode.NONE, "leader-storage"))
                .isEqualTo(SessionAnswer.NOT_REPLICATED);
        assertThat(first.started).isFalse();
        assertThat(replicaSessions.isInSync()).as("a session not replicated is not waited for").isTrue();

        engine.replace(REPLICATED);
        assertThat(replicaSessions.bind(0, REPLICATED.getFixSessionType(), id, ReplicationMode.ASYNC, "leader-storage"))
                .isEqualTo(SessionAnswer.SNAPSHOT_NEEDED);
        assertThat(engine.store(REPLICATED)).isNotSameAs(first);
        assertThat(replicaSessions.isInSync()).isFalse();
    }

    @Test
    void aSessionReplicatedByTheLeaderButNotHereIsRefusedAndNotMovedAhead() {
        String id = NOT_REPLICATED.getFixSessionId().toString();

        assertThat(replicaSessions.bind(0, NOT_REPLICATED.getFixSessionType(), id, ReplicationMode.ASYNC, "leader-storage"))
                .isEqualTo(SessionAnswer.MODE_MISMATCH);
        assertThat(engine.store(NOT_REPLICATED)).isNull();
        assertThat(replicaSessions.isInSync()).as("a refused session is missing").isFalse();

        replicaSessions.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(NOT_REPLICATED)).as("this node's settings do not replicate it").isNull();
    }

    @Test
    void aSessionTheLeaderDoesNotReplicateIsNotRefusedThoughItStillHasAModeHere() {
        assertThat(replicaSessions.bind(0, REPLICATED.getFixSessionType(), REPLICATED.getFixSessionId().toString(), ReplicationMode.NONE,
                "leader-storage")).isEqualTo(SessionAnswer.NOT_REPLICATED);
        assertThat(engine.store(REPLICATED)).isNull();
        assertThat(replicaSessions.isInSync()).isTrue();
    }

    @Test
    void aReplicationModeChangedOnTheLeaderAloneStopsTheReplica() {
        String id = REPLICATED.getFixSessionId().toString();
        replicaSessions.bind(0, REPLICATED.getFixSessionType(), id, ReplicationMode.ASYNC, "leader-storage");
        RecordingEngine.RecordingStore replicaStore = engine.store(REPLICATED);

        assertThat(replicaSessions.bind(0, REPLICATED.getFixSessionType(), id, ReplicationMode.SYNC_APPLIED, "leader-storage"))
                .isEqualTo(SessionAnswer.MODE_MISMATCH);
        assertThat(replicaStore.started).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aMessageTheStreamBringsAgainAfterTheSnapshotIsSkippedUntilAReset(boolean batchingStore) {
        engine.batchingStores = batchingStore;
        replicaSessions.bind(0, REPLICATED.getFixSessionType(), REPLICATED.getFixSessionId().toString(), ReplicationMode.ASYNC, "leader-storage");
        replicaSessions.snapshotBegin(0, 0, 1, 1);
        replicaSessions.snapshotMessage(0, 0, 4, text("four"), 0, 4, (byte) 0);
        replicaSessions.snapshotMessage(0, 0, 5, text("five"), 0, 4, (byte) 0);
        replicaSessions.snapshotEnd(0, 0, 2, 1, 6);
        RecordingEngine.RecordingStore store = engine.store(REPLICATED);
        store.writes.clear();

        storeMessage(5, "five");
        storeMessage(6, "six");
        replicaSessions.apply(0, 1, 3, 0, StoreEventType.RESET_SEQ_NUM, 1, text(""), 0, 0, (byte) 0);
        storeMessage(1, "one");
        replicaSessions.writeApplied(0);

        assertThat(store.writes).containsExactly(batchingStore
                ? new String[]{"batch 6 six", "outgoing 7", "reset", "batch 1 one", "outgoing 2"}
                : new String[]{"message 6 six", "reset", "message 1 one"});
    }

    private void storeMessage(long seq, String message) {
        replicaSessions.apply(0, 1, seq, 0, StoreEventType.STORE_MESSAGE, seq, text(message), 0, message.length(), (byte) 0);
    }

    private static ByteBuffer text(String message) {
        return ByteBuffer.wrap(message.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void aSessionTheLeaderNeverAnnouncedIsMovedAheadToo() {
        replicaSessions.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(REPLICATED).writes).containsExactly("outgoing 1001");
        assertThat(engine.store(SHARED).writes).containsExactly("outgoing 1001");
    }

    /**
     * What the network thread asks of a lane on every read, before acknowledging it. Both sessions replicated: a
     * session not replicated has the {@link NoStore}, whose inherited default methods Mockito instruments once another
     * test mocks the store interface, and its check then allocates.
     */
    @Test
    void endingAReadAllocatesNothing() {
        replicaSessions.bind(0, REPLICATED.getFixSessionType(), REPLICATED.getFixSessionId().toString(), ReplicationMode.ASYNC, "leader-storage");
        replicaSessions.bind(1, SHARED.getFixSessionType(), SHARED.getFixSessionId().toString(), ReplicationMode.ASYNC, "leader-storage");
        for (int key = 0; key < 2; key++) {
            replicaSessions.snapshotBegin(0, key, 1, 1);
            replicaSessions.snapshotEnd(0, key, 0, 1, 1);
        }
        assertThat(endRead()).as("in sync, nothing to bind again, nothing pending").isEqualTo(1);

        long before = AllocatedBytes.ofCurrentThread();
        long inSyncReads = 0;
        for (int i = 0; i < 100_000; i++) {
            inSyncReads += endRead();
        }

        assertThat(AllocatedBytes.ofCurrentThread() - before).as("less than a byte per read").isLessThan(100_000);
        assertThat(inSyncReads).isEqualTo(100_000);
    }

    private long endRead() {
        replicaSessions.writeApplied(0);
        boolean idle = replicaSessions.isLaneInSync(0) && !replicaSessions.hasSessionsToBindAgain(0);
        return idle ? 1 + replicaSessions.getPendingWrites(0) : 0;
    }
}
