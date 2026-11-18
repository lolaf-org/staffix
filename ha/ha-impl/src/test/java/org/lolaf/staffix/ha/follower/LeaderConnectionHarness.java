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
import org.lolaf.staffix.ha.wire.CloseReason;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.TestingFrameEncoder;
import org.lolaf.staffix.ha.wire.FrameHandler;
import org.lolaf.staffix.ha.wire.RecordingHandler;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntBiFunction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    final IOSession ioSession = mock(IOSession.class);
    final RecordingHandler sent = new RecordingHandler();
    final RecordingEngine engine = new RecordingEngine().with(MAIN);
    final ReplicaSessions replicaSessions = new ReplicaSessions(engine);
    final LeaderConnection connection = newConnection(0, 0);

    LeaderConnectionHarness() {
        when(ioSession.borrow(anyInt())).then(invocation -> ByteBuffer.allocate(invocation.getArgument(0)));
        doAnswer(invocation -> {
            ByteBuffer frames = invocation.getArgument(0);
            decode(frames.duplicate().flip(), sent);
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
     * Hands the connection the frames written by each encoder in one read, as the client does.
     */
    @SafeVarargs
    final void read(ToIntBiFunction<ByteBuffer, Integer>... frames) {
        connection.read(writeFrames(frames), new FrameDecoder(1 << 20));
    }

    static ToIntBiFunction<ByteBuffer, Integer> welcome(long epoch, long resumeOffset) {
        return welcome(epoch, resumeOffset, 1);
    }

    static ToIntBiFunction<ByteBuffer, Integer> welcome(long epoch, long resumeOffset, int lanes) {
        return (out, index) -> FrameEncoder.welcome(out, index, epoch, "leader-a", resumeOffset, lanes);
    }

    static ToIntBiFunction<ByteBuffer, Integer> session(int key, FixSessionSettings settings) {
        return session(key, settings, "leader-storage");
    }

    static ToIntBiFunction<ByteBuffer, Integer> session(int key, FixSessionSettings settings, String leaderStorageIdentity) {
        return (out, index) -> FrameEncoder.session(out, index, EPOCH, key, settings.getFixSessionType(),
                settings.getHighAvailability().getReplicationMode(), settings.getFixSessionId().toString(), leaderStorageIdentity);
    }

    static ToIntBiFunction<ByteBuffer, Integer> event(long epoch, long offset, int key, StoreEventType type, long seq,
                                                              String message) {
        return (out, index) -> TestingFrameEncoder.storeEvent(out, index, epoch, offset, key, type, seq,
                message == null ? null : ByteBuffer.wrap(message.getBytes(StandardCharsets.US_ASCII)));
    }

    static ToIntBiFunction<ByteBuffer, Integer> fragment(long offset, int key, long seq, String text, int flags) {
        return (out, index) -> {
            ByteBuffer message = ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
            int body = FrameEncoder.storeEventBody(out, index + WireFormat.STORE_EVENT_BODY_OFFSET, key,
                    StoreEventType.STORE_MESSAGE, seq, message, 0, message.remaining(), (byte) flags);
            return FrameEncoder.storeEventHeader(out, index, EPOCH, offset, body);
        };
    }

    static ToIntBiFunction<ByteBuffer, Integer> snapshotFragment(long seq, String text, int flags) {
        return (out, index) -> {
            ByteBuffer message = ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
            return FrameEncoder.snapshotMessage(out, index, EPOCH, 0, seq, message, 0, message.remaining(), (byte) flags);
        };
    }

    static ToIntBiFunction<ByteBuffer, Integer> snapshotMessage(long seq, String message) {
        return (out, index) -> TestingFrameEncoder.snapshotMessage(out, index, EPOCH, 0, seq,
                ByteBuffer.wrap(message.getBytes(StandardCharsets.US_ASCII)));
    }

    void streaming() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN));
        sent.frames.clear();
    }

    void streamingSyncReceived() {
        FixSessionSettings syncReceived = MAIN.toBuilder()
                .highAvailability(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.SYNC_RECEIVED).build())
                .build();
        engine.replace(syncReceived);
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, syncReceived));
        sent.frames.clear();
    }

    LeaderConnection checkingHourly() {
        return newConnection(0, TimeUnit.HOURS.toNanos(1));
    }

    LeaderConnection newConnection(int lane) {
        return newConnection(lane, LeaderConnection.CHECK_INTERVAL_NANOS);
    }

    private LeaderConnection newConnection(int lane, long checkIntervalNanos) {
        return new LeaderConnection(ioSession, replicaSessions, () -> EPOCH, "follower-b", lane, replicaSessions::setLanes,
                checkIntervalNanos, new AnswerSender.OnNetworkThread(ioSession), false);
    }

    static ToIntBiFunction<ByteBuffer, Integer> heartbeat() {
        return (out, index) -> FrameEncoder.heartbeat(out, index, EPOCH, 0);
    }

    long lostWrites() {
        HaSessionStatus.HaSessionStatusBuilder status = HaSessionStatus.builder();
        replicaSessions.describe(FixSessionType.INITIATOR, MAIN.getFixSessionId().toString(), status);
        return status.build().getLostWrites();
    }

    void complete() {
        read((out, index) -> FrameEncoder.snapshotBegin(out, index, EPOCH, 0, 0, 1, 1),
                (out, index) -> FrameEncoder.snapshotEnd(out, index, EPOCH, 0, 0, 1, 1));
        engine.store(MAIN).writes.clear();
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
}
