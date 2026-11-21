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
package org.lolaf.staffix.ha.encryption;

import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.wire.*;

import java.nio.ByteBuffer;

/**
 * What every cipher family shares: which frames are sealed, which of their bytes are encrypted, and the count of
 * frames that makes each tag depend on its place in the stream.
 */
abstract class SealingFrameCipher implements FrameCipher {

    static final int TAG_BYTES = WireFormat.TAG_BYTES;

    /**
     * Frames sealed or opened so far in this direction.
     */
    private long frames;

    /**
     * The bytes of the frame authenticated but not encrypted: its header and fixed payload, or all of it for an
     * unencrypted message.
     */
    private static int clearBytes(ByteBuffer frames, int frameIndex, FrameType type, int contentLength) {
        return FrameDecoder.isUnencryptedMessage(frames, frameIndex, type)
                ? contentLength : WireFormat.HEADER_BYTES + type.getFixedPayloadBytes();
    }

    private static @Nullable FrameType sealedType(ByteBuffer frames, int frameIndex) {
        FrameType type = FrameType.forCode(frames.get(frameIndex + WireFormat.OFFSET_TYPE));
        return type != null && type.isSealed() ? type : null;
    }

    @Override
    public final int getTagBytes() {
        return TAG_BYTES;
    }

    @Override
    public final int seal(ByteBuffer frames, int frameIndex, int frameLength) {
        FrameType type = sealedType(frames, frameIndex);
        if (type == null) {
            return frameLength;
        }
        int sealedLength = frameLength + TAG_BYTES;
        frames.putInt(frameIndex + WireFormat.OFFSET_LENGTH, sealedLength);
        seal(frames, frameIndex, clearBytes(frames, frameIndex, type, frameLength), frameLength, this.frames++);
        return sealedLength;
    }

    @Override
    public final int open(ByteBuffer frames, int frameIndex, int frameLength) {
        FrameType type = sealedType(frames, frameIndex);
        if (type == null) {
            return frameLength;
        }
        int contentLength = frameLength - TAG_BYTES;
        if (contentLength < WireFormat.HEADER_BYTES + type.getFixedPayloadBytes()) {
            throw new WireFormatException(type + " of " + frameLength + " bytes is shorter than its fixed part and tag");
        }
        if (!open(frames, frameIndex, clearBytes(frames, frameIndex, type, contentLength), contentLength, this.frames++)) {
            throw new WireFormatException(type + " fails its authentication tag");
        }
        return contentLength;
    }

    /**
     * Encrypts the frame's bytes past {@code clearBytes} in place and writes the tag after {@code contentLength}.
     */
    abstract void seal(ByteBuffer frames, int frameIndex, int clearBytes, int contentLength, long frame);

    /**
     * @return whether the tag after {@code contentLength} verifies; the bytes past {@code clearBytes} are then
     * decrypted in place
     */
    abstract boolean open(ByteBuffer frames, int frameIndex, int clearBytes, int contentLength, long frame);
}
