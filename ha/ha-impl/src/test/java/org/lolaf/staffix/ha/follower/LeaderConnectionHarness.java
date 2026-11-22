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

import org.lolaf.betty.api.io.IOSession;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.EncryptionMode;
import org.lolaf.staffix.ha.encryption.KeyExchange;
import org.lolaf.staffix.ha.encryption.TestingKeyExchanges;
import org.lolaf.staffix.ha.wire.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntBiFunction;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A leader connection over a mocked IO session, fed the frames a leader would send.
 */
abstract class LeaderConnectionHarness {

    static final long EPOCH = 7;
    static final FixSessionSettings MAIN = RecordingEngine.session("main", FixSessionType.INITIATOR);
    static final int FIRST = WireFormat.MORE_FRAGMENTS;
    static final int MIDDLE = WireFormat.MORE_FRAGMENTS | WireFormat.CONTINUED_FRAGMENT;
    static final int LAST = WireFormat.CONTINUED_FRAGMENT;
    static final String MESSAGE = "8=FIX.4.4\u00019=5\u000135=D\u000110=000\u0001";

    final IOSession ioSession;
    final RecordingHandler sent;
    final RecordingEngine engine;
    final ReplicaSessions replicaSessions;
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
        this.engine = new RecordingEngine().with(MAIN);
        this.replicaSessions = new ReplicaSessions(engine);
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
        return (out, index) -> TestingFrameEncoder.welcome(out, index, epoch, "leader-a", resumeOffset, lanes, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
    }

    static ToIntBiFunction<ByteBuffer, Integer> session(int key, FixSessionSettings settings) {
        return session(key, settings, "leader-storage");
    }

    static ToIntBiFunction<ByteBuffer, Integer> session(int key, FixSessionSettings settings, String leaderStorageIdentity) {
        return (out, index) -> TestingFrameEncoder.stream(out, index, EPOCH, key, settings.getFixSessionType(),
                settings.getHighAvailability().getReplicationMode(), settings.getFixSessionId().toString(), leaderStorageIdentity);
    }

    static ToIntBiFunction<ByteBuffer, Integer> announced(int count) {
        return (out, index) -> FrameEncoder.streamsAnnounced(out, index, EPOCH, count);
    }

    static ToIntBiFunction<ByteBuffer, Integer> event(long epoch, long offset, int key, StoreEventType type, long seq,
                                                      String message) {
        return (out, index) -> TestingFrameEncoder.record(out, index, epoch, offset, key, type, seq,
                message == null ? null : ByteBuffer.wrap(message.getBytes(StandardCharsets.US_ASCII)));
    }

    static ToIntBiFunction<ByteBuffer, Integer> fragment(long offset, int key, long seq, String text, int flags) {
        return (out, index) -> {
            ByteBuffer message = ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
            int body = FrameEncoder.recordBody(out, index + WireFormat.RECORD_BODY_OFFSET, key,
                    StoreEventType.STORE_MESSAGE.getCode(), (byte) flags, seq, message, 0, message.remaining());
            return FrameEncoder.recordHeader(out, index, EPOCH, offset, body);
        };
    }

    static ToIntBiFunction<ByteBuffer, Integer> snapshotFragment(long seq, String text, int flags) {
        return (out, index) -> {
            ByteBuffer message = ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
            return FrameEncoder.snapshotRecord(out, index, EPOCH, 0, StoreEventType.STORE_MESSAGE.getCode(),
                    (byte) flags, seq, message, 0, message.remaining());
        };
    }

    static ToIntBiFunction<ByteBuffer, Integer> snapshotMessage(long seq, String message) {
        return (out, index) -> TestingFrameEncoder.snapshotRecord(out, index, EPOCH, 0, seq,
                ByteBuffer.wrap(message.getBytes(StandardCharsets.US_ASCII)));
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
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN), announced(1));
        sent.frames.clear();
    }

    void streamingSyncReceived() {
        FixSessionSettings syncReceived = MAIN.toBuilder()
                .highAvailability(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.SYNC_RECEIVED).build())
                .build();
        engine.replace(syncReceived);
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, syncReceived), announced(1));
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
     * As with a store writer thread, whose network thread acknowledges what it reads as received.
     */
    LeaderConnection newStoreWriterConnection(KeyExchange keyExchange) {
        return newConnection(0, TimeUnit.SECONDS.toNanos(1), keyExchange, true);
    }

    private LeaderConnection newConnection(int lane, long checkIntervalNanos, KeyExchange keyExchange) {
        return newConnection(lane, checkIntervalNanos, keyExchange, false);
    }

    private LeaderConnection newConnection(int lane, long checkIntervalNanos, KeyExchange keyExchange,
                                           boolean receivedAckedByNetworkThread) {
        latestKeyExchange = keyExchange;
        latest = new LeaderConnection(ioSession, replicaSessions, () -> EPOCH, "follower-b", lane, replicaSessions::setLanes,
                checkIntervalNanos, new OnNetworkThreadAnswerSender(ioSession), receivedAckedByNetworkThread, keyExchange);
        sentOpener = FrameCipher.CLEAR;
        return latest;
    }

    long lostWrites() {
        HaSessionStatus.HaSessionStatusBuilder status = HaSessionStatus.builder();
        replicaSessions.describe(FixSessionType.INITIATOR, MAIN.getFixSessionId().toString(), status);
        return status.build().getLostWrites();
    }

    void complete() {
        read((out, index) -> TestingFrameEncoder.snapshotBegin(out, index, EPOCH, 0, 0, 1, 1),
                (out, index) -> TestingFrameEncoder.snapshotEnd(out, index, EPOCH, 0, 0, 1, 1));
        engine.store(MAIN).writes.clear();
    }
}
