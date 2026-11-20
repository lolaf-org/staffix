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

import org.lolaf.staffix.ha.EncryptionMode;
import org.lolaf.staffix.ha.SessionSettingsDigests;
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

/**
 * Writes frames at an absolute index of a caller-owned buffer, leaving its position and limit alone: several threads
 * can then encode into disjoint ranges of one shared buffer, as the replication log needs. Each method returns the
 * frame's length; the buffer must have room for it, which the {@code ...Bytes} methods size.
 *
 * <p>{@link #storeEventBody}, {@link #ack} and {@link #heartbeat} allocate nothing; the frames carrying strings encode
 * them, and are sent only when a connection or a session starts.
 */
@UtilityClass
public class FrameEncoder {

    private static final int MAX_CLOSE_TEXT = 1024;
    /**
     * Plain access only, which tolerates any alignment.
     */
    private static final VarHandle LONG = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.nativeOrder());

    public static int snapshotMessageBytes(int messageLength) {
        return HEADER_BYTES + FrameType.SNAPSHOT_MESSAGE.getFixedPayloadBytes() + messageLength;
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
    public static int hello(ByteBuffer out, int index, long epoch, String nodeId, long lastAppliedEpoch,
                            long lastAppliedOffset, int lane, EncryptionMode encryptionMode, byte[] nonce) {
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
        zero(out, p + 27, 5);
        putNonce(out, p + 32, nonce);
        putBytes(out, p + 48, id);
        return length;
    }

    /**
     * @param nonce {@link WireFormat#NONCE_BYTES} bytes
     */
    public static int welcome(ByteBuffer out, int index, long epoch, String leaderId, long resumeOffset, int lanes,
                              EncryptionMode encryptionMode, byte[] nonce) {
        byte[] id = utf8(leaderId);
        int length = header(out, index, FrameType.WELCOME, 0, epoch, id.length);
        int p = index + HEADER_BYTES;
        out.putInt(p, WireFormat.MAGIC);
        out.putShort(p + 4, WireFormat.VERSION);
        out.putShort(p + 6, (short) id.length);
        out.putLong(p + 8, resumeOffset);
        out.putShort(p + 16, (short) lanes);
        out.put(p + 18, encryptionMode.getCode());
        zero(out, p + 19, 5);
        putNonce(out, p + 24, nonce);
        putBytes(out, p + 40, id);
        return length;
    }

    /**
     * @param confirmation {@link WireFormat#KEY_CONFIRMATION_BYTES} bytes
     */
    public static int keyConfirmation(ByteBuffer out, int index, long epoch, byte[] confirmation) {
        if (confirmation.length != WireFormat.KEY_CONFIRMATION_BYTES) {
            throw new IllegalArgumentException("A key confirmation is " + WireFormat.KEY_CONFIRMATION_BYTES + " bytes, got " + confirmation.length);
        }
        int length = header(out, index, FrameType.KEY_CONFIRMATION, 0, epoch, 0);
        putBytes(out, index + HEADER_BYTES, confirmation);
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

    public static int sessionSettingsDigest(ByteBuffer out, int index, long epoch, int generation, SessionSettingsDigests.Digest digest) {
        byte[] store = utf8(digest.getStore());
        byte[] id = utf8(digest.getFixSessionId());
        byte[] value = utf8(digest.getValue());
        byte[] identity = utf8(digest.getStoreIdentity());
        byte[] document = utf8(digest.getDocument());
        int length = header(out, index, FrameType.SESSION_SETTINGS_DIGEST, digest.isRemoved() ? WireFormat.FLAG_SESSION_REMOVED : 0, epoch,
                store.length + id.length + value.length + identity.length + document.length);
        int p = index + HEADER_BYTES;
        out.putInt(p, generation);
        out.put(p + 4, digest.getFixSessionType().getCode());
        out.put(p + 5, (byte) 0);
        out.putShort(p + 6, (short) store.length);
        out.putShort(p + 8, (short) id.length);
        out.putShort(p + 10, (short) value.length);
        out.putShort(p + 12, (short) identity.length);
        out.putShort(p + 14, (short) document.length);
        int variable = p + 16;
        putBytes(out, variable, store);
        putBytes(out, variable += store.length, id);
        putBytes(out, variable += id.length, value);
        putBytes(out, variable += value.length, identity);
        putBytes(out, variable + identity.length, document);
        return length;
    }

    public static int sessionSettingsDigestsComplete(ByteBuffer out, int index, long epoch, int generation, int digestCount) {
        int length = header(out, index, FrameType.SESSION_SETTINGS_DIGESTS_COMPLETE, 0, epoch, 0);
        out.putInt(index + HEADER_BYTES, generation);
        out.putInt(index + HEADER_BYTES + 4, digestCount);
        return length;
    }

    /**
     * Ends the first announcement of a stream's sessions, {@code announcedCount} SESSION frames since its WELCOME.
     */
    public static int sessionsAnnounced(ByteBuffer out, int index, long epoch, int announcedCount) {
        int length = header(out, index, FrameType.SESSIONS_ANNOUNCED, 0, epoch, 0);
        out.putInt(index + HEADER_BYTES, announcedCount);
        out.putInt(index + HEADER_BYTES + 4, 0);
        return length;
    }

    public static int sessionAnswer(ByteBuffer out, int index, long epoch, int sessionKey, SessionAnswer answer) {
        int length = header(out, index, FrameType.SESSION_ANSWER, 0, epoch, 0);
        int p = index + HEADER_BYTES;
        out.putInt(p, sessionKey);
        out.put(p + 4, (byte) answer.getCode());
        return length;
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
     * It carries {@code length} bytes of {@code message} from {@code messageIndex}, a fragment of it when
     * {@code fragmentFlags} says so; see {@link WireFormat#MORE_FRAGMENTS}.
     *
     * @param messageTypeHash every fragment carries it, so a fragment is told apart without its first
     * @param message         null for an event carrying none
     * @return the body's length
     */
    public static int storeEventBody(ByteBuffer out, int index, int sessionKey, StoreEventType eventType, long sequenceNumber,
                                     int messageTypeHash, @Nullable ByteBuffer message, int messageIndex, int length,
                                     byte fragmentFlags) {
        out.putInt(index, sessionKey);
        out.put(index + 4, eventType.getCode());
        out.put(index + 5, fragmentFlags);
        out.putShort(index + 6, (short) 0);
        out.putLong(index + 8, sequenceNumber);
        out.putInt(index + 16, messageTypeHash);
        out.putInt(index + 20, length);
        if (message != null) {
            copy(message, messageIndex, out, index + 24, length);
        }
        return storeEventBodyBytes(length);
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

    public static int snapshotBegin(ByteBuffer out, int index, long epoch, int sessionKey, long snapshotOffset,
                                    long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        int length = header(out, index, FrameType.SNAPSHOT_BEGIN, 0, epoch, 0);
        int p = index + HEADER_BYTES;
        out.putInt(p, sessionKey);
        out.putInt(p + 4, 0);
        out.putLong(p + 8, snapshotOffset);
        out.putLong(p + 16, nextIncomingSeqNum);
        out.putLong(p + 24, nextOutgoingSeqNum);
        return length;
    }

    /**
     * {@code length} bytes of {@code message} from {@code messageIndex}, a fragment of it when {@code fragmentFlags}
     * says so, in the header's flags; see {@link WireFormat#MORE_FRAGMENTS}.
     */
    public static int snapshotMessage(ByteBuffer out, int index, long epoch, int sessionKey, long sequenceNumber,
                                      int messageTypeHash, ByteBuffer message, int messageIndex, int length,
                                      byte fragmentFlags) {
        int frameLength = header(out, index, FrameType.SNAPSHOT_MESSAGE, fragmentFlags, epoch, length);
        int p = index + HEADER_BYTES;
        out.putInt(p, sessionKey);
        out.putInt(p + 4, length);
        out.putLong(p + 8, sequenceNumber);
        out.putInt(p + 16, messageTypeHash);
        out.putInt(p + 20, 0);
        copy(message, messageIndex, out, p + 24, length);
        return frameLength;
    }

    public static int snapshotEnd(ByteBuffer out, int index, long epoch, int sessionKey, int messageCount,
                                  long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        int length = header(out, index, FrameType.SNAPSHOT_END, 0, epoch, 0);
        int p = index + HEADER_BYTES;
        out.putInt(p, sessionKey);
        out.putInt(p + 4, messageCount);
        out.putLong(p + 8, nextIncomingSeqNum);
        out.putLong(p + 16, nextOutgoingSeqNum);
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

    private static void zero(ByteBuffer out, int index, int length) {
        for (int i = 0; i < length; i++) {
            out.put(index + i, (byte) 0);
        }
    }

    private static void putBytes(ByteBuffer out, int index, byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            out.put(index + i, bytes[i]);
        }
    }
}
