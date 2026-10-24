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
package org.lolaf.staffix.ha.wire;

import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lolaf.staffix.ha.wire.WireFormat.HEADER_BYTES;

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
     * @param available the bytes readable from {@code index}
     * @return the length of the frame dispatched, or 0 when {@code available} does not hold a whole frame yet
     * @throws WireFormatException for bytes that are not a valid frame
     */
    public int decode(ByteBuffer in, int index, int available, FrameHandler handler) {
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
        int variableBytes = length - HEADER_BYTES - type.getFixedPayloadBytes();
        if (variableBytes < 0) {
            throw new WireFormatException(type + " of " + length + " bytes is shorter than its fixed part");
        }
        int flags = in.get(index + WireFormat.OFFSET_FLAGS);
        long epoch = in.getLong(index + WireFormat.OFFSET_EPOCH);
        dispatch(in, index + HEADER_BYTES, type, flags, epoch, variableBytes, handler);
        return length;
    }

    private static void dispatch(ByteBuffer in, int p, FrameType type, int flags, long epoch, int variableBytes,
                                 FrameHandler handler) {
        switch (type) {
            case HELLO:
                checkMagic(in, p);
                handler.onHello(epoch, in.getShort(p + 4), string(in, p + 24, unsignedShort(in, p + 6), variableBytes, type),
                        in.getLong(p + 8), in.getLong(p + 16));
                break;
            case WELCOME:
                checkMagic(in, p);
                handler.onWelcome(epoch, in.getShort(p + 4), string(in, p + 16, unsignedShort(in, p + 6), variableBytes, type),
                        in.getLong(p + 8));
                break;
            case SESSION:
                onSession(in, p, flags, epoch, variableBytes, handler);
                break;
            case SESSION_STATUS:
                SessionStatus status = SessionStatus.forCode(in.get(p + 4));
                if (status == null) {
                    throw new WireFormatException("Unknown session status " + in.get(p + 4));
                }
                handler.onSessionStatus(epoch, in.getInt(p), status);
                break;
            case STORE_EVENT:
                int eventLength = in.getInt(p + 24);
                checkVariableBytes(type, eventLength, variableBytes);
                handler.onStoreEvent(epoch, in.getLong(p), in.getInt(p + 8), eventType(in.get(p + 12)), in.getLong(p + 16),
                        in, p + 28, eventLength);
                break;
            case SNAPSHOT_BEGIN:
                handler.onSnapshotBegin(epoch, in.getInt(p), in.getLong(p + 8), in.getLong(p + 16), in.getLong(p + 24));
                break;
            case SNAPSHOT_MESSAGE:
                int messageLength = in.getInt(p + 4);
                checkVariableBytes(type, messageLength, variableBytes);
                handler.onSnapshotMessage(epoch, in.getInt(p), in.getLong(p + 8), in, p + 16, messageLength);
                break;
            case SNAPSHOT_END:
                handler.onSnapshotEnd(epoch, in.getInt(p), in.getInt(p + 4));
                break;
            case ACK:
                handler.onAck(epoch, in.getLong(p), (flags & WireFormat.FLAG_IN_SYNC) != 0);
                break;
            case HEARTBEAT:
                handler.onHeartbeat(epoch, in.getLong(p));
                break;
            case CLOSE:
                handler.onClose(epoch, CloseReason.forCode(unsignedShort(in, p)),
                        string(in, p + 4, unsignedShort(in, p + 2), variableBytes, type));
                break;
            default:
                throw new WireFormatException("No decoding for " + type);
        }
    }

    private static void onSession(ByteBuffer in, int p, int flags, long epoch, int variableBytes, FrameHandler handler) {
        int idLength = unsignedShort(in, p + 6);
        int identityLength = unsignedShort(in, p + 8);
        checkVariableBytes(FrameType.SESSION, idLength + identityLength, variableBytes);
        String identity = (flags & WireFormat.FLAG_STORAGE_IDENTITY) != 0
                ? new String(bytes(in, p + 10 + idLength, identityLength), StandardCharsets.UTF_8)
                : null;
        handler.onSession(epoch, in.getInt(p), fixSessionType(in.get(p + 4)), replicationMode(in.get(p + 5)),
                new String(bytes(in, p + 10, idLength), StandardCharsets.UTF_8), identity);
    }

    private static void checkMagic(ByteBuffer in, int p) {
        if (in.getInt(p) != WireFormat.MAGIC) {
            throw new WireFormatException("Not a staffix replication peer: magic " + Integer.toHexString(in.getInt(p)));
        }
    }

    private static void checkVariableBytes(FrameType type, int declared, int actual) {
        if (declared != actual) {
            throw new WireFormatException(type + " declares " + declared + " bytes of content but carries " + actual);
        }
    }

    private static StoreEventType eventType(byte code) {
        try {
            return StoreEventType.from(code);
        } catch (IllegalStateException e) {
            throw new WireFormatException("Unknown store event type " + code);
        }
    }

    private static FixSessionType fixSessionType(byte code) {
        try {
            return FixSessionType.from(code);
        } catch (IllegalStateException e) {
            throw new WireFormatException(e.getMessage());
        }
    }

    private static ReplicationMode replicationMode(byte code) {
        try {
            return ReplicationMode.from(code);
        } catch (IllegalStateException e) {
            throw new WireFormatException(e.getMessage());
        }
    }

    private static String string(ByteBuffer in, int index, int length, int variableBytes, FrameType type) {
        checkVariableBytes(type, length, variableBytes);
        return new String(bytes(in, index, length), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(ByteBuffer in, int index, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = in.get(index + i);
        }
        return bytes;
    }

    private static int unsignedShort(ByteBuffer in, int index) {
        return in.getShort(index) & 0xFFFF;
    }
}
