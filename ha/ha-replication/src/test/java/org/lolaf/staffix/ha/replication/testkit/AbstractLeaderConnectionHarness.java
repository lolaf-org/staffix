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
package org.lolaf.staffix.ha.replication.testkit;

import org.lolaf.betty.api.io.IOSession;
import org.lolaf.staffix.ha.replication.encryption.KeyExchange;
import org.lolaf.staffix.ha.replication.follower.FollowerStreams;
import org.lolaf.staffix.ha.replication.follower.LeaderConnection;
import org.lolaf.staffix.ha.replication.follower.OnNetworkThreadAnswerSender;
import org.lolaf.staffix.ha.replication.wire.CloseReason;
import org.lolaf.staffix.ha.replication.wire.FrameCipher;
import org.lolaf.staffix.ha.replication.wire.FrameDecoder;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.wire.FrameHandler;
import org.lolaf.staffix.ha.replication.wire.FrameType;
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
 * A follower's connection to a leader over a mocked IO session, fed the frames a leader would send, whatever the
 * application its streams are applied into. What the connection sends is recorded as the leader reads it.
 */
public abstract class AbstractLeaderConnectionHarness {

    protected static final long EPOCH = 7;
    protected static final int FIRST = WireFormat.MORE_FRAGMENTS;
    protected static final int MIDDLE = WireFormat.MORE_FRAGMENTS | WireFormat.CONTINUED_FRAGMENT;
    protected static final int LAST = WireFormat.CONTINUED_FRAGMENT;

    protected final IOSession ioSession;
    protected final RecordingHandler sent;
    protected final FollowerStreams followerStreams;
    protected final LeaderConnection connection;
    private final FrameDecoder sentDecoder;
    private FrameCipher sentOpener;
    private KeyExchange latestKeyExchange;
    private byte[] leaderNonce;

    protected AbstractLeaderConnectionHarness(FollowerStreams followerStreams, RecordingHandler sent) {
        this.ioSession = mock(IOSession.class);
        this.sent = sent;
        this.sentDecoder = new FrameDecoder(1 << 20);
        this.sentOpener = FrameCipher.CLEAR;
        this.leaderNonce = TestingKeyExchanges.NO_NONCE;
        this.followerStreams = followerStreams;
        this.connection = newConnection(0, 0);
        when(ioSession.borrow(anyInt())).then(invocation -> ByteBuffer.allocate(invocation.getArgument(0)));
        doAnswer(invocation -> {
            ByteBuffer frames = invocation.getArgument(0);
            decodeSent(frames.duplicate().flip());
            return null;
        }).when(ioSession).send(any(ByteBuffer.class), anyBoolean());
    }

    protected static void decode(ByteBuffer frames, FrameHandler handler) {
        FrameDecoder decoder = new FrameDecoder(1 << 20);
        int index = frames.position();
        int consumed;
        while (index < frames.limit() && (consumed = decoder.decode(frames, index, frames.limit() - index, handler)) > 0) {
            index += consumed;
        }
    }

    /**
     * As the leader reads them: every frame after the KEY_CONFIRMATION opened, with the keys the leader derives from
     * the HELLO's nonce and its WELCOME's.
     */
    private void decodeSent(ByteBuffer frames) {
        int index = frames.position();
        int consumed;
        while (index < frames.limit() && (consumed = sentDecoder.decode(frames, index, frames.limit() - index, sent, sentOpener)) > 0) {
            if (frames.get(index + WireFormat.OFFSET_TYPE) == FrameType.KEY_CONFIRMATION.getCode()) {
                sentOpener = latestKeyExchange.newOpener(
                        latestKeyExchange.deriveKeys(sent.lastNonce, leaderNonce).getFollowerToLeader());
            }
            index += consumed;
        }
    }

    protected static ToIntBiFunction<ByteBuffer, Integer> announced(int count) {
        return (out, index) -> FrameEncoder.streamsAnnounced(out, index, EPOCH, count);
    }

    protected static ToIntBiFunction<ByteBuffer, Integer> heartbeat() {
        return (out, index) -> FrameEncoder.heartbeat(out, index, EPOCH, 0);
    }

    protected static ToIntBiFunction<ByteBuffer, Integer> close(CloseReason reason) {
        return (out, index) -> FrameEncoder.close(out, index, EPOCH, reason, "test");
    }

    @SafeVarargs
    protected static ByteBuffer writeFrames(ToIntBiFunction<ByteBuffer, Integer>... frames) {
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
    protected final void read(ToIntBiFunction<ByteBuffer, Integer>... frames) {
        read(connection, frames);
    }

    @SafeVarargs
    protected final void read(LeaderConnection leaderConnection, ToIntBiFunction<ByteBuffer, Integer>... frames) {
        ByteBuffer written = writeFrames(frames);
        noteLeaderNonce(written);
        leaderConnection.read(written, new FrameDecoder(1 << 20));
    }

    private void noteLeaderNonce(ByteBuffer frames) {
        if (frames.remaining() == 0 || frames.get(frames.position() + WireFormat.OFFSET_TYPE) != FrameType.WELCOME.getCode()) {
            return;
        }
        RecordingHandler welcome = new RecordingHandler(0);
        new FrameDecoder(1 << 20).decode(frames, frames.position(), frames.getInt(frames.position()), welcome);
        leaderNonce = welcome.lastNonce;
    }

    protected LeaderConnection checkingHourly() {
        return newConnection(0, TimeUnit.HOURS.toNanos(1));
    }

    protected LeaderConnection newConnection(int lane) {
        return newConnection(lane, TimeUnit.SECONDS.toNanos(1));
    }

    private LeaderConnection newConnection(int lane, long checkIntervalNanos) {
        return newConnection(lane, checkIntervalNanos, TestingKeyExchanges.CLEAR, false);
    }

    protected LeaderConnection newConnection(KeyExchange keyExchange) {
        return newConnection(0, TimeUnit.SECONDS.toNanos(1), keyExchange, false);
    }

    /**
     * As with a sink writer thread, whose network thread acknowledges what it reads as received.
     */
    protected LeaderConnection newSinkWriterConnection(KeyExchange keyExchange) {
        return newConnection(0, TimeUnit.SECONDS.toNanos(1), keyExchange, true);
    }

    private LeaderConnection newConnection(int lane, long checkIntervalNanos, KeyExchange keyExchange,
                                           boolean receivedAckedByNetworkThread) {
        latestKeyExchange = keyExchange;
        sentOpener = FrameCipher.CLEAR;
        return new LeaderConnection(ioSession, followerStreams, () -> EPOCH, "follower-b", lane, followerStreams::setLanes,
                checkIntervalNanos, new OnNetworkThreadAnswerSender(ioSession), receivedAckedByNetworkThread, keyExchange);
    }
}
