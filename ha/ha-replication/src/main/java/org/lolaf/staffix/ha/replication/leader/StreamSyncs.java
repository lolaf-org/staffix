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

import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * A lane's streams on one follower, by stream key: where each stands, and what the follower last answered for it,
 * which keeps a refusal's reason. Only the sender thread grows it, as it announces a stream; a stream is written from
 * then on by the connection's IO and sender threads, and read by any, without a lock: growing copies the streams, never
 * replaces one, so no write is lost to it. Only growing allocates.
 */
final class StreamSyncs {

    private static final int INITIAL_CAPACITY = 16;

    private volatile StreamSync[] streams;

    StreamSyncs() {
        this.streams = grown(new StreamSync[0], INITIAL_CAPACITY);
    }

    /**
     * Null for a stream not announced to the follower.
     */
    @Nullable StreamSyncState get(int streamKey) {
        StreamSync[] current = streams;
        return streamKey < current.length ? current[streamKey].state : null;
    }

    /**
     * Null until the follower answered the stream's announcement.
     */
    @Nullable StreamAnswer getAnswer(int streamKey) {
        StreamSync[] current = streams;
        return streamKey < current.length ? current[streamKey].answer : null;
    }

    /**
     * Sender thread only.
     */
    void announced(int streamKey) {
        StreamSync[] current = streams;
        if (streamKey >= current.length) {
            current = grown(current, Math.max(streamKey + 1, current.length * 2));
            streams = current;
        }
        current[streamKey].state = StreamSyncState.ANNOUNCED;
    }

    /**
     * Only for an announced stream.
     */
    void set(int streamKey, StreamSyncState state) {
        streams[streamKey].state = state;
    }

    boolean compareAndSet(int streamKey, StreamSyncState expected, StreamSyncState state) {
        StreamSync[] current = streams;
        return streamKey < current.length && current[streamKey].compareAndSet(expected, state);
    }

    /**
     * Only for an announced stream.
     */
    void answered(int streamKey, StreamAnswer answer) {
        streams[streamKey].answer = answer;
    }

    boolean isSnapshotInProgress(int streamCount) {
        for (int key = 0; key < streamCount; key++) {
            StreamSyncState state = get(key);
            if (state == StreamSyncState.SNAPSHOT_PENDING || state == StreamSyncState.SNAPSHOT_SENT) {
                return true;
            }
        }
        return false;
    }

    private static StreamSync[] grown(StreamSync[] from, int capacity) {
        StreamSync[] grown = Arrays.copyOf(from, capacity);
        for (int key = from.length; key < capacity; key++) {
            grown[key] = new StreamSync();
        }
        return grown;
    }

    private static final class StreamSync {

        private static final AtomicReferenceFieldUpdater<StreamSync, StreamSyncState> STATE =
                AtomicReferenceFieldUpdater.newUpdater(StreamSync.class, StreamSyncState.class, "state");

        private volatile @Nullable StreamSyncState state;
        private volatile @Nullable StreamAnswer answer;

        boolean compareAndSet(StreamSyncState expected, StreamSyncState changed) {
            return STATE.compareAndSet(this, expected, changed);
        }
    }
}
