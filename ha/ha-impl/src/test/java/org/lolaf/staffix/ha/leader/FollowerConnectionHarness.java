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
package org.lolaf.staffix.ha.leader;

import org.lolaf.betty.api.io.IOSession;
import org.lolaf.betty.api.io.IOWriter.MessageSentCallback;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreMessagesConsumer;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.ha.ReplicationFixEngineHa;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.ha.follower.RecordingProbes;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.RecordingHandler;
import org.lolaf.staffix.tests.TestingClock;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A follower connection over a mocked IO session, whose frames sent are decoded as a follower would.
 */
abstract class FollowerConnectionHarness {

    static final long EPOCH = 7;
    static final String SESSION = "default.main:VERSION_44:SENDER->TARGET";
    static final int MESSAGE_BYTES = 1_000;

    final IOSession ioSession = mock(IOSession.class);
    final RecordingHandler sent = new RecordingHandler();
    ReplicationStats stats = new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingClock()));
    /**
     * The batches sent, whose write betty has not reported yet while {@link #holdingWrites}.
     */
    final Queue<Runnable> unwrittenBatches = new ConcurrentLinkedQueue<>();
    final AtomicInteger batchesSent = new AtomicInteger();
    volatile boolean holdingWrites;
    ReplicationFixEngineHa ha = engine(1 << 16);
    FollowerConnection connection = connection();

    FollowerConnectionHarness() {
        when(ioSession.borrow(anyInt())).then(invocation -> ByteBuffer.allocate(invocation.getArgument(0)));
        doAnswer(invocation -> {
            ByteBuffer frames = invocation.getArgument(0);
            decodeAll(frames.duplicate().flip());
            return null;
        }).when(ioSession).send(any(ByteBuffer.class), anyBoolean());
        doAnswer(invocation -> {
            ByteBuffer frames = invocation.getArgument(0);
            MessageSentCallback<Object> callback = invocation.getArgument(2);
            if (!holdingWrites) {
                decodeAll(frames.duplicate().flip());
            }
            batchesSent.incrementAndGet();
            Runnable written = () -> {
                // as betty: flipped for the callback, its position and limit restored once it returns
                frames.flip();
                int position = frames.position();
                int limit = frames.limit();
                callback.onMessageWriteCallback(frames, null, invocation.getArgument(1));
                frames.limit(limit).position(position);
            };
            if (holdingWrites) {
                unwrittenBatches.add(written);
            } else {
                written.run();
            }
            return null;
        }).when(ioSession).send(any(ByteBuffer.class), any(), any(), anyBoolean());
    }

    static ReplicationFixEngineHa engine(int logCapacity) {
        return new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(logCapacity)
                .logFullTimeout(Duration.ofMillis(50)).build());
    }

    FollowerConnection connection() {
        return new FollowerConnection(ioSession, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a", Duration.ofMillis(100),
                Duration.ZERO, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(), stats,
                loop -> {
                });
    }

    static FixSessionSettings settings(String name) {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(name, FixRegularVersion.VERSION_44, "SENDER", "TARGET"))
                .fixSessionType(FixSessionType.INITIATOR)
                .highAvailability(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.SYNC_APPLIED).build())
                .build();
    }

    static FixSessionMessagesStore startedStore() {
        FixSessionMessagesStore store = mock(FixSessionMessagesStore.class);
        when(store.isStarted()).thenReturn(true);
        return store;
    }

    FixSessionMessagesStore replicated(String name) {
        return ha.replicatingStore(settings(name), startedStore(), new RecordingProbes());
    }

    /**
     * As a follower would, refusing a frame longer than the log's longest event.
     */
    void decodeAll(ByteBuffer frames) {
        FrameDecoder decoder = new FrameDecoder(ha.getReplicationLogs().get(0).getMaxEventFrameBytes());
        int index = frames.position();
        int consumed;
        while (index < frames.limit() && (consumed = decoder.decode(frames, index, frames.limit() - index, sent)) > 0) {
            index += consumed;
        }
    }

    void fill() {
        fill(1 << 20);
    }

    void fill(int batchBytes) {
        ByteBuffer batch = ByteBuffer.allocate(batchBytes);
        int filled = connection.fillBatch(batch);
        decodeAll(batch.limit(filled));
    }

    /**
     * Starts the pending snapshot and sends its chunks until it ends, as the sender loop would with no live writes.
     */
    void snapshot() {
        connection.startPendingSnapshot();
        while (connection.isSnapshotInProgress() && connection.getState() == FollowerConnection.State.STREAMING) {
            connection.sendSnapshotChunk();
        }
    }

    FollowerConnection connectionStallingAfter(Duration stallTimeout, Thread[] sender) {
        return new FollowerConnection(ioSession, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a",
                Duration.ofHours(1), stallTimeout, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(), stats, loop -> {
            sender[0] = new Thread(loop);
            sender[0].start();
        });
    }

    FollowerConnection senderRunningConnection() {
        return senderRunningConnection(Integer.MAX_VALUE);
    }

    FollowerConnection senderRunningConnection(int snapshotChunkBytes) {
        return new FollowerConnection(ioSession, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a", Duration.ofHours(1),
                Duration.ZERO, 4, snapshotChunkBytes, new BackoffIdleStrategy(), stats, loop -> new Thread(loop).start());
    }

    /**
     * A started store holding the messages up to {@code outgoing - 1}, each {@code MESSAGE_BYTES} long, its next
     * outgoing seqnum answered from {@code outgoing} one call after the other, the last answer repeated.
     */
    static FixSessionMessagesStore storeHolding(Long first, Long... next) {
        FixSessionMessagesStore store = startedStore();
        when(store.getIncomingSeqNum()).thenReturn(21L);
        when(store.getOutgoingSeqNum()).thenReturn(first, next);
        doAnswer(invocation -> {
            long stop = invocation.getArgument(1);
            StoreMessagesConsumer consumer = invocation.getArgument(2);
            for (long seqNum = invocation.getArgument(0); seqNum <= stop; seqNum++) {
                if (!consumer.onMessage(seqNum, ByteBuffer.wrap(new byte[MESSAGE_BYTES]))) {
                    return null;
                }
            }
            return null;
        }).when(store).find(anyLong(), anyLong(), any(StoreMessagesConsumer.class));
        return store;
    }

    List<Long> snapshotSeqNums() {
        return sent.frames.stream().filter(frame -> frame.startsWith("SNAPSHOT_MESSAGE"))
                .map(frame -> Long.parseLong(frame.replaceAll(".* seq=(\\d+) .*", "$1"))).collect(Collectors.toList());
    }
}
