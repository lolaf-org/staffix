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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.ha.replication.spi.LatencyRecorder;
import org.lolaf.staffix.ha.replication.spi.ReplicaBinder;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;
import org.lolaf.staffix.ha.replication.wire.WireFormat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * What the follower holds of the leader's streams, kept across connections so a reconnection can resume: each
 * stream's sink, bound by the {@link ReplicaBinder}, whether it is in sync, and each lane's {@link LanePositions
 * position}.
 *
 * <p>A stream is in sync once a snapshot filled it, and until a lane fails to resume. A snapshot starts the copy over
 * before filling it, so nothing older than the leader's survives; the log may then bring records the snapshot already
 * held, which the sink is free to skip.
 */
@Slf4j
public final class FollowerStreams {

    private final ReplicaBinder binder;
    private final LanePositions positions;
    private volatile BoundStream[] streamsByKey;
    /**
     * Times a read's writes after its records were acknowledged as received.
     */
    @Getter(AccessLevel.PACKAGE)
    @Setter
    private LatencyRecorder receivedWriteLatency;

    public FollowerStreams(ReplicaBinder binder) {
        this.positions = new LanePositions();
        this.streamsByKey = new BoundStream[16];
        this.receivedWriteLatency = LatencyRecorder.NONE;
        this.binder = binder;
    }

    public int getApplicationId() {
        return binder.getApplicationId();
    }

    public int getLanes() {
        return positions.count();
    }

    /**
     * The leader's lane count, from its WELCOME. A lane's position is kept while the count does not change.
     */
    void setLanes(int lanes) {
        positions.setCount(lanes);
    }

    public boolean carries(int lane, int key) {
        return key % positions.count() == lane;
    }

    public long getLastAppliedEpoch(int lane) {
        return positions.getLastAppliedEpoch(lane);
    }

    /**
     * The offset in the lane's log of the last record written, -1 for none.
     */
    public long getLastAppliedOffset(int lane) {
        return positions.getLastAppliedOffset(lane);
    }

    /**
     * The offset in the lane's log of the last record received, written or not yet, when the read held a record of a
     * {@code SYNC_RECEIVED} stream since the last call, else -1: only such a write waits for it. The lane's thread only.
     */
    long takeReceivedToAck(int lane) {
        return positions.takeReceivedToAck(lane);
    }

    /**
     * Starts a lane's stream, ending any handover of its streams: one that did not resume leaves each a gap only a
     * snapshot fills, and nothing to resume from.
     */
    void streamStarted(int lane, boolean resumed) {
        forEachOf(lane, stream -> stream.streamStarted(resumed));
        positions.streamStarted(lane, resumed);
    }

    /**
     * The lane's connection has announced every stream the leader replicated when it started.
     */
    void streamsAnnounced(int lane) {
        positions.streamsAnnounced(lane);
    }

    /**
     * The leader stopped cleanly after sending every record of the lane, so each of its streams complete here holds
     * exactly what the leader last wrote. Holds until the lane's next connection.
     */
    void handedOver(int lane) {
        forEachOf(lane, BoundStream::handOver);
    }

    /**
     * Every stream the leader replicates is in sync here: one refused is missing, although nothing is replicated
     * into it, and so is one a lane still announcing has not named yet. A follower that never streamed has nothing
     * missing.
     */
    public boolean isInSync() {
        if (positions.isAnyAnnouncing()) {
            return false;
        }
        for (BoundStream stream : streamsByKey) {
            if (stream != null && stream.isOutOfSync()) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@link #isInSync()} for the streams of one lane. Allocates nothing.
     */
    boolean isLaneInSync(int lane) {
        return !positions.isAnnouncing(lane) && !anyOf(lane, BoundStream::isOutOfSync);
    }

    /**
     * The lane's thread calls these on every read: given a method reference, which captures nothing, they allocate
     * nothing.
     */
    private void forEachOf(int lane, Consumer<BoundStream> action) {
        BoundStream[] streams = streamsByKey;
        int lanes = positions.count();
        for (int key = lane; key < streams.length; key += lanes) {
            if (streams[key] != null) {
                action.accept(streams[key]);
            }
        }
    }

    private boolean anyOf(int lane, Predicate<BoundStream> matching) {
        BoundStream[] streams = streamsByKey;
        int lanes = positions.count();
        for (int key = lane; key < streams.length; key += lanes) {
            if (streams[key] != null && matching.test(streams[key])) {
                return true;
            }
        }
        return false;
    }

    private long sumOf(int lane, ToLongFunction<BoundStream> value) {
        BoundStream[] streams = streamsByKey;
        int lanes = positions.count();
        long sum = 0;
        for (int key = lane; key < streams.length; key += lanes) {
            if (streams[key] != null) {
                sum += value.applyAsLong(streams[key]);
            }
        }
        return sum;
    }

    /**
     * Binds the leader's key to the stream of that name, unless the leader does not replicate it. A stream refused is
     * looked at again on the next connection, the binder's answer having changed perhaps. One the leader no longer
     * replicates, or with another mode here, has its sink stopped, and is caught up again by snapshot once both agree
     * it is replicated.
     */
    synchronized StreamAnswer bind(int key, String streamName, StreamReplicationMode leaderMode,
                                   @Nullable String leaderStorageIdentity) {
        BoundStream current = key < streamsByKey.length ? streamsByKey[key] : null;
        if (current != null && current.getStreamName().equals(streamName) && current.isReplicated()
                && leaderMode != StreamReplicationMode.NONE && leaderMode == binder.modeHere(streamName)) {
            // switched between the SYNC modes: the same copy, only its acknowledgement changes
            current.setLeaderMode(leaderMode);
            return current.answer();
        }
        if (current != null) {
            current.stop(Deadline.unlimited());
        }
        if (leaderMode == StreamReplicationMode.NONE) {
            log.info("Stream {} is not replicated: the leader does not replicate it", streamName);
            return put(key, BoundStream.notReplicated(streamName, leaderMode, StreamAnswer.NOT_REPLICATED, null));
        }
        return put(key, open(streamName, leaderMode, leaderStorageIdentity));
    }

    private BoundStream open(String streamName, StreamReplicationMode leaderMode, @Nullable String leaderStorageIdentity) {
        ReplicaBinder.Binding binding = binder.bind(streamName, leaderMode, leaderStorageIdentity);
        return binding.getSink() == null
                ? BoundStream.notReplicated(streamName, leaderMode, binding.getAnswer(), leaderStorageIdentity)
                : BoundStream.replicated(streamName, leaderMode, binding.getSink());
    }

    /**
     * Republishes the array, so a thread reading the streams of another lane sees the one put.
     */
    private StreamAnswer put(int key, BoundStream stream) {
        BoundStream[] streams = streamsByKey;
        if (key >= streams.length) {
            streams = Arrays.copyOf(streams, Math.max(key + 1, streams.length * 2));
        }
        streams[key] = stream;
        streamsByKey = streams;
        return stream.answer();
    }

    /**
     * What the sinks of the lane's streams have accepted and not written yet. Allocates nothing.
     */
    long getPendingWrites(int lane) {
        return sumOf(lane, BoundStream::getPendingWrites);
    }

    /**
     * Binds again each stream refused as unknown that this node's configuration now has, or for a mode mismatch it
     * now agrees with: a follower's application may configure its streams after the leader announced them, and a
     * change may reach this node after the leader. Either would otherwise stay refused until the next connection.
     *
     * @param onBound told each key of the lane bound now, with its answer for the leader
     */
    synchronized void bindAgain(int lane, ObjIntConsumer<StreamAnswer> onBound) {
        int lanes = positions.count();
        for (int key = lane; key < streamsByKey.length; key += lanes) {
            BoundStream refused = streamsByKey[key];
            if (refused == null || !refused.isRefusedUntilConfigurationChange() || !nowAgrees(refused)) {
                continue;
            }
            StreamAnswer answer = put(key, open(refused.getStreamName(), refused.getLeaderMode(),
                    refused.getLeaderStorageIdentity()));
            log.info("Stream {} is now configured here as on the leader: {}", refused.getStreamName(), answer);
            onBound.accept(answer, key);
        }
    }

    private boolean nowAgrees(BoundStream refused) {
        StreamReplicationMode here = binder.modeHere(refused.getStreamName());
        return here != null && (refused.getNotReplicatedAnswer() == StreamAnswer.UNKNOWN_STREAM
                || here == refused.getLeaderMode());
    }

    boolean hasStreamsToBindAgain(int lane) {
        return anyOf(lane, BoundStream::isRefusedUntilConfigurationChange);
    }

    /**
     * @return false for a key the leader never bound, or of another lane
     */
    boolean apply(int lane, long epoch, long offset, int key, byte contentType, byte flags, long position,
                  ByteBuffer receiveBuffer, int payloadIndex, int payloadLength) {
        BoundStream stream = stream(lane, key);
        if (stream == null || !stream.apply(contentType, position, receiveBuffer, payloadIndex, payloadLength,
                fragmentFlags(flags))) {
            return false;
        }
        positions.applied(lane, epoch, offset, stream.getLeaderMode() == StreamReplicationMode.SYNC_RECEIVED);
        return true;
    }

    /**
     * @return false for a key the leader never bound, of another lane, or not replicated here
     */
    boolean snapshotBegin(int lane, int key, ByteBuffer state, int stateIndex, int stateLength) {
        BoundStream stream = replicatedStream(lane, key);
        if (stream == null) {
            return false;
        }
        stream.snapshotBegin(state, stateIndex, stateLength);
        return true;
    }

    boolean snapshotRecord(int lane, int key, byte contentType, byte flags, long position, ByteBuffer receiveBuffer,
                           int payloadIndex, int payloadLength) {
        BoundStream stream = replicatedStream(lane, key);
        return stream != null && stream.snapshotRecord(contentType, position, receiveBuffer, payloadIndex,
                payloadLength, fragmentFlags(flags));
    }

    /**
     * @return false when the stream is unknown or the count is not what was received
     */
    boolean snapshotEnd(int lane, int key, int recordCount, ByteBuffer state, int stateIndex, int stateLength) {
        BoundStream stream = replicatedStream(lane, key);
        return stream != null && stream.snapshotEnd(recordCount, state, stateIndex, stateLength);
    }

    private static byte fragmentFlags(byte flags) {
        return (byte) (flags & (WireFormat.MORE_FRAGMENTS | WireFormat.CONTINUED_FRAGMENT));
    }

    /**
     * Writes what the frames of a lane's read left pending, before it is acknowledged, then counts their records
     * applied: a write failing leaves them unapplied, so a reconnection resumes before them.
     */
    void writeApplied(int lane) {
        forEachOf(lane, BoundStream::writePending);
        positions.written(lane);
    }

    /**
     * Whether the leader waits for this follower to receive the stream's records, not to apply them. Any thread: the
     * network thread reads it for a lane applied on another.
     */
    boolean acksOnReceipt(int lane, int key) {
        BoundStream stream = stream(lane, key);
        return stream != null && stream.getLeaderMode() == StreamReplicationMode.SYNC_RECEIVED;
    }

    private @Nullable BoundStream stream(int lane, int key) {
        BoundStream[] streams = streamsByKey;
        return key < streams.length && carries(lane, key) ? streams[key] : null;
    }

    private @Nullable BoundStream replicatedStream(int lane, int key) {
        BoundStream stream = stream(lane, key);
        return stream == null || !stream.isReplicated() ? null : stream;
    }

    /**
     * Null for a stream the leader never announced. Any thread.
     */
    public @Nullable BoundStream find(String streamName) {
        for (BoundStream stream : streamsByKey) {
            if (stream != null && stream.getStreamName().equals(streamName)) {
                return stream;
            }
        }
        return null;
    }

    /**
     * Stops every sink, before the application takes over its streams.
     */
    public synchronized void close(Deadline deadline) {
        for (BoundStream stream : streamsByKey) {
            if (stream != null) {
                stream.stop(deadline);
            }
        }
        Arrays.fill(streamsByKey, null);
    }
}
