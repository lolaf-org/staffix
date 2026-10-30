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
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.ha.wire.SessionStatus;

import static org.assertj.core.api.Assertions.assertThat;

class TestReplica {

    private static final FixSessionSettings REPLICATED = RecordingEngine.session("main", FixSessionType.ACCEPTOR);
    private static final FixSessionSettings SHARED = RecordingEngine.session("shared", FixSessionType.ACCEPTOR);
    private static final FixSessionSettings NOT_REPLICATED = RecordingEngine.session("local", FixSessionType.ACCEPTOR).toBuilder()
            .haSessionSettings(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.NONE).build())
            .build();

    private final RecordingEngine engine = new RecordingEngine().with(REPLICATED).with(SHARED).with(NOT_REPLICATED);
    private final Replica replica = new Replica(engine);

    @Test
    void promotionMovesTheOutgoingSeqNumOfEveryReplicatedSessionAhead() {
        assertThat(replica.bind(0, REPLICATED.getFixSessionType(), REPLICATED.getFixSessionId().toString(), ReplicationMode.ASYNC, "leader-storage"))
                .isEqualTo(SessionStatus.SNAPSHOT_NEEDED);
        assertThat(replica.bind(1, SHARED.getFixSessionType(), SHARED.getFixSessionId().toString(), ReplicationMode.ASYNC, "follower-storage"))
                .isEqualTo(SessionStatus.SHARED_STORAGE);

        replica.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(REPLICATED).writes).containsExactly("outgoing 1001");
        assertThat(engine.store(REPLICATED).started).as("still the replica's").isTrue();
        assertThat(engine.store(SHARED).writes).as("its storage, shared with the old leader").containsExactly("outgoing 1001");
        assertThat(engine.store(SHARED).started).as("opened for the jump only").isFalse();
        assertThat(engine.store(NOT_REPLICATED)).isNull();
    }

    @Test
    void aSessionTheLeaderStopsReplicatingIsClosedThenCaughtUpAgainBySnapshot() {
        String id = REPLICATED.getFixSessionId().toString();
        replica.bind(0, REPLICATED.getFixSessionType(), id, ReplicationMode.ASYNC, "leader-storage");
        RecordingEngine.RecordingStore first = engine.store(REPLICATED);
        assertThat(replica.snapshotBegin(0, 1, 1) && replica.snapshotEnd(0, 0, 1, 1)).isTrue();

        engine.replace(NOT_REPLICATED.toBuilder().fixSessionId(REPLICATED.getFixSessionId()).build());
        assertThat(replica.bind(0, REPLICATED.getFixSessionType(), id, ReplicationMode.NONE, "leader-storage"))
                .isEqualTo(SessionStatus.NOT_REPLICATED);
        assertThat(first.started).isFalse();
        assertThat(replica.isComplete()).as("a session not replicated is not waited for").isTrue();

        engine.replace(REPLICATED);
        assertThat(replica.bind(0, REPLICATED.getFixSessionType(), id, ReplicationMode.ASYNC, "leader-storage"))
                .isEqualTo(SessionStatus.SNAPSHOT_NEEDED);
        assertThat(engine.store(REPLICATED)).isNotSameAs(first);
        assertThat(replica.isComplete()).isFalse();
    }

    @Test
    void aSessionReplicatedByTheLeaderButNotHereIsRefusedAndNotMovedAhead() {
        String id = NOT_REPLICATED.getFixSessionId().toString();

        assertThat(replica.bind(0, NOT_REPLICATED.getFixSessionType(), id, ReplicationMode.ASYNC, "leader-storage"))
                .isEqualTo(SessionStatus.MODE_MISMATCH);
        assertThat(engine.store(NOT_REPLICATED)).isNull();
        assertThat(replica.isComplete()).as("a refused session is missing").isFalse();

        replica.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(NOT_REPLICATED)).as("this node's settings do not replicate it").isNull();
    }

    @Test
    void aSessionReplicatedHereButNotByTheLeaderIsRefused() {
        assertThat(replica.bind(0, REPLICATED.getFixSessionType(), REPLICATED.getFixSessionId().toString(), ReplicationMode.NONE,
                "leader-storage")).isEqualTo(SessionStatus.MODE_MISMATCH);
        assertThat(engine.store(REPLICATED)).isNull();
    }

    @Test
    void aReplicationModeChangedOnTheLeaderAloneStopsTheReplica() {
        String id = REPLICATED.getFixSessionId().toString();
        replica.bind(0, REPLICATED.getFixSessionType(), id, ReplicationMode.ASYNC, "leader-storage");
        RecordingEngine.RecordingStore replicaStore = engine.store(REPLICATED);

        assertThat(replica.bind(0, REPLICATED.getFixSessionType(), id, ReplicationMode.SYNC, "leader-storage"))
                .isEqualTo(SessionStatus.MODE_MISMATCH);
        assertThat(replicaStore.started).isFalse();
    }

    @Test
    void aSessionTheLeaderNeverAnnouncedIsMovedAheadToo() {
        replica.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(REPLICATED).writes).containsExactly("outgoing 1001");
        assertThat(engine.store(SHARED).writes).containsExactly("outgoing 1001");
    }
}
