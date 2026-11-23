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

import lombok.Getter;
import org.lolaf.staffix.ha.replication.spi.LeaderStream;
import org.lolaf.staffix.ha.replication.spi.LeaderStreams;
import org.lolaf.staffix.ha.replication.wire.FrameCipher;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.wire.FrameType;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Announces to one follower the streams of its lane ahead of their records: each once in key order, then
 * STREAMS_ANNOUNCED, then again any whose replication mode changed. Sender thread, but for {@link #reannounce}.
 */
final class StreamAnnouncer {

    private final FollowerConnection connection;
    private final LeaderStreams streams;
    private final StreamSyncs syncs;
    private final SnapshotSender snapshots;
    /**
     * Keys of streams already announced whose replication mode changed since.
     */
    private final Queue<Integer> reannouncements;
    /**
     * The next stream key to announce, every key below it announced.
     */
    @Getter
    private int nextToAnnounce;
    /**
     * The STREAM frames of the first announcement pass, and whether its STREAMS_ANNOUNCED went out.
     */
    private int firstPassAnnounced;
    private boolean streamsAnnounced;

    StreamAnnouncer(FollowerConnection connection, LeaderStreams streams, StreamSyncs syncs, SnapshotSender snapshots) {
        this.reannouncements = new ConcurrentLinkedQueue<>();
        this.connection = connection;
        this.streams = streams;
        this.syncs = syncs;
        this.snapshots = snapshots;
    }

    /**
     * Fills {@code dst} from index 0, up to its limit, with what the follower has yet to be announced.
     *
     * @return the bytes filled
     */
    int announce(ByteBuffer dst, long currentEpoch, FrameCipher sealer) {
        int filled = 0;
        while (nextToAnnounce < streams.count()) {
            if (connection.carries(nextToAnnounce)) {
                int bytes = streamFrame(dst, filled, currentEpoch, streams.get(nextToAnnounce), sealer);
                if (bytes == 0) {
                    return filled;
                }
                filled += bytes;
                if (!streamsAnnounced) {
                    firstPassAnnounced++;
                }
            }
            nextToAnnounce++;
        }
        if (!streamsAnnounced) {
            if (dst.limit() - filled < FrameEncoder.fixedBytes(FrameType.STREAMS_ANNOUNCED)) {
                return filled;
            }
            filled += sealer.seal(dst, filled, FrameEncoder.streamsAnnounced(dst, filled, currentEpoch, firstPassAnnounced));
            streamsAnnounced = true;
        }
        Integer key;
        while ((key = reannouncements.peek()) != null) {
            // a key not announced yet goes out with its current mode once it is
            if (key < nextToAnnounce && connection.carries(key)) {
                int bytes = streamFrame(dst, filled, currentEpoch, streams.get(key), sealer);
                if (bytes == 0) {
                    return filled;
                }
                filled += bytes;
                if (snapshots.isOf(key)) {
                    snapshots.cancel();
                }
            }
            reannouncements.poll();
        }
        return filled;
    }

    /**
     * @return the bytes written, 0 when the frame does not fit
     */
    private int streamFrame(ByteBuffer dst, int index, long currentEpoch, LeaderStream stream, FrameCipher sealer) {
        String name = stream.getStreamName();
        String storageIdentity = stream.getStorageIdentity();
        if (dst.limit() - index < FrameEncoder.bytesWith(FrameType.STREAM, name, storageIdentity)) {
            return 0;
        }
        syncs.set(stream.getKey(), StreamSyncState.ANNOUNCED);
        return sealer.seal(dst, index, FrameEncoder.stream(dst, index, currentEpoch, stream.getKey(),
                stream.getStreamReplicationMode(), name, storageIdentity));
    }

    /**
     * The stream's replication mode changed: the follower stops holding it, catches up, or checks its own settings
     * agree. Any thread.
     */
    void reannounce(int streamKey) {
        reannouncements.add(streamKey);
    }
}
