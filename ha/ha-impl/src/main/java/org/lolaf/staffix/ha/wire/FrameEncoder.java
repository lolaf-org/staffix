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

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.lolaf.staffix.ha.wire.WireFormat.HEADER_BYTES;
import static org.lolaf.staffix.ha.wire.WireFormat.STORE_EVENT_BODY_OFFSET;

/**
 * Writes frames at an absolute index of a caller-owned buffer, leaving its position and limit alone: several threads
 * can then encode into disjoint ranges of one shared buffer, as the replication log needs. Each method returns the
 * frame's length; the buffer must have room for it, which the {@code ...Bytes} methods size.
 *
 * <p>{@link #storeEvent}, {@link #ack} and {@link #heartbeat} allocate nothing; the frames carrying strings encode
 * them, and are sent only when a connection or a session starts.
 */
@UtilityClass
public class FrameEncoder {

    /**
     * Plain access only, which tolerates any alignment.
     */
    private static final VarHandle LONG = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.nativeOrder());

    public static int storeEventBytes(int messageLength) {
        return HEADER_BYTES + FrameType.STORE_EVENT.getFixedPayloadBytes() + messageLength;
    }

    public static int snapshotMessageBytes(int messageLength) {
        return HEADER_BYTES + FrameType.SNAPSHOT_MESSAGE.getFixedPayloadBytes() + messageLength;
    }

    public static int fixedBytes(FrameType type) {
        return HEADER_BYTES + type.getFixedPayloadBytes();
    }

    public static int hello(ByteBuffer out, int index, long epoch, String nodeId, long lastAppliedEpoch,
                            long lastAppliedOffset) {
        byte[] id = utf8(nodeId);
        int length = header(out, index, FrameType.HELLO, 0, epoch, id.length);
        int p = index + HEADER_BYTES;
        out.putInt(p, WireFormat.MAGIC);
        out.putShort(p + 4, WireFormat.VERSION);
        out.putShort(p + 6, (short) id.length);
        out.putLong(p + 8, lastAppliedEpoch);
        out.putLong(p + 16, lastAppliedOffset);
        putBytes(out, p + 24, id);
        return length;
    }

    public static int welcome(ByteBuffer out, int index, long epoch, String leaderId, long resumeOffset) {
        byte[] id = utf8(leaderId);
        int length = header(out, index, FrameType.WELCOME, 0, epoch, id.length);
        int p = index + HEADER_BYTES;
        out.putInt(p, WireFormat.MAGIC);
        out.putShort(p + 4, WireFormat.VERSION);
        out.putShort(p + 6, (short) id.length);
        out.putLong(p + 8, resumeOffset);
        putBytes(out, p + 16, id);
        return length;
    }

    public static int session(ByteBuffer out, int index, long epoch, int sessionKey, FixSessionType fixSessionType,
                              ReplicationMode replicationMode, String fixSessionId, @Nullable String storageIdentity) {
        byte[] id = utf8(fixSessionId);
        byte[] identity = storageIdentity == null ? new byte[0] : utf8(storageIdentity);
        int flags = storageIdentity == null ? 0 : WireFormat.FLAG_STORAGE_IDENTITY;
        int length = header(out, index, FrameType.SESSION, flags, epoch, id.length + identity.length);
        int p = index + HEADER_BYTES;
        out.putInt(p, sessionKey);
        out.put(p + 4, fixSessionType.getCode());
        out.put(p + 5, replicationMode.getCode());
        out.putShort(p + 6, (short) id.length);
        out.putShort(p + 8, (short) identity.length);
        putBytes(out, p + 10, id);
        putBytes(out, p + 10 + id.length, identity);
        return length;
    }

    public static int sessionStatus(ByteBuffer out, int index, long epoch, int sessionKey, SessionStatus status) {
        int length = header(out, index, FrameType.SESSION_STATUS, 0, epoch, 0);
        int p = index + HEADER_BYTES;
        out.putInt(p, sessionKey);
        out.put(p + 4, (byte) status.getCode());
        return length;
    }

    /**
     * @param message the bytes from its position to its limit, left untouched; null for an event carrying none
     */
    public static int storeEvent(ByteBuffer out, int index, long epoch, long logOffset, int sessionKey,
                                 StoreEventType eventType, long sequenceNumber, @Nullable ByteBuffer message) {
        int bodyLength = storeEventBody(out, index + STORE_EVENT_BODY_OFFSET, sessionKey, eventType, sequenceNumber, message);
        return storeEventHeader(out, index, epoch, logOffset, bodyLength);
    }

    /**
     * The length of a {@link #storeEventBody} carrying {@code messageLength} bytes of message.
     */
    public static int storeEventBodyBytes(int messageLength) {
        return FrameType.STORE_EVENT.getFixedPayloadBytes() - Long.BYTES + messageLength;
    }

    /**
     * Writes a {@link FrameType#STORE_EVENT} without its header and log offset, which the replication log holds
     * until a sender adds them with {@link #storeEventHeader}: the offset is the log's position for the event, known
     * only once it is written. The body goes {@link WireFormat#STORE_EVENT_BODY_OFFSET} bytes into the frame.
     *
     * @param message the bytes from its position to its limit, left untouched; null for an event carrying none
     * @return the body's length
     */
    public static int storeEventBody(ByteBuffer out, int index, int sessionKey, StoreEventType eventType,
                                     long sequenceNumber, @Nullable ByteBuffer message) {
        int messageLength = message == null ? 0 : message.remaining();
        out.putInt(index, sessionKey);
        out.put(index + 4, eventType.getCode());
        out.put(index + 5, (byte) 0);
        out.putShort(index + 6, (short) 0);
        out.putLong(index + 8, sequenceNumber);
        out.putInt(index + 16, messageLength);
        if (message != null) {
            copy(message, message.position(), out, index + 20, messageLength);
        }
        return storeEventBodyBytes(messageLength);
    }

    /**
     * Writes the header and log offset in front of a {@link #storeEventBody} that is already in place.
     *
     * @return the frame's length
     */
    public static int storeEventHeader(ByteBuffer out, int index, long epoch, long logOffset, int bodyLength) {
        int messageLength = bodyLength - storeEventBodyBytes(0);
        int length = header(out, index, FrameType.STORE_EVENT, 0, epoch, messageLength);
        out.putLong(index + HEADER_BYTES, logOffset);
        return length;
    }

    public static int snapshotBegin(ByteBuffer out, int index, long epoch, int sessionKey, long cutOffset,
                                    long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        int length = header(out, index, FrameType.SNAPSHOT_BEGIN, 0, epoch, 0);
        int p = index + HEADER_BYTES;
        out.putInt(p, sessionKey);
        out.putInt(p + 4, 0);
        out.putLong(p + 8, cutOffset);
        out.putLong(p + 16, nextIncomingSeqNum);
        out.putLong(p + 24, nextOutgoingSeqNum);
        return length;
    }

    /**
     * @param message the bytes from its position to its limit, left untouched
     */
    public static int snapshotMessage(ByteBuffer out, int index, long epoch, int sessionKey, long sequenceNumber,
                                      ByteBuffer message) {
        int messageLength = message.remaining();
        int length = header(out, index, FrameType.SNAPSHOT_MESSAGE, 0, epoch, messageLength);
        int p = index + HEADER_BYTES;
        out.putInt(p, sessionKey);
        out.putInt(p + 4, messageLength);
        out.putLong(p + 8, sequenceNumber);
        copy(message, message.position(), out, p + 16, messageLength);
        return length;
    }

    public static int snapshotEnd(ByteBuffer out, int index, long epoch, int sessionKey, int messageCount) {
        int length = header(out, index, FrameType.SNAPSHOT_END, 0, epoch, 0);
        int p = index + HEADER_BYTES;
        out.putInt(p, sessionKey);
        out.putInt(p + 4, messageCount);
        return length;
    }

    public static int ack(ByteBuffer out, int index, long epoch, long appliedOffset, boolean inSync) {
        int length = header(out, index, FrameType.ACK, inSync ? WireFormat.FLAG_IN_SYNC : 0, epoch, 0);
        out.putLong(index + HEADER_BYTES, appliedOffset);
        return length;
    }

    public static int heartbeat(ByteBuffer out, int index, long epoch, long logTailOffset) {
        int length = header(out, index, FrameType.HEARTBEAT, 0, epoch, 0);
        out.putLong(index + HEADER_BYTES, logTailOffset);
        return length;
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

    private static void putBytes(ByteBuffer out, int index, byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            out.put(index + i, bytes[i]);
        }
    }
}
