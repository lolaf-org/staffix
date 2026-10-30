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
package org.lolaf.staffix.stores.messages.testkit;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.stores.FixMessagesStore;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * For a store written on the session thread, which must allocate nothing once warmed up: storing a message and moving
 * either seqnum. Each measure is retried, the fewest bytes kept, so only an allocation every attempt makes fails it.
 */
public abstract class AbstractAllocationFreeFixSessionMessagesStoreTest<T extends FixMessagesStore.FixSessionMessagesStore>
        extends AbstractFixSessionMessagesStoreTest<T> {

    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int WARM_UP = 20_000;
    private static final int MEASURED = 2_000;
    private static final int ATTEMPTS = 3;
    private static final byte[] MESSAGE = ("8=FIX.4.4\u00019=60\u000135=D\u000134=1\u000149=SENDER\u000156=TARGET\u0001"
            + "11=ORDER-1\u000155=EURUSD\u000154=1\u000138=1000000\u000140=1\u000110=000\u0001").getBytes(StandardCharsets.US_ASCII);

    private long nextSeqNum = 1;

    @Test
    void storingAMessageAllocatesNothing() {
        messageStore.start();
        ByteBuffer message = ByteBuffer.wrap(MESSAGE);

        assertThat(allocatedBytes(seqNum -> messageStore.storeMessageSent(seqNum, message.position(0)))).isZero();
    }

    @Test
    void movingTheIncomingSeqNumAllocatesNothing() {
        messageStore.start();

        assertThat(allocatedBytes(messageStore::storeNextIncomingSeqNum)).isZero();
    }

    @Test
    void movingTheOutgoingSeqNumAllocatesNothing() {
        messageStore.start();

        assertThat(allocatedBytes(messageStore::storeNextOutgoingSeqNum)).isZero();
    }

    /**
     * @return the fewest bytes {@code write} allocated over {@link #MEASURED} calls, each with the next seqnum
     */
    private long allocatedBytes(LongConsumer write) {
        repeat(write, WARM_UP);
        long fewest = Long.MAX_VALUE;
        for (int attempt = 0; attempt < ATTEMPTS && fewest > 0; attempt++) {
            long start = allocatedByThisThread();
            long measuringCost = allocatedByThisThread() - start;
            long before = allocatedByThisThread();
            repeat(write, MEASURED);
            fewest = Math.min(fewest, allocatedByThisThread() - before - measuringCost);
        }
        return fewest;
    }

    private void repeat(LongConsumer write, int count) {
        for (int i = 0; i < count; i++) {
            write.accept(nextSeqNum++);
        }
    }

    private static long allocatedByThisThread() {
        return THREADS.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
}
