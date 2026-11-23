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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TestStreamSyncs {

    private final StreamSyncs syncs = new StreamSyncs();

    @Test
    void aKeyPastTheFirstCapacityGrowsItKeepingWhatItHeld() {
        syncs.announced(1);
        syncs.set(1, StreamSyncState.IN_SYNC);
        syncs.answered(1, StreamAnswer.REPLICATING);

        syncs.announced(40);
        syncs.announced(41);
        syncs.answered(41, StreamAnswer.UNKNOWN_STREAM);
        syncs.set(41, StreamSyncState.NOT_REPLICATED);

        assertThat(syncs.get(1)).isEqualTo(StreamSyncState.IN_SYNC);
        assertThat(syncs.getAnswer(1)).isEqualTo(StreamAnswer.REPLICATING);
        assertThat(syncs.get(40)).isEqualTo(StreamSyncState.ANNOUNCED);
        assertThat(syncs.getAnswer(40)).isNull();
        assertThat(syncs.getAnswer(41)).isEqualTo(StreamAnswer.UNKNOWN_STREAM);
        assertThat(syncs.get(41)).isEqualTo(StreamSyncState.NOT_REPLICATED);
        assertThat(syncs.get(42)).isNull();
    }

    @Test
    void aStreamWrittenWhileTheSenderGrowsItKeepsTheWrite() throws Exception {
        for (int run = 0; run < 200; run++) {
            StreamSyncs grownWhileWritten = new StreamSyncs();
            IntStream.range(0, 16).forEach(grownWhileWritten::announced);
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<Void> sender = CompletableFuture.runAsync(() -> {
                await(start);
                IntStream.range(16, 4096).forEach(grownWhileWritten::announced);
            });
            start.countDown();
            for (int key = 0; key < 16; key++) {
                grownWhileWritten.answered(key, StreamAnswer.REPLICATING);
                grownWhileWritten.set(key, StreamSyncState.IN_SYNC);
            }
            sender.get();

            for (int key = 0; key < 16; key++) {
                assertThat(grownWhileWritten.get(key)).as("run %d, key %d", run, key).isEqualTo(StreamSyncState.IN_SYNC);
                assertThat(grownWhileWritten.getAnswer(key)).isEqualTo(StreamAnswer.REPLICATING);
            }
        }
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
        syncs.announced(0);
        syncs.announced(1);
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
