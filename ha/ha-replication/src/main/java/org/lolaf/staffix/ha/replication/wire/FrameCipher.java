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
package org.lolaf.staffix.ha.replication.wire;

import org.lolaf.staffix.ha.replication.spi.RecordFlag;

import java.nio.ByteBuffer;

/**
 * Seals the frames of one direction of a connection, or opens them, in place. Sealing a frame appends a
 * {@link WireFormat#TAG_BYTES} tag, counted in its length, over every byte of it and its place in the direction's
 * frames, so a frame altered, replayed, dropped or reordered fails it; it also encrypts the variable part, unless the
 * message is flagged {@link RecordFlag#PLAINTEXT}. Opening verifies the tag and decrypts.
 *
 * <p>One thread at a time, in the order the frames are sent: each instance counts the frames it sealed or opened.
 */
public interface FrameCipher {

    /**
     * A clear connection's, and an encrypted one's during the handshake: no tag, nothing touched.
     */
    FrameCipher CLEAR = new FrameCipher() {
        @Override
        public int getTagBytes() {
            return 0;
        }

        @Override
        public int seal(ByteBuffer frames, int frameIndex, int frameLength) {
            return frameLength;
        }

        @Override
        public int open(ByteBuffer frames, int frameIndex, int frameLength) {
            return frameLength;
        }
    };

    /**
     * The room a sender leaves after each frame, 0 on a clear connection.
     */
    int getTagBytes();

    /**
     * @param frameLength the frame as encoded, {@link #getTagBytes()} of room after it
     * @return its length on the wire
     */
    int seal(ByteBuffer frames, int frameIndex, int frameLength);

    /**
     * @param frameLength as its length field says
     * @return its length without the tag, its content decrypted in place
     * @throws WireFormatException when the tag does not verify, the frame's bytes then garbage
     */
    int open(ByteBuffer frames, int frameIndex, int frameLength);
}
