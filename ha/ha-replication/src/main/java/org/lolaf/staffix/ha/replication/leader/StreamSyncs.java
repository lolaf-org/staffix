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
import org.lolaf.staffix.ha.replication.wire.StreamAnswer;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A lane's streams on one follower, by stream key: where each stands, and what the follower last answered for it,
 * which keeps a refusal's reason. Written by the connection's IO and sender threads, read by any; only growing
 * allocates.
 */
final class StreamSyncs {

    private static final int INITIAL_CAPACITY = 16;

    private volatile AtomicReferenceArray<StreamSyncState> states = new AtomicReferenceArray<>(INITIAL_CAPACITY);
    private volatile AtomicReferenceArray<StreamAnswer> answers = new AtomicReferenceArray<>(INITIAL_CAPACITY);

    /**
     * Null for a stream not announced to the follower.
     */
    @Nullable StreamSyncState get(int streamKey) {
        AtomicReferenceArray<StreamSyncState> current = states;
        return streamKey < current.length() ? current.get(streamKey) : null;
    }

    /**
     * Null until the follower answered the stream's announcement.
     */
    @Nullable StreamAnswer getAnswer(int streamKey) {
        AtomicReferenceArray<StreamAnswer> current = answers;
        return streamKey < current.length() ? current.get(streamKey) : null;
    }

    synchronized void set(int streamKey, StreamSyncState state) {
        growFor(streamKey);
        states.set(streamKey, state);
    }

    synchronized boolean compareAndSet(int streamKey, StreamSyncState expected, StreamSyncState state) {
        AtomicReferenceArray<StreamSyncState> current = states;
        return streamKey < current.length() && current.compareAndSet(streamKey, expected, state);
    }

    synchronized void answered(int streamKey, StreamAnswer answer) {
        growFor(streamKey);
        answers.set(streamKey, answer);
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

    private void growFor(int streamKey) {
        int capacity = states.length();
        if (streamKey >= capacity) {
            int grown = Math.max(streamKey + 1, capacity * 2);
            states = copy(states, grown);
            answers = copy(answers, grown);
        }
    }

    private static <T> AtomicReferenceArray<T> copy(AtomicReferenceArray<T> from, int capacity) {
        AtomicReferenceArray<T> copy = new AtomicReferenceArray<>(capacity);
        for (int i = 0; i < from.length(); i++) {
            copy.set(i, from.get(i));
        }
        return copy;
    }
}
