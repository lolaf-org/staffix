/*
 * Copyright © 2026 Lolaf.org
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;

/**
 * Tells a failed write that resuming will cure from one that stops a session alone. With the resource down the stream
 * resumes before the write once it is back, so nothing is lost; with it up the same write would fail again at each
 * resume and hold back every session of the link, so it is given up, as a session gives up a message its store refuses.
 */
@Slf4j
@RequiredArgsConstructor
final class LostWrites {

    private final FixSessionMessagesStore store;
    private final String fixSessionId;
    private volatile long count;

    /**
     * @param messages those given up, 0 for a sequence number the store keeps in memory
     * @throws RuntimeException {@code failure}, when the resource is down
     */
    void failed(int messages, RuntimeException failure) {
        if (!store.isUnderlyingStorageResourceAvailable()) {
            throw failure;
        }
        count += messages;
        log.error("FIX session {} gives up {} messages its store failed to write with its resource up", fixSessionId, messages, failure);
    }

    long getCount() {
        return count;
    }

    void reset() {
        count = 0;
    }
}
