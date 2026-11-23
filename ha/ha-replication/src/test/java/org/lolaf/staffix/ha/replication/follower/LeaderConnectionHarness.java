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
package org.lolaf.staffix.ha.replication.follower;

import org.lolaf.staffix.ha.replication.EncryptionMode;
import org.lolaf.staffix.ha.replication.journal.Journal;
import org.lolaf.staffix.ha.replication.journal.JournalCopy;
import org.lolaf.staffix.ha.replication.journal.JournalReplica;
import org.lolaf.staffix.ha.replication.journal.JournalStream;
import org.lolaf.staffix.ha.replication.spi.LatencyRecorder;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;
import org.lolaf.staffix.ha.replication.testkit.AbstractLeaderConnectionHarness;
import org.lolaf.staffix.ha.replication.testkit.RecordingHandler;
import org.lolaf.staffix.ha.replication.testkit.TestingKeyExchanges;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.wire.TestingFrameEncoder;
import org.lolaf.staffix.ha.replication.wire.WireFormat;

import java.nio.ByteBuffer;
import java.util.function.ToIntBiFunction;

/**
 * A leader connection replicating into a {@link JournalReplica}. The replica is configured with {@code "main"},
 * {@code ASYNC}.
 */
abstract class LeaderConnectionHarness extends AbstractLeaderConnectionHarness {

    static final String MAIN = "main";

    final JournalReplica replica;
    final JournalCopy main;

    LeaderConnectionHarness() {
        this(new JournalReplica());
    }

    private LeaderConnectionHarness(JournalReplica replica) {
        super(new FollowerStreams(replica, LatencyRecorder.NONE), new RecordingHandler(Journal.APPLICATION_ID));
        this.replica = replica;
        this.main = replica.configure(MAIN, StreamReplicationMode.ASYNC);
    }

    static ToIntBiFunction<ByteBuffer, Integer> welcome(long epoch, long resumeOffset) {
        return welcome(epoch, resumeOffset, 1);
    }

    static ToIntBiFunction<ByteBuffer, Integer> welcome(long epoch, long resumeOffset, int lanes) {
        return (out, index) -> TestingFrameEncoder.welcome(out, index, epoch, "leader-a", resumeOffset, lanes,
                EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
    }

    /**
     * {@code ASYNC}, stored at {@code "leader-storage"}.
     */
    static ToIntBiFunction<ByteBuffer, Integer> stream(int key, String name) {
        return stream(key, name, StreamReplicationMode.ASYNC, "leader-storage");
    }

    static ToIntBiFunction<ByteBuffer, Integer> stream(int key, String name, StreamReplicationMode mode,
                                                       String leaderStorageIdentity) {
        return (out, index) -> FrameEncoder.stream(out, index, EPOCH, key, mode, name, leaderStorageIdentity);
    }

    static ToIntBiFunction<ByteBuffer, Integer> record(long epoch, long offset, int key, long position, String text) {
        return (out, index) -> TestingFrameEncoder.record(out, index, epoch, offset, key, position, text);
    }

    static ToIntBiFunction<ByteBuffer, Integer> fragment(long offset, int key, long position, String text, int flags) {
        return (out, index) -> TestingFrameEncoder.record(out, index, EPOCH, offset, key, JournalStream.TEXT,
                (byte) flags, position, TestingFrameEncoder.ascii(text));
    }

    static ToIntBiFunction<ByteBuffer, Integer> snapshotBegin(long snapshotOffset, String state) {
        return (out, index) -> TestingFrameEncoder.snapshotBegin(out, index, EPOCH, 0, snapshotOffset, state);
    }

    static ToIntBiFunction<ByteBuffer, Integer> snapshotRecord(long position, String text) {
        return (out, index) -> TestingFrameEncoder.snapshotRecord(out, index, EPOCH, 0, position, text);
    }

    static ToIntBiFunction<ByteBuffer, Integer> snapshotFragment(long position, String text, int flags) {
        return (out, index) -> TestingFrameEncoder.snapshotRecord(out, index, EPOCH, 0, JournalStream.TEXT,
                (byte) flags, position, TestingFrameEncoder.ascii(text));
    }

    static ToIntBiFunction<ByteBuffer, Integer> snapshotEnd(int recordCount, String state) {
        return (out, index) -> TestingFrameEncoder.snapshotEnd(out, index, EPOCH, 0, recordCount, state);
    }

    void streaming() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), stream(0, MAIN), announced(1));
        sent.frames.clear();
    }

    void streamingSyncReceived() {
        replica.configure(MAIN, StreamReplicationMode.SYNC_RECEIVED);
        read(welcome(EPOCH, WireFormat.NO_RESUME), stream(0, MAIN, StreamReplicationMode.SYNC_RECEIVED, "leader-storage"),
                announced(1));
        sent.frames.clear();
    }

    /**
     * An empty snapshot puts {@code main} in sync, its writes then cleared.
     */
    void complete() {
        read(snapshotBegin(0, "1"), snapshotEnd(0, "1"));
        main.getWrites().clear();
    }
}
