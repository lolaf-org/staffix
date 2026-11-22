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

import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.EncryptionMode;
import org.lolaf.staffix.ha.ReplicatedSessions;
import org.lolaf.staffix.ha.TypedSessionId;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Records each frame as one line, so a test compares every field at once.
 */
public class RecordingHandler implements FrameHandler {

    public final List<String> frames;

    public RecordingHandler() {
        this.frames = new ArrayList<>();
    }

    private static String text(ByteBuffer buffer, int index, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = buffer.get(index + i);
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    /**
     * The nonce of the last HELLO or WELCOME, and the last KEY_CONFIRMATION, which the frames only summarise.
     */
    public byte[] lastNonce;
    public byte[] lastKeyConfirmation;

    @Override
    public void onHello(long epoch, int version, int applicationId, String nodeId, long lastAppliedEpoch,
                        long lastAppliedOffset, int lane, EncryptionMode encryptionMode, byte[] nonce) {
        lastNonce = nonce;
        frames.add("HELLO epoch=" + epoch + " version=" + version + application(applicationId) + " node=" + nodeId
                + " lastEpoch=" + lastAppliedEpoch + " lastOffset=" + lastAppliedOffset + " lane=" + lane
                + encryption(encryptionMode, nonce));
    }

    @Override
    public void onWelcome(long epoch, int version, int applicationId, String leaderId, long resumeOffset, int lanes,
                          EncryptionMode encryptionMode, byte[] nonce) {
        lastNonce = nonce;
        frames.add("WELCOME epoch=" + epoch + " version=" + version + application(applicationId) + " leader=" + leaderId
                + " resume=" + resumeOffset + " lanes=" + lanes + encryption(encryptionMode, nonce));
    }

    /**
     * Nothing for FIX sessions, so the frames of the tests replicating them read as they always did.
     */
    private static String application(int applicationId) {
        return applicationId == ReplicatedSessions.APPLICATION_ID ? "" : " application=" + Integer.toHexString(applicationId);
    }

    @Override
    public void onKeyConfirmation(long epoch, byte[] confirmation) {
        lastKeyConfirmation = confirmation;
        frames.add("KEY_CONFIRMATION epoch=" + epoch + " bytes=" + confirmation.length);
    }

    /**
     * Nothing for a clear peer, so the frames of the tests that do not encrypt read as they always did.
     */
    private static String encryption(EncryptionMode encryptionMode, byte[] nonce) {
        if (encryptionMode == EncryptionMode.DISABLED) {
            return "";
        }
        return " encryption=" + encryptionMode + " nonce=" + (Arrays.equals(nonce, new byte[nonce.length]) ? "zeros" : "random");
    }

    /**
     * A FIX session's stream, as its type and id.
     */
    @Override
    public void onStream(long epoch, int streamKey, StreamReplicationMode replicationMode, String streamName,
                         String storageIdentity) {
        TypedSessionId session = TypedSessionId.ofStreamName(streamName);
        frames.add("STREAM epoch=" + epoch + " key=" + streamKey + " " + session.getFixSessionType() + " " + replicationMode
                + " id=" + session.getFixSessionId() + " identity=" + storageIdentity);
    }

    @Override
    public void onStreamsAnnounced(long epoch, int announcedCount) {
        frames.add("STREAMS_ANNOUNCED epoch=" + epoch + " count=" + announcedCount);
    }

    @Override
    public void onStreamAnswer(long epoch, int streamKey, StreamAnswer answer) {
        frames.add("STREAM_ANSWER epoch=" + epoch + " key=" + streamKey + " " + answer);
    }

    /**
     * A FIX store event, as its type and seqnum.
     */
    @Override
    public void onRecord(long epoch, long logOffset, int streamKey, byte contentType, byte flags, long position,
                         ByteBuffer buffer, int payloadIndex, int payloadLength) {
        frames.add("RECORD epoch=" + epoch + " offset=" + logOffset + " key=" + streamKey + " "
                + StoreEventType.from(contentType) + " seq=" + position + " message="
                + text(buffer, payloadIndex, payloadLength) + flags(flags));
    }

    /**
     * A FIX session's state, as its next seqnums.
     */
    @Override
    public void onSnapshotBegin(long epoch, int streamKey, long snapshotOffset, ByteBuffer buffer, int stateIndex,
                                int stateLength) {
        frames.add("SNAPSHOT_BEGIN epoch=" + epoch + " key=" + streamKey + " snapshotOffset=" + snapshotOffset
                + state(buffer, stateIndex, stateLength));
    }

    @Override
    public void onSnapshotRecord(long epoch, int streamKey, byte contentType, byte flags, long position,
                                 ByteBuffer buffer, int payloadIndex, int payloadLength) {
        String type = contentType == StoreEventType.STORE_MESSAGE.getCode() ? "" : " " + StoreEventType.from(contentType);
        frames.add("SNAPSHOT_RECORD epoch=" + epoch + " key=" + streamKey + type + " seq=" + position + " message="
                + text(buffer, payloadIndex, payloadLength) + flags(flags));
    }

    @Override
    public void onSnapshotEnd(long epoch, int streamKey, int recordCount, ByteBuffer buffer, int stateIndex,
                              int stateLength) {
        frames.add("SNAPSHOT_END epoch=" + epoch + " key=" + streamKey + " count=" + recordCount
                + state(buffer, stateIndex, stateLength));
    }

    private static String flags(byte flags) {
        return ((flags & WireFormat.CONTINUED_FRAGMENT) != 0 ? " continued" : "")
                + ((flags & WireFormat.MORE_FRAGMENTS) != 0 ? " more" : "")
                + ((flags & WireFormat.RESET) != 0 ? " reset" : "");
    }

    private static String state(ByteBuffer buffer, int index, int length) {
        if (length != 2 * Long.BYTES) {
            return " state=" + length + " bytes";
        }
        return " nextIn=" + buffer.getLong(index) + " nextOut=" + buffer.getLong(index + Long.BYTES);
    }

    @Override
    public void onAck(long epoch, long appliedOffset, boolean inSync, long pendingWrites) {
        frames.add("ACK epoch=" + epoch + " applied=" + appliedOffset + " inSync=" + inSync
                + (pendingWrites > 0 ? " pending=" + pendingWrites : ""));
    }

    @Override
    public void onReceived(long epoch, long receivedOffset) {
        frames.add("RECEIVED epoch=" + epoch + " received=" + receivedOffset);
    }

    @Override
    public void onHeartbeat(long epoch, long logTailOffset) {
        frames.add("HEARTBEAT epoch=" + epoch + " tail=" + logTailOffset);
    }

    @Override
    public void onClose(long epoch, CloseReason reason, String text) {
        frames.add("CLOSE epoch=" + epoch + " " + reason + " " + text);
    }
}
