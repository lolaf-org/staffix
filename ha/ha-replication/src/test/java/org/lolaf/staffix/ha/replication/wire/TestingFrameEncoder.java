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

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.replication.EncryptionMode;
import org.lolaf.staffix.ha.replication.journal.Journal;
import org.lolaf.staffix.ha.replication.journal.JournalStream;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Whole frames that production only writes in parts, a replication log holding a record's body and a snapshot
 * sending fragments, and the frames of the {@link Journal}: text entries, a state of ASCII digits.
 */
@UtilityClass
public class TestingFrameEncoder {

    public static int recordBytes(int payloadLength) {
        return WireFormat.HEADER_BYTES + FrameType.RECORD.getFixedPayloadBytes() + payloadLength;
    }

    /**
     * @param payload the bytes from its position to its limit, left untouched; null for a record carrying none
     */
    public static int record(ByteBuffer out, int index, long epoch, long logOffset, int streamKey, byte contentType,
                             byte flags, long position, @Nullable ByteBuffer payload) {
        int bodyLength = FrameEncoder.recordBody(out, index + WireFormat.RECORD_BODY_OFFSET, streamKey, contentType,
                flags, position, payload, payload == null ? 0 : payload.position(),
                payload == null ? 0 : payload.remaining());
        return FrameEncoder.recordHeader(out, index, epoch, logOffset, bodyLength);
    }

    /**
     * A journal entry.
     */
    public static int record(ByteBuffer out, int index, long epoch, long logOffset, int streamKey, long position,
                             String text) {
        return record(out, index, epoch, logOffset, streamKey, JournalStream.TEXT, (byte) 0, position, ascii(text));
    }

    /**
     * @param payload the bytes from its position to its limit, left untouched
     */
    public static int snapshotRecord(ByteBuffer out, int index, long epoch, int streamKey, byte contentType, byte flags,
                                     long position, ByteBuffer payload) {
        return FrameEncoder.snapshotRecord(out, index, epoch, streamKey, contentType, flags, position, payload,
                payload.position(), payload.remaining());
    }

    public static int snapshotRecord(ByteBuffer out, int index, long epoch, int streamKey, long position, String text) {
        return snapshotRecord(out, index, epoch, streamKey, JournalStream.TEXT, (byte) 0, position, ascii(text));
    }

    public static int snapshotBegin(ByteBuffer out, int index, long epoch, int streamKey, long snapshotOffset,
                                    String state) {
        ByteBuffer bytes = ascii(state);
        return FrameEncoder.snapshotBegin(out, index, epoch, streamKey, snapshotOffset, bytes, 0, bytes.remaining());
    }

    public static int snapshotEnd(ByteBuffer out, int index, long epoch, int streamKey, int recordCount, String state) {
        ByteBuffer bytes = ascii(state);
        return FrameEncoder.snapshotEnd(out, index, epoch, streamKey, recordCount, bytes, 0, bytes.remaining());
    }

    public static int hello(ByteBuffer out, int index, long epoch, String nodeId, long lastAppliedEpoch,
                            long lastAppliedOffset, int lane, EncryptionMode encryptionMode, byte[] nonce) {
        return FrameEncoder.hello(out, index, epoch, Journal.APPLICATION_ID, nodeId, lastAppliedEpoch,
                lastAppliedOffset, lane, encryptionMode, nonce);
    }

    public static int welcome(ByteBuffer out, int index, long epoch, String leaderId, long resumeOffset, int lanes,
                              EncryptionMode encryptionMode, byte[] nonce) {
        return FrameEncoder.welcome(out, index, epoch, Journal.APPLICATION_ID, leaderId, resumeOffset, lanes,
                encryptionMode, nonce);
    }

    public static ByteBuffer ascii(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }
}
