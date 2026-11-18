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
package org.lolaf.staffix.ha.follower;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.idling.IdleStrategy;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * The threads writing what a follower's lanes read into its stores, off their network threads, lane {@code i} on
 * thread {@code i % count}, so a lane's writes keep their order.
 */
final class StoreWriterThreads {

    private final StoreWriterThread[] threads;

    StoreWriterThreads(int count, Supplier<IdleStrategy> idleStrategy, String nodeId) {
        threads = new StoreWriterThread[count];
        for (int i = 0; i < count; i++) {
            threads[i] = new StoreWriterThread(idleStrategy.get(), "staffix-ha-store-writer-" + nodeId + "-" + i);
        }
    }

    StoreWriterThread forLane(int lane) {
        return threads[lane % threads.length];
    }

    /**
     * Once every lane was drained and removed.
     */
    void stop() {
        for (StoreWriterThread thread : threads) {
            thread.stop();
        }
    }

    /**
     * A lane's frames, queued by its network thread for a store writer thread.
     */
    interface WriteQueue {
        /**
         * @return how many queued frames it took, 0 for none
         */
        int writeQueued();
    }

    @Slf4j
    static final class StoreWriterThread implements Runnable {

        private static final WriteQueue[] NONE = new WriteQueue[0];

        private final IdleStrategy idle;
        private final Thread thread;
        private volatile WriteQueue[] writeQueues = NONE;
        private volatile boolean running = true;

        StoreWriterThread(IdleStrategy idle, String name) {
            this.idle = idle;
            this.thread = new Thread(this, name);
            thread.setDaemon(true);
            thread.start();
        }

        synchronized void add(WriteQueue writeQueue) {
            WriteQueue[] added = Arrays.copyOf(writeQueues, writeQueues.length + 1);
            added[writeQueues.length] = writeQueue;
            writeQueues = added;
        }

        synchronized void remove(WriteQueue writeQueue) {
            writeQueues = Arrays.stream(writeQueues).filter(other -> other != writeQueue).toArray(WriteQueue[]::new);
        }

        void wakeup() {
            idle.wakeup();
        }

        boolean isCurrentThread() {
            return Thread.currentThread() == thread;
        }

        @Override
        public void run() {
            idle.assignToThread(thread);
            idle.reset();
            while (running) {
                int framesTaken = 0;
                for (WriteQueue writeQueue : writeQueues) {
                    try {
                        framesTaken += writeQueue.writeQueued();
                    } catch (RuntimeException e) {
                        log.error("Replication store writer thread {} failed on a lane", thread.getName(), e);
                    }
                }
                idle.idle(framesTaken);
            }
        }

        void stop() {
            running = false;
            idle.wakeup();
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
