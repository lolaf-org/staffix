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
import org.lolaf.staffix.ha.wire.FrameCipher;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;

/**
 * On the connection's network thread, straight into a buffer of betty's pool.
 */
public final class OnNetworkThreadAnswerSender implements AnswerSender {

    private final IOSession ioSession;
    private FrameCipher sealer;

    public OnNetworkThreadAnswerSender(IOSession ioSession) {
        this.ioSession = ioSession;
        this.sealer = FrameCipher.CLEAR;
    }

    @Override
    public ByteBuffer claim(int bytes) {
        return ioSession.borrow(bytes + WireFormat.TAG_BYTES);
    }

    @Override
    public void send(ByteBuffer frame, int length) {
        ioSession.send(frame.clear().position(sealer.seal(frame, 0, length)), true);
    }

    @Override
    public void sendKeyConfirmation(ByteBuffer frame, int length, FrameCipher sealer) {
        send(frame, length);
        this.sealer = sealer;
    }
}
