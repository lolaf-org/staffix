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

import org.lolaf.staffix.ha.replication.spi.WireFormatException;
import org.lolaf.staffix.ha.replication.EncryptionMode;
import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.ha.replication.spi.RecordFlag;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lolaf.staffix.ha.replication.wire.WireFormat.HEADER_BYTES;

/**
 * Reads one frame at an absolute index, checks it is whole and well formed, and dispatches it to a
 * {@link FrameHandler}. The buffer's position and limit are left alone.
 *
 * <p>Allocates nothing for the frequent frames; the ones carrying strings decode them.
 */
@RequiredArgsConstructor
public class FrameDecoder {

    /**
     * The longest frame accepted. A length beyond it is garbage rather than a frame to wait for, so the stream is
     * refused instead of buffering without end.
     */
    private final int maxFrameBytes;

    /**
     * Decodes a clear connection's frame.
     *
     * @param available the bytes readable from {@code index}
     * @return the length of the frame dispatched, or 0 when {@code available} does not hold a whole frame yet
     * @throws WireFormatException for bytes that are not a valid frame
     */
    public int decode(ByteBuffer in, int index, int available, FrameHandler handler) {
        return decode(in, index, available, handler, FrameCipher.CLEAR);
    }

    /**
     * As {@link #decode(ByteBuffer, int, int, FrameHandler)}, opening the frame first: it is dispatched only once its
     * tag verified.
     */
    public int decode(ByteBuffer in, int index, int available, FrameHandler handler, FrameCipher opener) {
        if (available < Integer.BYTES) {
            return 0;
        }
        int length = in.getInt(index + WireFormat.OFFSET_LENGTH);
        if (length < HEADER_BYTES || length > maxFrameBytes) {
            throw new WireFormatException("Frame length " + length + " outside " + HEADER_BYTES + ".." + maxFrameBytes);
        }
        if (available < length) {
            return 0;
        }
        int typeCode = in.get(index + WireFormat.OFFSET_TYPE);
        FrameType type = FrameType.forCode(typeCode);
        if (type == null) {
            throw new WireFormatException("Unknown frame type " + (typeCode & 0xFF));
        }
        int contentLength = opener.open(in, index, length);
        int variableBytes = contentLength - HEADER_BYTES - type.getFixedPayloadBytes();
        if (variableBytes < 0) {
            throw new WireFormatException(type + " of " + length + " bytes is shorter than its fixed part");
        }
        int flags = in.get(index + WireFormat.OFFSET_FLAGS);
        long epoch = in.getLong(index + WireFormat.OFFSET_EPOCH);
        dispatch(in, index + HEADER_BYTES, type, flags, epoch, variableBytes, handler);
        return length;
    }

    /**
     * Whether the frame at {@code frameIndex} is a RECORD or SNAPSHOT_RECORD flagged {@link RecordFlag#PLAINTEXT}.
     */
    public static boolean isPlaintext(ByteBuffer in, int frameIndex, FrameType type) {
        switch (type) {
            case RECORD:
                return (in.get(frameIndex + WireFormat.OFFSET_RECORD_FLAGS) & RecordFlag.PLAINTEXT.getBits()) != 0;
            case SNAPSHOT_RECORD:
                return (in.get(frameIndex + HEADER_BYTES + WireFormat.RECORD_BODY_FLAGS)
                        & RecordFlag.PLAINTEXT.getBits()) != 0;
            default:
                return false;
        }
    }

    private static void dispatch(ByteBuffer in, int p, FrameType type, int flags, long epoch, int variableBytes,
                                 FrameHandler handler) {
        switch (type) {
            case HELLO:
                decodeHello(in, p, epoch, variableBytes, handler);
                break;
            case WELCOME:
                decodeWelcome(in, p, epoch, variableBytes, handler);
                break;
            case KEY_CONFIRMATION:
                checkVariableBytes(FrameType.KEY_CONFIRMATION, 0, variableBytes);
                handler.onKeyConfirmation(epoch, bytes(in, p, WireFormat.KEY_CONFIRMATION_BYTES));
                break;
            case STREAM:
                decodeStream(in, p, flags, epoch, variableBytes, handler);
                break;
            case STREAM_ANSWER:
                decodeStreamAnswer(in, p, epoch, handler);
                break;
            case RECORD:
                decodeRecord(in, p, epoch, variableBytes, handler);
                break;
            case SNAPSHOT_BEGIN:
                decodeSnapshotBegin(in, p, epoch, variableBytes, handler);
                break;
            case SNAPSHOT_RECORD:
                decodeSnapshotRecord(in, p, epoch, variableBytes, handler);
                break;
            case SNAPSHOT_END:
                decodeSnapshotEnd(in, p, epoch, variableBytes, handler);
                break;
            case ACK:
                handler.onAck(epoch, in.getLong(p), (flags & WireFormat.FLAG_IN_SYNC) != 0, in.getLong(p + 8));
                break;
            case RECEIVED:
                handler.onReceived(epoch, in.getLong(p));
                break;
            case HEARTBEAT:
                handler.onHeartbeat(epoch, in.getLong(p));
                break;
            case STREAMS_ANNOUNCED:
                handler.onStreamsAnnounced(epoch, in.getInt(p));
                break;
            case CLOSE:
                decodeClose(in, p, epoch, variableBytes, handler);
                break;
            default:
                throw new WireFormatException("No decoding for " + type);
        }
    }

    private static void decodeHello(ByteBuffer in, int p, long epoch, int variableBytes, FrameHandler handler) {
        checkMagic(in, p);
        String nodeId = string(in, p + 48, unsignedShort(in, p + 6), variableBytes, FrameType.HELLO);
        handler.onHello(epoch, in.getShort(p + 4), in.getInt(p + 28), nodeId, in.getLong(p + 8), in.getLong(p + 16),
                unsignedShort(in, p + 24), encryptionMode(in.get(p + 26)), bytes(in, p + 32, WireFormat.NONCE_BYTES));
    }

    private static void decodeWelcome(ByteBuffer in, int p, long epoch, int variableBytes, FrameHandler handler) {
        checkMagic(in, p);
        String leaderId = string(in, p + 40, unsignedShort(in, p + 6), variableBytes, FrameType.WELCOME);
        handler.onWelcome(epoch, in.getShort(p + 4), in.getInt(p + 20), leaderId, in.getLong(p + 8),
                unsignedShort(in, p + 16), encryptionMode(in.get(p + 18)), bytes(in, p + 24, WireFormat.NONCE_BYTES));
    }

    private static void decodeStream(ByteBuffer in, int p, int flags, long epoch, int variableBytes, FrameHandler handler) {
        int nameLength = unsignedShort(in, p + 6);
        int identityLength = unsignedShort(in, p + 8);
        checkVariableBytes(FrameType.STREAM, nameLength + identityLength, variableBytes);
        String identity = (flags & WireFormat.FLAG_STORAGE_IDENTITY) != 0
                ? utf8Str(in, p + 10 + nameLength, identityLength)
                : null;
        handler.onStream(epoch, in.getInt(p), replicationMode(in.get(p + 4)), utf8Str(in, p + 10, nameLength), identity);
    }

    private static void decodeStreamAnswer(ByteBuffer in, int p, long epoch, FrameHandler handler) {
        StreamAnswer answer = StreamAnswer.forCode(in.get(p + 4));
        if (answer == null) {
            throw new WireFormatException("Unknown stream answer " + in.get(p + 4));
        }
        handler.onStreamAnswer(epoch, in.getInt(p), answer);
    }

    /**
     * Reads the log offset of the {@link FrameType#RECORD} at {@code frameIndex} without decoding the frame.
     */
    public static long recordLogOffset(ByteBuffer in, int frameIndex) {
        return in.getLong(frameIndex + HEADER_BYTES);
    }

    /**
     * Reads the stream key of the record body at {@code bodyIndex}, which is {@link WireFormat#RECORD_BODY_OFFSET}
     * bytes into its frame.
     */
    public static int recordStreamKey(ByteBuffer in, int bodyIndex) {
        return in.getInt(bodyIndex);
    }

    /**
     * Reads the flags of the record body at {@code bodyIndex}.
     */
    public static byte recordFlags(ByteBuffer in, int bodyIndex) {
        return in.get(bodyIndex + WireFormat.RECORD_BODY_FLAGS);
    }

    private static void decodeRecord(ByteBuffer in, int p, long epoch, int variableBytes, FrameHandler handler) {
        int body = p + Long.BYTES;
        int payloadLength = in.getInt(body + 16);
        checkVariableBytes(FrameType.RECORD, payloadLength, variableBytes);
        handler.onRecord(epoch, in.getLong(p), recordStreamKey(in, body), in.get(body + 4), flags(in, body),
                in.getLong(body + 8), in, body + 24, payloadLength);
    }

    private static void decodeSnapshotBegin(ByteBuffer in, int p, long epoch, int variableBytes, FrameHandler handler) {
        int stateLength = unsignedShort(in, p + 6);
        checkVariableBytes(FrameType.SNAPSHOT_BEGIN, stateLength, variableBytes);
        handler.onSnapshotBegin(epoch, in.getInt(p), in.getLong(p + 8), in, p + 16, stateLength);
    }

    private static void decodeSnapshotRecord(ByteBuffer in, int p, long epoch, int variableBytes, FrameHandler handler) {
        int payloadLength = in.getInt(p + 16);
        checkVariableBytes(FrameType.SNAPSHOT_RECORD, payloadLength, variableBytes);
        handler.onSnapshotRecord(epoch, recordStreamKey(in, p), in.get(p + 4), flags(in, p), in.getLong(p + 8), in,
                p + 24, payloadLength);
    }

    private static void decodeSnapshotEnd(ByteBuffer in, int p, long epoch, int variableBytes, FrameHandler handler) {
        int stateLength = unsignedShort(in, p + 10);
        checkVariableBytes(FrameType.SNAPSHOT_END, stateLength, variableBytes);
        handler.onSnapshotEnd(epoch, in.getInt(p), in.getInt(p + 4), in, p + 12, stateLength);
    }

    /**
     * Without {@link RecordFlag#PLAINTEXT}, which the opener already obeyed.
     */
    private static byte flags(ByteBuffer in, int bodyIndex) {
        return (byte) (recordFlags(in, bodyIndex) & ~RecordFlag.PLAINTEXT.getBits());
    }

    private static void decodeClose(ByteBuffer in, int p, long epoch, int variableBytes, FrameHandler handler) {
        String text = string(in, p + 4, unsignedShort(in, p + 2), variableBytes, FrameType.CLOSE);
        handler.onClose(epoch, CloseReason.forCode(unsignedShort(in, p)), text);
    }

    private static void checkMagic(ByteBuffer in, int p) {
        if (in.getInt(p) != WireFormat.MAGIC) {
            throw new WireFormatException("Not a replication peer: magic " + Integer.toHexString(in.getInt(p)));
        }
    }

    private static void checkVariableBytes(FrameType type, int declared, int actual) {
        if (declared != actual) {
            throw new WireFormatException(type + " declares " + declared + " bytes of content but carries " + actual);
        }
    }

    private static EncryptionMode encryptionMode(byte code) {
        try {
            return EncryptionMode.from(code);
        } catch (IllegalArgumentException e) {
            throw new WireFormatException(e.getMessage());
        }
    }

    /**
     * A copy of {@code length} bytes from {@code index}, leaving the buffer's position alone.
     */
    public static byte[] bytes(ByteBuffer in, int index, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = in.get(index + i);
        }
        return bytes;
    }

    private static StreamReplicationMode replicationMode(byte code) {
        try {
            return StreamReplicationMode.from(code);
        } catch (IllegalArgumentException e) {
            throw new WireFormatException(e.getMessage());
        }
    }

    private static String string(ByteBuffer in, int index, int length, int variableBytes, FrameType type) {
        checkVariableBytes(type, length, variableBytes);
        return utf8Str(in, index, length);
    }

    private static String utf8Str(ByteBuffer in, int index, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = in.get(index + i);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int unsignedShort(ByteBuffer in, int index) {
        return in.getShort(index) & 0xFFFF;
    }
}
