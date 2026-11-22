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

import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.lolaf.staffix.ha.replication.wire.WireFormat.HEADER_BYTES;

/**
 * Writes frames at an absolute index of a caller-owned buffer, leaving its position and limit alone: several threads
 * can then encode into disjoint ranges of one shared buffer, as the replication log needs. Each method returns the
 * frame's length, without the tag a {@link FrameCipher} then adds; the buffer must have room for it, which the
 * {@code ...Bytes} methods size.
 *
 * <p>{@link #recordBody}, {@link #ack} and {@link #heartbeat} allocate nothing; the frames carrying strings encode
 * them, and are sent only when a connection or a stream starts.
 */
@UtilityClass
public class FrameEncoder {

    private static final int MAX_CLOSE_TEXT = 1024;
    /**
     * Plain access only, which tolerates any alignment.
     */
    private static final VarHandle LONG = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.nativeOrder());

    public static int snapshotRecordBytes(int payloadLength) {
        return HEADER_BYTES + FrameType.SNAPSHOT_RECORD.getFixedPayloadBytes() + payloadLength;
    }

    public static int fixedBytes(FrameType type) {
        return HEADER_BYTES + type.getFixedPayloadBytes();
    }

    /**
     * The length of a frame whose variable part is the given strings, UTF-8 encoded.
     */
    public static int bytesWith(FrameType type, @Nullable String... strings) {
        int length = fixedBytes(type);
        for (String string : strings) {
            length += string == null ? 0 : utf8(string).length;
        }
        return length;
    }

    /**
     * @param nonce {@link WireFormat#NONCE_BYTES} bytes
     */
    public static int hello(ByteBuffer out, int index, long epoch, int applicationId, String nodeId,
                            long lastAppliedEpoch, long lastAppliedOffset, int lane, EncryptionMode encryptionMode,
                            byte[] nonce) {
        byte[] id = utf8(nodeId);
        int length = header(out, index, FrameType.HELLO, 0, epoch, id.length);
        int p = index + HEADER_BYTES;
        out.putInt(p, WireFormat.MAGIC);
        out.putShort(p + 4, WireFormat.VERSION);
        out.putShort(p + 6, (short) id.length);
        out.putLong(p + 8, lastAppliedEpoch);
        out.putLong(p + 16, lastAppliedOffset);
        out.putShort(p + 24, (short) lane);
        out.put(p + 26, encryptionMode.getCode());
        out.put(p + 27, (byte) 0);
        out.putInt(p + 28, applicationId);
        putNonce(out, p + 32, nonce);
        putBytes(out, p + 48, id);
        return length;
    }

    /**
     * @param nonce {@link WireFormat#NONCE_BYTES} bytes
     */
    public static int welcome(ByteBuffer out, int index, long epoch, int applicationId, String leaderId,
                              long resumeOffset, int lanes, EncryptionMode encryptionMode, byte[] nonce) {
        byte[] id = utf8(leaderId);
        int length = header(out, index, FrameType.WELCOME, 0, epoch, id.length);
        int p = index + HEADER_BYTES;
        out.putInt(p, WireFormat.MAGIC);
        out.putShort(p + 4, WireFormat.VERSION);
        out.putShort(p + 6, (short) id.length);
        out.putLong(p + 8, resumeOffset);
        out.putShort(p + 16, (short) lanes);
        out.put(p + 18, encryptionMode.getCode());
        out.put(p + 19, (byte) 0);
        out.putInt(p + 20, applicationId);
        putNonce(out, p + 24, nonce);
        putBytes(out, p + 40, id);
        return length;
    }

    /**
     * The HELLO {@link #hello} encodes, as bytes: what the KEY_CONFIRMATION covers.
     */
    public static byte[] helloBytes(long epoch, int applicationId, String nodeId, long lastAppliedEpoch,
                                    long lastAppliedOffset, int lane, EncryptionMode encryptionMode, byte[] nonce) {
        ByteBuffer frame = ByteBuffer.allocate(bytesWith(FrameType.HELLO, nodeId));
        int length = hello(frame, 0, epoch, applicationId, nodeId, lastAppliedEpoch, lastAppliedOffset, lane,
                encryptionMode, nonce);
        return FrameDecoder.bytes(frame, 0, length);
    }

    /**
     * The WELCOME {@link #welcome} encodes, as bytes: what the KEY_CONFIRMATION covers.
     */
    public static byte[] welcomeBytes(long epoch, int applicationId, String leaderId, long resumeOffset, int lanes,
                                      EncryptionMode encryptionMode, byte[] nonce) {
        ByteBuffer frame = ByteBuffer.allocate(bytesWith(FrameType.WELCOME, leaderId));
        int length = welcome(frame, 0, epoch, applicationId, leaderId, resumeOffset, lanes, encryptionMode, nonce);
        return FrameDecoder.bytes(frame, 0, length);
    }

    /**
     * @param confirmation {@link WireFormat#KEY_CONFIRMATION_BYTES} bytes
     */
    public static int keyConfirmation(ByteBuffer out, int index, long epoch, byte[] confirmation) {
        if (confirmation.length != WireFormat.KEY_CONFIRMATION_BYTES) {
            throw new IllegalArgumentException("A key confirmation is " + WireFormat.KEY_CONFIRMATION_BYTES + " bytes, got "
                    + confirmation.length);
        }
        int length = header(out, index, FrameType.KEY_CONFIRMATION, 0, epoch, 0);
        putBytes(out, index + HEADER_BYTES, confirmation);
        return length;
    }

    public static int stream(ByteBuffer out, int index, long epoch, int streamKey, StreamReplicationMode replicationMode,
                             String streamName, @Nullable String storageIdentity) {
        byte[] name = utf8(streamName);
        byte[] identity = storageIdentity == null ? new byte[0] : utf8(storageIdentity);
        int flags = storageIdentity == null ? 0 : WireFormat.FLAG_STORAGE_IDENTITY;
        int length = header(out, index, FrameType.STREAM, flags, epoch, name.length + identity.length);
        int p = index + HEADER_BYTES;
        out.putInt(p, streamKey);
        out.put(p + 4, replicationMode.getCode());
        out.put(p + 5, (byte) 0);
        out.putShort(p + 6, (short) name.length);
        out.putShort(p + 8, (short) identity.length);
        putBytes(out, p + 10, name);
        putBytes(out, p + 10 + name.length, identity);
        return length;
    }

    /**
     * Ends the first announcement of a connection's streams, {@code announcedCount} STREAM frames since its WELCOME.
     */
    public static int streamsAnnounced(ByteBuffer out, int index, long epoch, int announcedCount) {
        int length = header(out, index, FrameType.STREAMS_ANNOUNCED, 0, epoch, 0);
        out.putInt(index + HEADER_BYTES, announcedCount);
        out.putInt(index + HEADER_BYTES + 4, 0);
        return length;
    }

    public static int streamAnswer(ByteBuffer out, int index, long epoch, int streamKey, StreamAnswer answer) {
        int length = header(out, index, FrameType.STREAM_ANSWER, 0, epoch, 0);
        int p = index + HEADER_BYTES;
        out.putInt(p, streamKey);
        out.put(p + 4, (byte) answer.getCode());
        return length;
    }

    /**
     * The length of a {@link #recordBody} carrying {@code payloadLength} bytes of payload.
     */
    public static int recordBodyBytes(int payloadLength) {
        return FrameType.RECORD.getFixedPayloadBytes() - Long.BYTES + payloadLength;
    }

    /**
     * Writes a record without its frame header and log offset, which the replication log holds until a sender adds
     * them with {@link #recordHeader}: the offset is the log's position for the record, known only once it is written.
     * The body goes {@link WireFormat#RECORD_BODY_OFFSET} bytes into a {@link FrameType#RECORD}, and is the whole
     * fixed payload of a {@link FrameType#SNAPSHOT_RECORD}. It carries {@code length} bytes of {@code payload} from
     * {@code payloadIndex}, a fragment of it when {@code flags} says so; see {@link WireFormat#MORE_FRAGMENTS}.
     *
     * @param payload null for a record carrying none
     * @return the body's length
     */
    public static int recordBody(ByteBuffer out, int index, int streamKey, byte contentType, byte flags, long position,
                                 @Nullable ByteBuffer payload, int payloadIndex, int length) {
        out.putInt(index, streamKey);
        out.put(index + 4, contentType);
        out.put(index + WireFormat.RECORD_BODY_FLAGS, flags);
        out.putShort(index + 6, (short) 0);
        out.putLong(index + 8, position);
        out.putInt(index + 16, length);
        out.putInt(index + 20, 0);
        if (payload != null) {
            copy(payload, payloadIndex, out, index + 24, length);
        }
        return recordBodyBytes(length);
    }

    /**
     * Writes the header and log offset in front of a {@link #recordBody} that is already in place.
     *
     * @return the frame's length
     */
    public static int recordHeader(ByteBuffer out, int index, long epoch, long logOffset, int bodyLength) {
        int payloadLength = bodyLength - recordBodyBytes(0);
        int length = header(out, index, FrameType.RECORD, 0, epoch, payloadLength);
        out.putLong(index + HEADER_BYTES, logOffset);
        return length;
    }

    /**
     * @param state the stream's state, {@code stateLength} bytes from {@code stateIndex}, left untouched
     */
    public static int snapshotBegin(ByteBuffer out, int index, long epoch, int streamKey, long snapshotOffset,
                                    ByteBuffer state, int stateIndex, int stateLength) {
        int length = header(out, index, FrameType.SNAPSHOT_BEGIN, 0, epoch, checkState(stateLength));
        int p = index + HEADER_BYTES;
        out.putInt(p, streamKey);
        out.putShort(p + 4, (short) 0);
        out.putShort(p + 6, (short) stateLength);
        out.putLong(p + 8, snapshotOffset);
        copy(state, stateIndex, out, p + 16, stateLength);
        return length;
    }

    /**
     * A record body, as {@link #recordBody}, behind a header of its own.
     */
    public static int snapshotRecord(ByteBuffer out, int index, long epoch, int streamKey, byte contentType, byte flags,
                                     long position, ByteBuffer payload, int payloadIndex, int length) {
        int frameLength = header(out, index, FrameType.SNAPSHOT_RECORD, 0, epoch, length);
        recordBody(out, index + HEADER_BYTES, streamKey, contentType, flags, position, payload, payloadIndex, length);
        return frameLength;
    }

    /**
     * @param state as for {@link #snapshotBegin}
     */
    public static int snapshotEnd(ByteBuffer out, int index, long epoch, int streamKey, int recordCount,
                                  ByteBuffer state, int stateIndex, int stateLength) {
        int length = header(out, index, FrameType.SNAPSHOT_END, 0, epoch, checkState(stateLength));
        int p = index + HEADER_BYTES;
        out.putInt(p, streamKey);
        out.putInt(p + 4, recordCount);
        out.putShort(p + 8, (short) 0);
        out.putShort(p + 10, (short) stateLength);
        copy(state, stateIndex, out, p + 12, stateLength);
        return length;
    }

    public static int ack(ByteBuffer out, int index, long epoch, long appliedOffset, boolean inSync, long pendingWrites) {
        int length = header(out, index, FrameType.ACK, inSync ? WireFormat.FLAG_IN_SYNC : 0, epoch, 0);
        out.putLong(index + HEADER_BYTES, appliedOffset);
        out.putLong(index + HEADER_BYTES + Long.BYTES, pendingWrites);
        return length;
    }

    public static int received(ByteBuffer out, int index, long epoch, long receivedOffset) {
        int length = header(out, index, FrameType.RECEIVED, 0, epoch, 0);
        out.putLong(index + HEADER_BYTES, receivedOffset);
        return length;
    }

    public static int heartbeat(ByteBuffer out, int index, long epoch, long logTailOffset) {
        int length = header(out, index, FrameType.HEARTBEAT, 0, epoch, 0);
        out.putLong(index + HEADER_BYTES, logTailOffset);
        return length;
    }

    /**
     * The text cut short enough for a CLOSE: it may hold an exception's message, of any length.
     */
    public static String closeText(String text) {
        return text.length() > MAX_CLOSE_TEXT ? text.substring(0, MAX_CLOSE_TEXT) : text;
    }

    public static int close(ByteBuffer out, int index, long epoch, CloseReason reason, String text) {
        byte[] bytes = utf8(text);
        int length = header(out, index, FrameType.CLOSE, 0, epoch, bytes.length);
        int p = index + HEADER_BYTES;
        out.putShort(p, (short) reason.getCode());
        out.putShort(p + 2, (short) bytes.length);
        putBytes(out, p + 4, bytes);
        return length;
    }

    /**
     * Copies without touching either buffer's position, which an absolute bulk copy cannot do before Java 16. Longs
     * are read and written in native order whatever the buffers' order, so the bytes come out unswapped.
     */
    static void copy(ByteBuffer src, int srcIndex, ByteBuffer dst, int dstIndex, int length) {
        int i = 0;
        for (; i + Long.BYTES <= length; i += Long.BYTES) {
            LONG.set(dst, dstIndex + i, (long) LONG.get(src, srcIndex + i));
        }
        for (; i < length; i++) {
            dst.put(dstIndex + i, src.get(srcIndex + i));
        }
    }

    private static int header(ByteBuffer out, int index, FrameType type, int flags, long epoch, int variableBytes) {
        int length = HEADER_BYTES + type.getFixedPayloadBytes() + variableBytes;
        out.putInt(index + WireFormat.OFFSET_LENGTH, length);
        out.put(index + WireFormat.OFFSET_TYPE, (byte) type.getCode());
        out.put(index + WireFormat.OFFSET_FLAGS, (byte) flags);
        out.putShort(index + WireFormat.OFFSET_RESERVED, (short) 0);
        out.putLong(index + WireFormat.OFFSET_EPOCH, epoch);
        return length;
    }

    private static byte[] utf8(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > WireFormat.MAX_STRING_BYTES) {
            throw new IllegalArgumentException("At most " + WireFormat.MAX_STRING_BYTES + " UTF-8 bytes, got "
                    + bytes.length + ": " + value.substring(0, 64) + "...");
        }
        return bytes;
    }

    private static void putNonce(ByteBuffer out, int index, byte[] nonce) {
        if (nonce.length != WireFormat.NONCE_BYTES) {
            throw new IllegalArgumentException("A nonce is " + WireFormat.NONCE_BYTES + " bytes, got " + nonce.length);
        }
        putBytes(out, index, nonce);
    }

    private static int checkState(int stateLength) {
        if (stateLength > WireFormat.MAX_STATE_BYTES) {
            throw new IllegalArgumentException("A stream state is at most " + WireFormat.MAX_STATE_BYTES + " bytes, got "
                    + stateLength);
        }
        return stateLength;
    }

    private static void putBytes(ByteBuffer out, int index, byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            out.put(index + i, bytes[i]);
        }
    }
}
