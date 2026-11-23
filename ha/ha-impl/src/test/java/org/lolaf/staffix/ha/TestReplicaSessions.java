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

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;
import org.lolaf.staffix.ha.replication.wire.WireFormat;

import static org.assertj.core.api.Assertions.assertThat;

class TestReplicaSessions extends FixLeaderConnectionHarness {

    private static final FixSessionSettings SHARED = RecordingEngine.session("shared", FixSessionType.ACCEPTOR);
    private static final FixSessionSettings NOT_REPLICATED = withMode(RecordingEngine.session("local", FixSessionType.ACCEPTOR),
            ReplicationMode.NONE);

    TestReplicaSessions() {
        engine.with(SHARED).with(NOT_REPLICATED);
        read(welcome(EPOCH, WireFormat.NO_RESUME));
    }

    @Test
    void promotionMovesTheOutgoingSeqNumOfEveryReplicatedSessionAhead() {
        assertThat(announce(0, MAIN, "leader-storage")).isEqualTo(StreamAnswer.SNAPSHOT_NEEDED);
        assertThat(announce(1, SHARED, "follower-storage")).isEqualTo(StreamAnswer.SHARED_STORAGE);

        replicaSessions.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(MAIN).writes).containsExactly("outgoing 1001");
        assertThat(engine.store(MAIN).started).as("still the replica's").isTrue();
        assertThat(engine.store(SHARED).writes).as("refused as the leader's storage").containsExactly("outgoing 1001");
        assertThat(engine.store(SHARED).started).as("opened for the jump only").isFalse();
        assertThat(engine.store(NOT_REPLICATED)).isNull();
    }

    @Test
    void aSessionTheLeaderStopsReplicatingIsClosedThenCaughtUpAgainBySnapshot() {
        announce(0, MAIN, "leader-storage");
        read(announced(1));
        RecordingEngine.RecordingStore first = engine.store(MAIN);
        complete();
        assertThat(replicaSessions.isInSync()).isTrue();

        FixSessionSettings notReplicated = withMode(MAIN, ReplicationMode.NONE);
        engine.replace(notReplicated);
        assertThat(announce(0, notReplicated, "leader-storage")).isEqualTo(StreamAnswer.NOT_REPLICATED);
        assertThat(first.started).isFalse();
        assertThat(replicaSessions.isInSync()).as("a session not replicated is not waited for").isTrue();

        engine.replace(MAIN);
        assertThat(announce(0, MAIN, "leader-storage")).isEqualTo(StreamAnswer.SNAPSHOT_NEEDED);
        assertThat(engine.store(MAIN)).isNotSameAs(first);
        assertThat(replicaSessions.isInSync()).isFalse();
    }

    @Test
    void aSessionReplicatedByTheLeaderButNotHereIsRefusedAndNotMovedAhead() {
        assertThat(announce(0, withMode(NOT_REPLICATED, ReplicationMode.ASYNC), "leader-storage"))
                .isEqualTo(StreamAnswer.MODE_MISMATCH);
        read(announced(1));
        assertThat(engine.store(NOT_REPLICATED)).isNull();
        assertThat(replicaSessions.isInSync()).as("a refused session is missing").isFalse();

        replicaSessions.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(NOT_REPLICATED)).as("this node's settings do not replicate it").isNull();
    }

    @Test
    void aSessionTheLeaderDoesNotReplicateIsNotRefusedThoughItStillHasAModeHere() {
        assertThat(announce(0, withMode(MAIN, ReplicationMode.NONE), "leader-storage")).isEqualTo(StreamAnswer.NOT_REPLICATED);
        read(announced(1));
        assertThat(engine.store(MAIN)).isNull();
        assertThat(replicaSessions.isInSync()).isTrue();
    }

    @Test
    void aReplicationModeChangedOnTheLeaderAloneStopsTheReplica() {
        announce(0, MAIN, "leader-storage");
        RecordingEngine.RecordingStore replicaStore = engine.store(MAIN);

        assertThat(announce(0, withMode(MAIN, ReplicationMode.SYNC_APPLIED), "leader-storage"))
                .isEqualTo(StreamAnswer.MODE_MISMATCH);
        assertThat(replicaStore.started).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aMessageTheStreamBringsAgainAfterTheSnapshotIsSkippedUntilAReset(boolean batchingStore) {
        engine.batchingStores = batchingStore;
        announce(0, MAIN, "leader-storage");
        read((out, index) -> FixFrames.snapshotBegin(out, index, EPOCH, 0, 0, 1, 1),
                snapshotMessage(4, "four"),
                snapshotMessage(5, "five"),
                (out, index) -> FixFrames.snapshotEnd(out, index, EPOCH, 0, 2, 1, 6));
        RecordingEngine.RecordingStore store = engine.store(MAIN);
        store.writes.clear();

        read(event(EPOCH, 1, 0, StoreEventType.STORE_MESSAGE, 5, "five"),
                event(EPOCH, 2, 0, StoreEventType.STORE_MESSAGE, 6, "six"),
                event(EPOCH, 3, 0, StoreEventType.RESET_SEQ_NUM, 1, null),
                event(EPOCH, 4, 0, StoreEventType.STORE_MESSAGE, 1, "one"));

        assertThat(store.writes).containsExactly(batchingStore
                ? new String[]{"batch 6 six", "outgoing 7", "reset", "batch 1 one", "outgoing 2"}
                : new String[]{"message 6 six", "reset", "message 1 one"});
    }

    @Test
    void aSessionTheLeaderNeverAnnouncedIsMovedAheadToo() {
        replicaSessions.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(MAIN).writes).containsExactly("outgoing 1001");
        assertThat(engine.store(SHARED).writes).containsExactly("outgoing 1001");
    }

    /**
     * The answer the follower sends to the session announced as the leader has it.
     */
    private @Nullable StreamAnswer announce(int key, FixSessionSettings leaderSettings, String leaderStorageIdentity) {
        sent.frames.clear();
        read(session(key, leaderSettings, leaderStorageIdentity));
        String answerLine = "STREAM_ANSWER epoch=" + EPOCH + " key=" + key + " ";
        return sent.frames.stream()
                .filter(frame -> frame.startsWith(answerLine))
                .map(frame -> StreamAnswer.valueOf(frame.substring(answerLine.length())))
                .reduce((earlier, later) -> later)
                .orElse(null);
    }

    private static FixSessionSettings withMode(FixSessionSettings settings, ReplicationMode mode) {
        return settings.toBuilder()
                .highAvailability(FixSessionSettings.HaSessionSettings.builder().replicationMode(mode).build())
                .build();
    }
}
