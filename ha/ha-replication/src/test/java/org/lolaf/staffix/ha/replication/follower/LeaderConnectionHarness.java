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

import org.lolaf.betty.api.io.IOSession;
import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import org.lolaf.staffix.ha.replication.encryption.KeyExchange;
import org.lolaf.staffix.ha.replication.encryption.TestingKeyExchanges;
import org.lolaf.staffix.ha.replication.journal.JournalCopy;
import org.lolaf.staffix.ha.replication.journal.JournalReplica;
import org.lolaf.staffix.ha.replication.journal.JournalStream;
import org.lolaf.staffix.ha.replication.wire.CloseReason;
import org.lolaf.staffix.ha.replication.wire.FrameCipher;
import org.lolaf.staffix.ha.replication.wire.FrameDecoder;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.wire.FrameHandler;
import org.lolaf.staffix.ha.replication.wire.FrameType;
import org.lolaf.staffix.ha.replication.wire.RecordingHandler;
import org.lolaf.staffix.ha.replication.wire.StreamReplicationMode;
import org.lolaf.staffix.ha.replication.wire.TestingFrameEncoder;
import org.lolaf.staffix.ha.replication.wire.WireFormat;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntBiFunction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A leader connection over a mocked IO session replicating into a {@link JournalReplica}, fed the frames a leader
 * would send. The replica is configured with {@code "main"}, {@code ASYNC}.
 */
abstract class LeaderConnectionHarness {

    static final long EPOCH = 7;
    static final String MAIN = "main";
    static final int FIRST = WireFormat.MORE_FRAGMENTS;
    static final int MIDDLE = WireFormat.MORE_FRAGMENTS | WireFormat.CONTINUED_FRAGMENT;
    static final int LAST = WireFormat.CONTINUED_FRAGMENT;

    final IOSession ioSession;
    final RecordingHandler sent;
    final JournalReplica replica;
    final JournalCopy main;
    final ReplicaStreams replicaStreams;
    final LeaderConnection connection;
    private final FrameDecoder sentDecoder;
    private FrameCipher sentOpener;
    private KeyExchange latestKeyExchange;
    private LeaderConnection latest;

    LeaderConnectionHarness() {
        this.ioSession = mock(IOSession.class);
        this.sent = new RecordingHandler();
        this.sentDecoder = new FrameDecoder(1 << 20);
        this.sentOpener = FrameCipher.CLEAR;
        this.replica = new JournalReplica();
        this.main = replica.configure(MAIN, StreamReplicationMode.ASYNC);
        this.replicaStreams = new ReplicaStreams(replica);
        this.connection = newConnection(0, 0);
        when(ioSession.borrow(anyInt())).then(invocation -> ByteBuffer.allocate(invocation.getArgument(0)));
        doAnswer(invocation -> {
            ByteBuffer frames = invocation.getArgument(0);
            decodeSent(frames.duplicate().flip());
            return null;
        }).when(ioSession).send(any(ByteBuffer.class), anyBoolean());
    }

    static void decode(ByteBuffer frames, FrameHandler handler) {
        FrameDecoder decoder = new FrameDecoder(1 << 20);
        int index = frames.position();
        int consumed;
        while (index < frames.limit() && (consumed = decoder.decode(frames, index, frames.limit() - index, handler)) > 0) {
            index += consumed;
        }
    }

    /**
     * As the leader reads them: every frame after the KEY_CONFIRMATION opened.
     */
    private void decodeSent(ByteBuffer frames) {
        int index = frames.position();
        int consumed;
        while (index < frames.limit() && (consumed = sentDecoder.decode(frames, index, frames.limit() - index, sent, sentOpener)) > 0) {
            if (frames.get(index + WireFormat.OFFSET_TYPE) == FrameType.KEY_CONFIRMATION.getCode()) {
                sentOpener = latestKeyExchange.newOpener(latest.getEncryptionKeys().getFollowerToLeader());
            }
            index += consumed;
        }
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

    static ToIntBiFunction<ByteBuffer, Integer> announced(int count) {
        return (out, index) -> FrameEncoder.streamsAnnounced(out, index, EPOCH, count);
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

    static ToIntBiFunction<ByteBuffer, Integer> heartbeat() {
        return (out, index) -> FrameEncoder.heartbeat(out, index, EPOCH, 0);
    }

    static ToIntBiFunction<ByteBuffer, Integer> close(CloseReason reason) {
        return (out, index) -> FrameEncoder.close(out, index, EPOCH, reason, "test");
    }

    @SafeVarargs
    static ByteBuffer writeFrames(ToIntBiFunction<ByteBuffer, Integer>... frames) {
        ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
        int length = 0;
        for (ToIntBiFunction<ByteBuffer, Integer> frame : frames) {
            length += frame.applyAsInt(buffer, length);
        }
        return buffer.limit(length);
    }

    /**
     * Hands the connection the frames written by each encoder in one read, as the client does.
     */
    @SafeVarargs
    final void read(ToIntBiFunction<ByteBuffer, Integer>... frames) {
        connection.read(writeFrames(frames), new FrameDecoder(1 << 20));
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

    LeaderConnection checkingHourly() {
        return newConnection(0, TimeUnit.HOURS.toNanos(1));
    }

    LeaderConnection newConnection(int lane) {
        return newConnection(lane, TimeUnit.SECONDS.toNanos(1));
    }

    private LeaderConnection newConnection(int lane, long checkIntervalNanos) {
        return newConnection(lane, checkIntervalNanos, TestingKeyExchanges.CLEAR, false);
    }

    LeaderConnection newConnection(KeyExchange keyExchange) {
        return newConnection(0, TimeUnit.SECONDS.toNanos(1), keyExchange, false);
    }

    /**
     * As with a sink writer thread, whose network thread acknowledges what it reads as received.
     */
    LeaderConnection newSinkWriterConnection(KeyExchange keyExchange) {
        return newConnection(0, TimeUnit.SECONDS.toNanos(1), keyExchange, true);
    }

    private LeaderConnection newConnection(int lane, long checkIntervalNanos, KeyExchange keyExchange,
                                           boolean receivedAckedByNetworkThread) {
        latestKeyExchange = keyExchange;
        latest = new LeaderConnection(ioSession, replicaStreams, () -> EPOCH, "follower-b", lane, replicaStreams::setLanes,
                checkIntervalNanos, new OnNetworkThreadAnswerSender(ioSession), receivedAckedByNetworkThread, keyExchange);
        sentOpener = FrameCipher.CLEAR;
        return latest;
    }

    /**
     * An empty snapshot puts {@code main} in sync, its writes then cleared.
     */
    void complete() {
        read(snapshotBegin(0, "1"), snapshotEnd(0, "1"));
        main.getWrites().clear();
    }
}
