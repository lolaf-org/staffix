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

import com.github.valfirst.slf4jtest.LoggingEvent;
import com.github.valfirst.slf4jtest.TestLogger;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.staffix.ha.replication.EncryptionMode;
import org.lolaf.staffix.ha.replication.testkit.TestingKeyExchanges;
import org.lolaf.staffix.ha.replication.journal.Journal;
import org.lolaf.staffix.ha.replication.journal.JournalStream;
import org.lolaf.staffix.ha.replication.spi.RecordFlag;
import org.lolaf.staffix.ha.replication.spi.TestingReplicationClock;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;
import org.lolaf.staffix.ha.replication.wire.WireFormat;
import org.slf4j.event.Level;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TestSnapshotSender extends FollowerConnectionHarness {

    private void hello() {
        connection.onHello(EPOCH, WireFormat.VERSION, Journal.APPLICATION_ID, "follower-b", 0, -1, 0, EncryptionMode.DISABLED,
                TestingKeyExchanges.NO_NONCE);
        fill();
    }

    private FollowerConnection connectionChunking(int snapshotChunkBytes) {
        return new FollowerConnection(ioSession, journal.getLogs(), journal, () -> EPOCH, "leader-a",
                Duration.ofMillis(100), Duration.ZERO, 4, snapshotChunkBytes, new BackoffIdleStrategy(), stats, loop -> {
        }, new TestingReplicationClock(), TestingKeyExchanges.CLEAR);
    }

    /**
     * Holds {@code count} more entries once the snapshot reads its first, as appends landing while it runs.
     */
    private static void growsWhileSnapshotted(JournalStream stream, int count) {
        AtomicBoolean grown = new AtomicBoolean();
        stream.setSnapshotReads(position -> {
            if (grown.compareAndSet(false, true)) {
                stream.hold(count, MESSAGE_BYTES);
            }
        });
    }

    private static List<Long> range(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().collect(Collectors.toList());
    }

    @Test
    void aResyncSnapshotsAgainAStreamTheFollowerHoldsAndLeavesTheOthers() {
        stream("main").append("");
        stream("shared").append("");
        stream("refused").append("");
        connection.onHello(EPOCH, WireFormat.VERSION, Journal.APPLICATION_ID, "follower-b", EPOCH, 0, 0, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
        fill();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.REPLICATING);
        connection.onStreamAnswer(EPOCH, 1, StreamAnswer.SHARED_STORAGE);
        connection.onStreamAnswer(EPOCH, 2, StreamAnswer.UNKNOWN_STREAM);

        for (int key = 0; key < 3; key++) {
            connection.resync(key);
        }

        assertThat(connection.getSyncState(0)).isEqualTo(StreamSyncState.SNAPSHOT_PENDING);
        assertThat(connection.isStreamInSync(0)).as("not waited for until the snapshot lands").isFalse();
        assertThat(connection.getSyncState(1)).as("refused").isEqualTo(StreamSyncState.NOT_REPLICATED);
        assertThat(connection.getSyncState(2)).isEqualTo(StreamSyncState.NOT_REPLICATED);
        sent.frames.clear();
        snapshot();
        assertThat(sent.frames).first().asString().startsWith("SNAPSHOT_BEGIN epoch=7 key=0 ");
        assertThat(sent.frames).noneMatch(frame -> frame.contains("key=1") || frame.contains("key=2"));
    }

    @Test
    void aSnapshotCarriesTheStateAndTheRecordsUpToTheStreamsPosition() {
        JournalStream main = stream("main");
        main.append("one");
        main.append("two");
        hello();
        sent.frames.clear();

        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        snapshot();

        long snapshotOffset = journal.getLogs().get(0).getTailOffset();
        assertThat(sent.frames).containsExactly(
                "SNAPSHOT_BEGIN epoch=7 key=0 snapshotOffset=" + snapshotOffset + " state=3",
                "SNAPSHOT_RECORD epoch=7 key=0 type=1 pos=1 payload=one",
                "SNAPSHOT_RECORD epoch=7 key=0 type=1 pos=2 payload=two",
                "SNAPSHOT_END epoch=7 key=0 count=2 state=3");
        assertThat(main.getSnapshotRanges()).singleElement().isEqualTo(new long[]{0, 2});
        assertThat(connection.getSyncState(0)).isEqualTo(StreamSyncState.SNAPSHOT_SENT);
        assertThat(stats.getSnapshots()).isEqualTo(1);
        assertThat(stats.getSnapshotRecords()).isEqualTo(2);
    }

    @Test
    void aPlaintextRecordOfASnapshotIsFlaggedOnEveryFragment() {
        int fragment = journal.getLogs().get(0).getMaxRecordFrameBytes() - FrameEncoder.snapshotRecordBytes(0);
        JournalStream main = stream("main");
        main.hold(RecordFlag.PLAINTEXT, "x".repeat(2 * fragment + 1));
        main.hold(RecordFlag.NONE, "encrypted");
        hello();
        sentPlaintext.clear();

        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        snapshot();

        assertThat(sentPlaintext).as("three fragments, then the other record").containsExactly(true, true, true, false);
    }

    @Test
    void aSnapshotGoesInChunksBetweenTheBatchesOfTheOtherStreamsAndSkipsItsOwnRecords() {
        JournalStream main = streamHolding("main", 1_000);
        JournalStream other = stream("other");
        hello();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        sent.frames.clear();

        connection.getSender().startPendingSnapshot();
        connection.getSender().sendSnapshotChunk();

        assertThat(sent.frames).first().asString().startsWith("SNAPSHOT_BEGIN epoch=7 key=0 ");
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("SNAPSHOT_END"));
        int chunked = sent.frames.size();
        main.append("");
        other.append("");
        fill();
        assertThat(sent.frames.subList(chunked, sent.frames.size())).singleElement().asString()
                .contains("key=1 type=1 pos=1");

        while (connection.getSender().isSnapshotInProgress()) {
            connection.getSender().sendSnapshotChunk();
        }
        assertThat(snapshotPositions()).as("the record appended meanwhile in the last round").isEqualTo(range(1, 1_001));
        assertThat(sent.frames).last().isEqualTo("SNAPSHOT_END epoch=7 key=0 count=1001 state=1002");
    }

    @Test
    void aSnapshotChunkHoldsNoMoreThanItsBudget() {
        int chunkBytes = 4 << 10;
        connection = connectionChunking(chunkBytes);
        streamHolding("main", 1_000);
        hello();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        connection.getSender().startPendingSnapshot();
        connection.getSender().sendSnapshotChunk();
        sent.frames.clear();

        connection.getSender().sendSnapshotChunk();

        int recordFrameBytes = FrameEncoder.snapshotRecordBytes(MESSAGE_BYTES);
        assertThat(snapshotPositions()).hasSize(chunkBytes / recordFrameBytes);
        snapshot();
        assertThat(sent.frames).last().isEqualTo("SNAPSHOT_END epoch=7 key=0 count=1000 state=1001");
    }

    @Test
    void aSnapshotReadsTheStreamOnlyAboutAsFarAsEachChunkSends() {
        connection = connectionChunking(4 << 10);
        JournalStream main = streamHolding("main", 1_000);
        hello();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);

        snapshot();

        long read = main.getSnapshotRanges().stream().mapToLong(range -> range[1] - range[0] + 1).sum();
        assertThat(read).as("positions asked of the stream for 1 000 sent").isLessThan(1_500);
        assertThat(sent.frames).last().isEqualTo("SNAPSHOT_END epoch=7 key=0 count=1000 state=1001");
    }

    @Test
    void aSnapshotEndsWithTheRoundThatCaughtUpWithTheStreamsAppends() {
        growsWhileSnapshotted(streamHolding("main", 1_000), 10);
        hello();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        sent.frames.clear();

        snapshot();

        assertThat(snapshotPositions()).isEqualTo(range(1, 1_010));
        assertThat(sent.frames).last().isEqualTo("SNAPSHOT_END epoch=7 key=0 count=1010 state=1011");
    }

    @Test
    void aSnapshotNotCatchingUpSendsItsLastRoundWhole() {
        growsWhileSnapshotted(streamHolding("main", 1_000), 2_000);
        hello();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        connection.getSender().startPendingSnapshot();
        while (snapshotPositions().size() < 1_000) {
            connection.getSender().sendSnapshotChunk();
        }
        sent.frames.clear();

        connection.getSender().sendSnapshotChunk();

        assertThat(snapshotPositions()).as("the second round, longer than the first, in one chunk")
                .isEqualTo(range(1_001, 3_000));
        assertThat(sent.frames).last().isEqualTo("SNAPSHOT_END epoch=7 key=0 count=3000 state=3001");
        assertThat(connection.getSender().isSnapshotInProgress()).isFalse();
    }

    @Test
    void aResetOfTheStreamEndsItsSnapshotAndIsSentAfterItsEnd() {
        JournalStream main = streamHolding("main", 1_000);
        hello();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        connection.getSender().startPendingSnapshot();
        connection.getSender().sendSnapshotChunk();
        sent.frames.clear();

        main.reset();
        fill();
        connection.getSender().sendSnapshotChunk();
        fill();

        assertThat(connection.getSender().isSnapshotInProgress()).isFalse();
        assertThat(sent.frames).hasSize(2);
        assertThat(sent.frames.get(0)).startsWith("SNAPSHOT_END epoch=7 key=0 count=");
        assertThat(sent.frames.get(1)).endsWith("key=0 type=1 pos=0 payload= reset");
    }

    @Test
    void aSnapshotCarriesARecordLongerThanAFrameInFragmentsAndCountsItOnce() {
        int fragment = journal.getLogs().get(0).getMaxRecordFrameBytes() - FrameEncoder.snapshotRecordBytes(0);
        String x = "x".repeat(fragment);
        String y = "y".repeat(fragment);
        stream("main").hold(RecordFlag.NONE, x + y + "zzz");
        hello();
        sent.frames.clear();

        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        snapshot();

        assertThat(sent.frames).filteredOn(frame -> !frame.startsWith("SNAPSHOT_BEGIN")).containsExactly(
                "SNAPSHOT_RECORD epoch=7 key=0 type=1 pos=1 payload=" + x + " more",
                "SNAPSHOT_RECORD epoch=7 key=0 type=1 pos=1 payload=" + y + " continued more",
                "SNAPSHOT_RECORD epoch=7 key=0 type=1 pos=1 payload=zzz continued",
                "SNAPSHOT_END epoch=7 key=0 count=1 state=2");
    }

    @Test
    void aSnapshotAsksForEveryPositionEvenThoseHoldingNoRecord() {
        JournalStream main = stream("main");
        main.skip(1_000);
        hello();

        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        snapshot();

        List<long[]> ranges = main.getSnapshotRanges();
        List<Long> next = new ArrayList<>(List.of(0L));
        ranges.forEach(range -> next.add(range[1] + 1));
        assertThat(ranges).extracting(range -> range[0]).as("each read starts where the last ended")
                .isEqualTo(next.subList(0, next.size() - 1));
        assertThat(ranges).last().extracting(range -> range[1]).isEqualTo(1_000L);
    }

    @Test
    void aStreamNotReadableYetIsSnapshotOnceItIs() {
        JournalStream main = stream("main");
        main.hold(4, 10);
        main.setReadable(false);
        hello();
        sent.frames.clear();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);

        snapshot();

        assertThat(sent.frames).isEmpty();
        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(connection.getSyncState(0)).isEqualTo(StreamSyncState.SNAPSHOT_PENDING);
        assertThat(main.getSnapshotRanges()).isEmpty();

        main.setReadable(true);
        await().untilAsserted(() -> {
            snapshot();
            assertThat(sent.frames).first().asString().startsWith("SNAPSHOT_BEGIN epoch=7 key=0 ");
        });
        assertThat(connection.getSyncState(0)).isEqualTo(StreamSyncState.SNAPSHOT_SENT);
    }

    @Test
    void aSnapshotWhoseSourceStopsIsRetriedOnceItIsReadableAgainOnTheSameConnection() {
        JournalStream main = stream("main");
        main.hold(4, 10);
        AtomicInteger reads = new AtomicInteger();
        main.setSnapshotReads(position -> {
            if (reads.incrementAndGet() == 2) {
                main.setReadable(false);
                throw new IllegalStateException("store stopped");
            }
        });
        hello();
        sent.frames.clear();
        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        TestLogger snapshotLog = TestLoggerFactory.getTestLogger(SnapshotSender.class);
        int loggedBefore = snapshotLog.getLoggingEvents().size();

        snapshot();

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.STREAMING);
        assertThat(sent.frames).first().asString().startsWith("SNAPSHOT_BEGIN epoch=7 key=0 ");
        assertThat(sent.frames).noneMatch(frame -> frame.startsWith("CLOSE") || frame.startsWith("SNAPSHOT_END"));
        assertThat(connection.getSyncState(0)).isEqualTo(StreamSyncState.SNAPSHOT_PENDING);
        assertThat(snapshotLog.getLoggingEvents().subList(loggedBefore, snapshotLog.getLoggingEvents().size()))
                .extracting(LoggingEvent::getLevel).containsExactly(Level.INFO);

        sent.frames.clear();
        main.setSnapshotReads(position -> {
        });
        main.setReadable(true);
        await().untilAsserted(() -> {
            snapshot();
            assertThat(sent.frames).anyMatch(frame -> frame.startsWith("SNAPSHOT_END epoch=7 key=0 "));
        });
        assertThat(sent.frames).first().asString().startsWith("SNAPSHOT_BEGIN epoch=7 key=0 ");
        assertThat(connection.getSyncState(0)).isEqualTo(StreamSyncState.SNAPSHOT_SENT);
    }

    @Test
    void aStreamThatCannotBeReadEndsTheConnectionSoTheFollowerRetries() {
        JournalStream main = stream("main");
        main.hold(4, 10);
        main.setSnapshotReads(position -> {
            throw new IllegalStateException("storage down");
        });
        hello();
        sent.frames.clear();

        connection.onStreamAnswer(EPOCH, 0, StreamAnswer.SNAPSHOT_NEEDED);
        snapshot();

        assertThat(connection.getState()).isEqualTo(FollowerConnection.State.CLOSED);
        assertThat(sent.frames).anyMatch(frame -> frame.startsWith("CLOSE epoch=7 SNAPSHOT_FAILED"));
        assertThat(connection.getSyncState(0)).isEqualTo(StreamSyncState.SNAPSHOT_PENDING);
    }
}
