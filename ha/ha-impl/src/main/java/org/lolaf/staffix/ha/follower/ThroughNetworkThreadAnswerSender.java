package org.lolaf.staffix.ha.follower;

import org.lolaf.betty.api.io.IOSession;
import org.lolaf.ringos.rb.ByteFrameHandler;
import org.lolaf.ringos.rb.ByteRingBuffer;
import org.lolaf.ringos.rb.ByteRingBufferFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * From a store writer thread: betty's pool is the network thread's alone, so the answers queue here and a task on that
 * thread sends them, scheduled at most once at a time.
 */
public final class ThroughNetworkThreadAnswerSender implements AnswerSender, ByteFrameHandler, Runnable {

    private static final int CAPACITY = 64 << 10;

    private final IOSession ioSession;
    private final ByteRingBuffer answers = ByteRingBufferFactory.queue(CAPACITY);
    private final ByteBuffer answer = ByteBuffer.allocate(answers.getMaxPayloadLength());
    private final AtomicBoolean sendScheduled = new AtomicBoolean();

    ThroughNetworkThreadAnswerSender(IOSession ioSession) {
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
        ByteBuffer frame = ioSession.borrow(length);
        for (int i = 0; i < length; i++) {
            frame.put(i, buffer.get(index + i));
        }
        ioSession.send(frame.position(length), true);
    }
}
