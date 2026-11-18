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
package org.lolaf.staffix.stores.messages.file;

import java.lang.invoke.VarHandle;
import java.util.Arrays;

/**
 * Where each message of a session's file starts, in the order written, so that a find reads only the messages it
 * returns instead of the whole file. Written by the session's thread, read by finds on any thread.
 *
 * <p>A ring of entries, a slot reused only once the message it names left the file, else the ring grows: it so holds
 * every message still in the file. An entry is a seqlock read against its writer, and its message is still checked in
 * the file, which may have overwritten it since. The ring stays sorted by seqnum, which a find's binary search needs:
 * a message written at or below the last seqnum, only after an outgoing seqnum was lowered without a reset, is merged
 * into a copy, replacing the entry of the same seqnum. Entries of overwritten messages are skipped from the oldest on,
 * as a find from below the file's oldest message would otherwise read each of them to reject it.
 */
final class SeqNumIndex {

    private static final int INITIAL_CAPACITY = 256;
    private static final long NONE = -1;

    private final InFile inFile;
    private volatile Entries entries = new Entries(INITIAL_CAPACITY);
    private long lastSeqNum = Long.MIN_VALUE;

    /**
     * Whether a message, by its seqnum and where it starts, is still in the file.
     */
    @FunctionalInterface
    interface InFile {
        boolean holds(long seqNum, int position);
    }

    /**
     * Receives the entries a find reads.
     */
    @FunctionalInterface
    interface EntryConsumer {
        /**
         * @return whether to carry on
         */
        boolean onEntry(long seqNum, int position);
    }

    SeqNumIndex(InFile inFile) {
        this.inFile = inFile;
    }

    /**
     * The file was emptied. Writer only.
     */
    void clear() {
        entries = new Entries(INITIAL_CAPACITY);
        lastSeqNum = Long.MIN_VALUE;
    }

    /**
     * A message written, after every message written before it. Writer only.
     */
    void add(long seqNum, int position) {
        Entries current = entries;
        if (seqNum <= lastSeqNum) {
            entries = current.merging(seqNum, position);
            return;
        }
        lastSeqNum = seqNum;
        if (current.isFull() && current.oldestStillIn(inFile)) {
            current = current.grown();
            entries = current;
        }
        current.append(seqNum, position);
    }

    /**
     * Skips the oldest entries whose message the file no longer holds, up to the first it still does. Writer only.
     */
    void dropOverwritten() {
        entries.dropOverwritten(inFile);
    }

    /**
     * Hands over, in ascending seqnum, the entries from {@code start} to {@code stop} while the consumer asks for more.
     */
    void find(long start, long stop, EntryConsumer consumer) {
        Entries current = entries;
        long count = current.count;
        long low = Math.max(current.firstHeld, count - current.capacity());
        long from = current.firstAtOrAfter(start, low, count);
        for (long number = from; number < count; number++) {
            long seqNum = current.seqNumOf(number);
            if (seqNum == NONE) {
                continue;
            }
            if (seqNum > stop) {
                return;
            }
            int position = current.positionOf(number, seqNum);
            if (position >= 0 && !consumer.onEntry(seqNum, position)) {
                return;
            }
        }
    }

    /**
     * The entry of number {@code n} sits in slot {@code n & mask}; {@code numbers} tells which entry a slot holds, -1
     * while it is being written.
     */
    private static final class Entries {

        private final long[] seqNums;
        private final int[] positions;
        private final long[] numbers;
        private final int mask;
        private volatile long count;
        private volatile long firstHeld;

        Entries(int capacity) {
            seqNums = new long[capacity];
            positions = new int[capacity];
            numbers = new long[capacity];
            Arrays.fill(numbers, NONE);
            mask = capacity - 1;
        }

        int capacity() {
            return mask + 1;
        }

        boolean isFull() {
            return count >= capacity();
        }

        void dropOverwritten(InFile inFile) {
            long last = count;
            long number = Math.max(firstHeld, last - capacity());
            while (number < last) {
                int slot = (int) (number & mask);
                if (inFile.holds(seqNums[slot], positions[slot])) {
                    break;
                }
                number++;
            }
            firstHeld = number;
        }

        boolean oldestStillIn(InFile inFile) {
            int slot = (int) (count & mask);
            return inFile.holds(seqNums[slot], positions[slot]);
        }

        /**
         * A copy twice the size, holding the same entries, which no write touches after.
         */
        Entries grown() {
            Entries grown = new Entries(capacity() * 2);
            long last = count;
            for (long number = Math.max(0, last - capacity()); number < last; number++) {
                int from = (int) (number & mask);
                int to = (int) (number & grown.mask);
                grown.seqNums[to] = seqNums[from];
                grown.positions[to] = positions[from];
                grown.numbers[to] = number;
            }
            grown.count = last;
            grown.firstHeld = firstHeld;
            return grown;
        }

        /**
         * A sorted copy holding the entry too, in place of one of the same seqnum, which no write touches after.
         */
        Entries merging(long seqNum, int position) {
            long last = count;
            long first = Math.max(firstHeld, last - capacity());
            Entries merged = new Entries(last - first < capacity() ? capacity() : capacity() * 2);
            boolean added = false;
            for (long number = first; number < last; number++) {
                int slot = (int) (number & mask);
                if (!added && seqNums[slot] >= seqNum) {
                    merged.append(seqNum, position);
                    added = true;
                }
                if (seqNums[slot] != seqNum) {
                    merged.append(seqNums[slot], positions[slot]);
                }
            }
            if (!added) {
                merged.append(seqNum, position);
            }
            return merged;
        }

        void append(long seqNum, int position) {
            long number = count;
            int slot = (int) (number & mask);
            numbers[slot] = NONE;
            VarHandle.storeStoreFence();
            seqNums[slot] = seqNum;
            positions[slot] = position;
            VarHandle.storeStoreFence();
            numbers[slot] = number;
            count = number + 1;
        }

        /**
         * @return the entry's seqnum, {@link #NONE} when its slot was reused or is being written
         */
        long seqNumOf(long number) {
            int slot = (int) (number & mask);
            if (numbers[slot] != number) {
                return NONE;
            }
            VarHandle.loadLoadFence();
            long seqNum = seqNums[slot];
            VarHandle.loadLoadFence();
            return numbers[slot] == number ? seqNum : NONE;
        }

        /**
         * @return where the entry's message starts, -1 when its slot was reused since its seqnum was read
         */
        int positionOf(long number, long seqNum) {
            int slot = (int) (number & mask);
            int position = positions[slot];
            VarHandle.loadLoadFence();
            return numbers[slot] == number && seqNums[slot] == seqNum ? position : -1;
        }

        /**
         * The first entry from {@code low} with a seqnum at or after {@code start}; one whose slot was reused counts as
         * before it, a slot being reused for the oldest entry only.
         */
        long firstAtOrAfter(long start, long low, long high) {
            while (low < high) {
                long middle = (low + high) >>> 1;
                long seqNum = seqNumOf(middle);
                if (seqNum == NONE || seqNum < start) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low;
        }
    }
}
