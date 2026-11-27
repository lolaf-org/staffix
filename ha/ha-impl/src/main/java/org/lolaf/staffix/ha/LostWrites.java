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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;

/**
 * Tells a failed write that resuming will cure from one that stops a session alone. With the resource down the stream
 * resumes before the write once it is back, so nothing is lost; with it up the same write would fail again at each
 * resume and hold back every session of the lane, so it is given up, as a session gives up a message its store refuses.
 * A store stopped under the replica fails the stream too: its writes are not lost to a storage problem but to a bug.
 */
@Slf4j
@RequiredArgsConstructor
final class LostWrites {

    private final FixSessionMessagesStore store;
    private final String fixSessionId;
    private volatile long count;
    private long failures;

    /**
     * @param messages those given up, 0 for a sequence number update
     * @throws RuntimeException {@code failure}, when the resource is down or the store stopped
     */
    void failed(int messages, RuntimeException failure) {
        if (!store.isStarted() || !store.isUnderlyingStorageResourceAvailable()) {
            throw failure;
        }
        count += messages;
        String given = messages == 0 ? "a sequence number update" : messages == 1 ? "1 message" : messages + " messages";
        if (failures++ == 0) {
            log.error("FIX session {} gives up {} its store failed to write with its resource up", fixSessionId, given,
                    failure);
        } else {
            log.error("FIX session {} gives up {} its store failed to write with its resource up, {} times since its last"
                    + " reset: {}", fixSessionId, given, failures, failure.toString());
        }
    }

    long getCount() {
        return count;
    }

    void reset() {
        count = 0;
        failures = 0;
    }
}
