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

import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.replicos.CatchUp;
import org.lolaf.replicos.EncryptionMode;
import org.lolaf.replicos.follower.FollowerStreams;
import org.lolaf.replicos.spi.LatencyRecorder;
import org.lolaf.replicos.testkit.AbstractLeaderConnectionHarness;
import org.lolaf.replicos.testkit.TestingKeyExchanges;
import org.lolaf.replicos.wire.FrameEncoder;
import org.lolaf.replicos.wire.WireFormat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.ToIntBiFunction;

/**
 * A leader connection replicating into FIX sessions.
 */
abstract class FixLeaderConnectionHarness extends AbstractLeaderConnectionHarness {

    static final FixSessionSettings MAIN = RecordingEngine.session("main", FixSessionType.INITIATOR);
    static final String MESSAGE = "8=FIX.4.4\u00019=5\u000135=D\u000110=000\u0001";

    final RecordingEngine engine;
    final ReplicaSessions replicaSessions;

    FixLeaderConnectionHarness() {
        this(new RecordingEngine().with(MAIN));
    }

    private FixLeaderConnectionHarness(RecordingEngine engine) {
        this(engine, new SessionSettingsComparison(engine));
    }

    private FixLeaderConnectionHarness(RecordingEngine engine, SessionSettingsComparison settingsComparison) {
        super(new FollowerStreams(new FixSessionSinks(engine, settingsComparison), CatchUp.fullCopy(),
                LatencyRecorder.NONE),
                new FixRecordingHandler());
        this.engine = engine;
        this.replicaSessions = new ReplicaSessions(engine, settingsComparison, new FollowerStreamsView(followerStreams));
    }

    static ToIntBiFunction<ByteBuffer, Integer> welcome(long epoch, long resumeOffset) {
        return welcome(epoch, resumeOffset, 1);
    }

    static ToIntBiFunction<ByteBuffer, Integer> welcome(long epoch, long resumeOffset, int lanes) {
        return (out, index) -> FixFrames.welcome(out, index, epoch, "leader-a", LEADERSHIP, resumeOffset, lanes,
                EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE);
    }

    static ToIntBiFunction<ByteBuffer, Integer> session(int key, FixSessionSettings settings) {
        return session(key, settings, "leader-storage");
    }

    static ToIntBiFunction<ByteBuffer, Integer> session(int key, FixSessionSettings settings, String leaderStorageIdentity) {
        return (out, index) -> FixFrames.stream(out, index, EPOCH, key, settings.getFixSessionType(),
                settings.getHighAvailability().getReplicationMode(), settings.getFixSessionId().toString(), leaderStorageIdentity);
    }

    static ToIntBiFunction<ByteBuffer, Integer> event(long epoch, long offset, int key, StoreEventType type, long seq,
                                                      String message) {
        return (out, index) -> FixFrames.record(out, index, epoch, offset, key, type, seq,
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
        return (out, index) -> FixFrames.snapshotRecord(out, index, EPOCH, 0, seq,
                ByteBuffer.wrap(message.getBytes(StandardCharsets.US_ASCII)));
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

    long lostWrites() {
        HaSessionStatus.HaSessionStatusBuilder status = HaSessionStatus.builder();
        replicaSessions.describe(FixSessionType.INITIATOR, MAIN.getFixSessionId().toString(), status);
        return status.build().getLostWrites();
    }

    void complete() {
        read((out, index) -> FixFrames.snapshotBegin(out, index, EPOCH, 0, 0, 1, 1),
                (out, index) -> FixFrames.snapshotEnd(out, index, EPOCH, 0, 0, 1, 1));
        engine.store(MAIN).writes.clear();
        sent.frames.clear();
    }
}
