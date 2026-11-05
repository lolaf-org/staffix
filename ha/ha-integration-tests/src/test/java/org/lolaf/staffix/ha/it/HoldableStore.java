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
package org.lolaf.staffix.ha.it;

import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A session store whose writes can be held, stalling a follower that applies them on its network thread.
 */
@RequiredArgsConstructor
class HoldableStore implements FixSessionMessagesStore {

    private interface Held {
        void storeNextIncomingSeqNum(long nextIncomingSeqNum);

        void storeMessageSent(long outgoingSeqNum, ByteBuffer message);

        void resetSequenceNumbers();
    }

    @Delegate(excludes = Held.class)
    private final FixSessionMessagesStore delegate;
    private final AtomicInteger resets = new AtomicInteger();
    private volatile CountDownLatch writesHeld = new CountDownLatch(0);

    void hold() {
        writesHeld = new CountDownLatch(1);
    }

    void release() {
        writesHeld.countDown();
    }

    /**
     * A snapshot starts by resetting the follower's copy.
     */
    int resets() {
        return resets.get();
    }

    @Override
    public void resetSequenceNumbers() {
        resets.incrementAndGet();
        delegate.resetSequenceNumbers();
    }

    @Override
    public void storeNextIncomingSeqNum(long nextIncomingSeqNum) {
        awaitRelease();
        delegate.storeNextIncomingSeqNum(nextIncomingSeqNum);
    }

    @Override
    public void storeMessageSent(long outgoingSeqNum, ByteBuffer message) {
        awaitRelease();
        delegate.storeMessageSent(outgoingSeqNum, message);
    }

    private void awaitRelease() {
        try {
            writesHeld.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
