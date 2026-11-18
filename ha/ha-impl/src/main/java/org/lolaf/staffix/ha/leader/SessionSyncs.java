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

import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.wire.SessionAnswer;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A lane's sessions on one follower, by session key: where each stands, and what the follower last answered for it,
 * which keeps a refusal's reason. Written by the connection's IO and sender threads, read by any; only growing
 * allocates.
 */
final class SessionSyncs {

    private static final int INITIAL_CAPACITY = 16;

    private volatile AtomicReferenceArray<SessionSyncState> states = new AtomicReferenceArray<>(INITIAL_CAPACITY);
    private volatile AtomicReferenceArray<SessionAnswer> answers = new AtomicReferenceArray<>(INITIAL_CAPACITY);

    /**
     * Null for a session not announced to the follower.
     */
    @Nullable SessionSyncState get(int sessionKey) {
        AtomicReferenceArray<SessionSyncState> current = states;
        return sessionKey < current.length() ? current.get(sessionKey) : null;
    }

    /**
     * Null until the follower answered the session's announcement.
     */
    @Nullable SessionAnswer getAnswer(int sessionKey) {
        AtomicReferenceArray<SessionAnswer> current = answers;
        return sessionKey < current.length() ? current.get(sessionKey) : null;
    }

    synchronized void set(int sessionKey, SessionSyncState state) {
        growFor(sessionKey);
        states.set(sessionKey, state);
    }

    synchronized boolean compareAndSet(int sessionKey, SessionSyncState expected, SessionSyncState state) {
        AtomicReferenceArray<SessionSyncState> current = states;
        return sessionKey < current.length() && current.compareAndSet(sessionKey, expected, state);
    }

    synchronized void answered(int sessionKey, SessionAnswer answer) {
        growFor(sessionKey);
        answers.set(sessionKey, answer);
    }

    boolean isSnapshotInProgress(int sessionCount) {
        for (int key = 0; key < sessionCount; key++) {
            SessionSyncState state = get(key);
            if (state == SessionSyncState.SNAPSHOT_PENDING || state == SessionSyncState.SNAPSHOT_SENT) {
                return true;
            }
        }
        return false;
    }

    private void growFor(int sessionKey) {
        int capacity = states.length();
        if (sessionKey >= capacity) {
            int grown = Math.max(sessionKey + 1, capacity * 2);
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
