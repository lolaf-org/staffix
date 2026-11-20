package org.lolaf.staffix.ha.follower;

import lombok.RequiredArgsConstructor;
import org.lolaf.betty.api.io.IOSession;

import java.nio.ByteBuffer;

/**
 * On the connection's network thread, straight into a buffer of betty's pool.
 */
@RequiredArgsConstructor
public final class OnNetworkThreadAnswerSender implements AnswerSender {

    private final IOSession ioSession;

    @Override
    public ByteBuffer claim(int bytes) {
        return ioSession.borrow(bytes);
    }

    @Override
    public void send(ByteBuffer frame, int length) {
        ioSession.send(frame.clear().position(length), true);
    }
}
