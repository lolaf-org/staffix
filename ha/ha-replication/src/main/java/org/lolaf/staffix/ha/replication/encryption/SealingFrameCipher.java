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
package org.lolaf.staffix.ha.replication.encryption;

import org.lolaf.staffix.ha.replication.spi.WireFormatException;
import org.lolaf.staffix.ha.replication.wire.*;

import java.nio.ByteBuffer;

/**
 * The cipher of an encrypted connection, as opposed to {@link FrameCipher#CLEAR}: which bytes of a frame are encrypted,
 * and the count of frames that makes each tag depend on its place in the stream.
 */
abstract class SealingFrameCipher implements FrameCipher {

    static final int TAG_BYTES = WireFormat.TAG_BYTES;

    /**
     * Frames sealed or opened so far in this direction.
     */
    private long nextFrameNumber;

    /**
     * The bytes of the frame authenticated but not encrypted: its header and fixed payload, or all of it for an
     * unencrypted message.
     */
    private static int clearBytes(ByteBuffer frames, int frameIndex, FrameType type, int contentLength) {
        return FrameDecoder.isPlaintext(frames, frameIndex, type)
                ? contentLength : WireFormat.HEADER_BYTES + type.getFixedPayloadBytes();
    }

    private static FrameType type(ByteBuffer frames, int frameIndex) {
        FrameType type = FrameType.forCode(frames.get(frameIndex + WireFormat.OFFSET_TYPE));
        if (type == null) {
            throw new WireFormatException("Unknown frame type " + (frames.get(frameIndex + WireFormat.OFFSET_TYPE) & 0xFF));
        }
        return type;
    }

    @Override
    public final int getTagBytes() {
        return TAG_BYTES;
    }

    @Override
    public final int seal(ByteBuffer frames, int frameIndex, int frameLength) {
        FrameType type = type(frames, frameIndex);
        int sealedLength = frameLength + TAG_BYTES;
        frames.putInt(frameIndex + WireFormat.OFFSET_LENGTH, sealedLength);
        seal(frames, frameIndex, clearBytes(frames, frameIndex, type, frameLength), frameLength, nextFrameNumber++);
        return sealedLength;
    }

    @Override
    public final int open(ByteBuffer frames, int frameIndex, int frameLength) {
        FrameType type = type(frames, frameIndex);
        int contentLength = frameLength - TAG_BYTES;
        if (contentLength < WireFormat.HEADER_BYTES + type.getFixedPayloadBytes()) {
            throw new WireFormatException(type + " of " + frameLength + " bytes is shorter than its fixed part and tag");
        }
        int clearBytes = clearBytes(frames, frameIndex, type, contentLength);
        if (!open(frames, frameIndex, clearBytes, contentLength, nextFrameNumber++)) {
            throw new WireFormatException(type + " fails its authentication tag");
        }
        return contentLength;
    }

    /**
     * Encrypts the frame's bytes past {@code clearBytes} in place and writes the tag after {@code contentLength}.
     */
    abstract void seal(ByteBuffer frames, int frameIndex, int clearBytes, int contentLength, long frameNumber);

    /**
     * @return whether the tag after {@code contentLength} verifies; the bytes past {@code clearBytes} are then
     * decrypted in place
     */
    abstract boolean open(ByteBuffer frames, int frameIndex, int clearBytes, int contentLength, long frameNumber);
}
