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
package org.lolaf.staffix.ha.replication.leader;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;

import static org.assertj.core.api.Assertions.assertThat;

class TestStreamSyncs {

    private final StreamSyncs syncs = new StreamSyncs();

    @Test
    void aKeyPastTheFirstCapacityGrowsBothArraysKeepingWhatTheyHeld() {
        syncs.set(1, StreamSyncState.IN_SYNC);
        syncs.answered(1, StreamAnswer.REPLICATING);

        syncs.set(40, StreamSyncState.ANNOUNCED);
        syncs.answered(41, StreamAnswer.UNKNOWN_STREAM);

        assertThat(syncs.get(1)).isEqualTo(StreamSyncState.IN_SYNC);
        assertThat(syncs.getAnswer(1)).isEqualTo(StreamAnswer.REPLICATING);
        assertThat(syncs.get(40)).isEqualTo(StreamSyncState.ANNOUNCED);
        assertThat(syncs.getAnswer(41)).isEqualTo(StreamAnswer.UNKNOWN_STREAM);
        assertThat(syncs.get(41)).isNull();
    }

    @Test
    void aKeyNeverSetIsNeitherReadNorChanged() {
        assertThat(syncs.get(100)).isNull();
        assertThat(syncs.getAnswer(100)).isNull();
        assertThat(syncs.compareAndSet(100, StreamSyncState.IN_SYNC, StreamSyncState.SNAPSHOT_PENDING)).isFalse();
        assertThat(syncs.get(100)).isNull();
    }

    @Test
    void aSnapshotIsInProgressFromItsRequestUntilItLands() {
        syncs.set(0, StreamSyncState.IN_SYNC);
        syncs.set(1, StreamSyncState.NOT_REPLICATED);
        assertThat(syncs.isSnapshotInProgress(2)).isFalse();

        syncs.set(1, StreamSyncState.SNAPSHOT_PENDING);
        assertThat(syncs.isSnapshotInProgress(2)).isTrue();
        assertThat(syncs.isSnapshotInProgress(1)).as("only the streams counted").isFalse();

        syncs.set(1, StreamSyncState.SNAPSHOT_SENT);
        assertThat(syncs.isSnapshotInProgress(2)).isTrue();

        syncs.set(1, StreamSyncState.IN_SYNC);
        assertThat(syncs.isSnapshotInProgress(2)).isFalse();
    }
}
