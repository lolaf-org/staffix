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

import org.lolaf.betty.api.io.IOSession;
import org.lolaf.ringos.rb.ByteFrameHandler;
import org.lolaf.ringos.rb.ByteRingBuffer;
import org.lolaf.ringos.rb.ByteRingBufferFactory;
import org.lolaf.staffix.ha.wire.FrameCipher;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * From a store writer thread: betty's pool is the network thread's alone, so the answers queue here and a task on that
 * thread sends them, scheduled at most once at a time.
 */
public final class ThroughNetworkThreadAnswerSender implements AnswerSender, ByteFrameHandler, Runnable {

    private static final int CAPACITY = 64 << 10;
    private static final int ANSWER = 0;
    private static final int KEY_CONFIRMATION = 1;

    private final IOSession ioSession;
    private final ByteRingBuffer answers = ByteRingBufferFactory.queue(CAPACITY);
    private final ByteBuffer answer = ByteBuffer.allocate(answers.getMaxPayloadLength());
    private final AtomicBoolean sendScheduled = new AtomicBoolean();
    /**
     * Network thread only, from the answer after the KEY_CONFIRMATION.
     */
    private FrameCipher sealer;
    /**
     * Set by the store writer thread before it queues the KEY_CONFIRMATION.
     */
    private volatile FrameCipher nextSealer;

    ThroughNetworkThreadAnswerSender(IOSession ioSession) {
        this.ioSession = ioSession;
        this.sealer = FrameCipher.CLEAR;
        this.nextSealer = FrameCipher.CLEAR;
    }

    @Override
    public void sendKeyConfirmation(ByteBuffer frame, int length, FrameCipher sealer) {
        nextSealer = sealer;
        queue(KEY_CONFIRMATION, frame, length);
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
        queue(ANSWER, frame, length);
    }

    private void queue(int type, ByteBuffer frame, int length) {
        while (!answers.write(type, frame, 0, length)) {
            scheduleSend();
            Thread.onSpinWait();
        }
        scheduleSend();
    }

    private void scheduleSend() {
        if (sendScheduled.compareAndSet(false, true)) {
            // refused only once the connection is gone, and its answers with it
            ioSession.processTask(this, this::discardTaskResult);
        }
    }

    private void discardTaskResult(Runnable r, Exception e) {
        // don t care
    }

    @Override
    public void run() {
        sendScheduled.set(false);
        answers.read(this, Integer.MAX_VALUE);
    }

    @Override
    public void onFrame(int type, ByteBuffer buffer, int index, int length) {
        ByteBuffer frame = ioSession.borrow(length + WireFormat.TAG_BYTES);
        for (int i = 0; i < length; i++) {
            frame.put(i, buffer.get(index + i));
        }
        ioSession.send(frame.position(sealer.seal(frame, 0, length)), true);
        if (type == KEY_CONFIRMATION) {
            sealer = nextSealer;
        }
    }
}
