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

import lombok.RequiredArgsConstructor;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.ringos.rb.ByteFrameHandler;
import org.lolaf.ringos.rb.ByteRingBuffer;
import org.lolaf.ringos.rb.ByteRingBufferFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * How a {@link LeaderConnection}'s answers to the leader reach its connection: a frame is encoded from index 0 of the buffer claimed, then
 * sent.
 */
interface AnswerSender {

    ByteBuffer claim(int bytes);

    void send(ByteBuffer frame, int length);

    /**
     * On the connection's network thread, straight into a buffer of betty's pool.
     */
    @RequiredArgsConstructor
    final class OnNetworkThread implements AnswerSender {

        private final IOSession ioSession;

        @Override
        public ByteBuffer claim(int bytes) {
            return ioSession.borrow(bytes).clear();
        }

        @Override
        public void send(ByteBuffer frame, int length) {
            ioSession.send(frame.clear().position(length), true);
        }
    }

    /**
     * From a store writer thread: betty's pool is the network thread's alone, so the answers queue here and a task on that
     * thread sends them, scheduled at most once at a time.
     */
    final class ThroughNetworkThread implements AnswerSender, ByteFrameHandler, Runnable {

        private static final int CAPACITY = 64 << 10;

        private final IOSession ioSession;
        private final ByteRingBuffer answers = ByteRingBufferFactory.queue(CAPACITY);
        private final ByteBuffer answer = ByteBuffer.allocate(answers.getMaxPayloadLength());
        private final AtomicBoolean sendScheduled = new AtomicBoolean();

        ThroughNetworkThread(IOSession ioSession) {
            this.ioSession = ioSession;
        }

        @Override
        public ByteBuffer claim(int bytes) {
            if (bytes > answer.capacity()) {
                throw new IllegalArgumentException("An answer of " + bytes + " bytes is longer than the "
                        + answer.capacity() + " a store writer thread can queue");
            }
            return answer.clear();
        }

        @Override
        public void send(ByteBuffer frame, int length) {
            while (!answers.write(0, frame, 0, length)) {
                scheduleSend();
                Thread.onSpinWait();
            }
            scheduleSend();
        }

        private void scheduleSend() {
            if (sendScheduled.compareAndSet(false, true)) {
                ioSession.processTask(this);
            }
        }

        @Override
        public void run() {
            sendScheduled.set(false);
            answers.read(this, Integer.MAX_VALUE);
        }

        @Override
        public void onFrame(int type, ByteBuffer buffer, int index, int length) {
            ByteBuffer frame = ioSession.borrow(length).clear();
            for (int i = 0; i < length; i++) {
                frame.put(i, buffer.get(index + i));
            }
            ioSession.send(frame.position(length), true);
        }
    }
}
