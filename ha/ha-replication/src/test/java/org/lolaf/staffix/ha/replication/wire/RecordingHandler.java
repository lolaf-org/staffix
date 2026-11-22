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
import org.lolaf.staffix.ha.replication.journal.Journal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Records each frame as one line, so a test compares every field at once. Streams, records and states read as the
 * {@link Journal}'s, text; an application replicating other ones overrides how they read.
 */
public class RecordingHandler implements FrameHandler {

    public final List<String> frames;

    public RecordingHandler() {
        this.frames = new ArrayList<>();
    }

    protected static String text(ByteBuffer buffer, int index, int length) {
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
     * Nothing for the application the frames come from, so its tests' frames read shorter.
     */
    private String application(int applicationId) {
        return applicationId == applicationId() ? "" : " application=" + Integer.toHexString(applicationId);
    }

    protected int applicationId() {
        return Journal.APPLICATION_ID;
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

    @Override
    public void onStream(long epoch, int streamKey, StreamReplicationMode replicationMode, String streamName,
                         String storageIdentity) {
        frames.add("STREAM epoch=" + epoch + " key=" + streamKey + " " + stream(replicationMode, streamName)
                + " identity=" + storageIdentity);
    }

    protected String stream(StreamReplicationMode replicationMode, String streamName) {
        return replicationMode + " name=" + streamName;
    }

    @Override
    public void onStreamsAnnounced(long epoch, int announcedCount) {
        frames.add("STREAMS_ANNOUNCED epoch=" + epoch + " count=" + announcedCount);
    }

    @Override
    public void onStreamAnswer(long epoch, int streamKey, StreamAnswer answer) {
        frames.add("STREAM_ANSWER epoch=" + epoch + " key=" + streamKey + " " + answer);
    }

    @Override
    public void onRecord(long epoch, long logOffset, int streamKey, byte contentType, byte flags, long position,
                         ByteBuffer buffer, int payloadIndex, int payloadLength) {
        frames.add("RECORD epoch=" + epoch + " offset=" + logOffset + " key=" + streamKey + " "
                + record(contentType, position, text(buffer, payloadIndex, payloadLength)) + flags(flags));
    }

    protected String record(byte contentType, long position, String payload) {
        return "type=" + contentType + " pos=" + position + " payload=" + payload;
    }

    @Override
    public void onSnapshotBegin(long epoch, int streamKey, long snapshotOffset, ByteBuffer buffer, int stateIndex,
                                int stateLength) {
        frames.add("SNAPSHOT_BEGIN epoch=" + epoch + " key=" + streamKey + " snapshotOffset=" + snapshotOffset
                + state(buffer, stateIndex, stateLength));
    }

    @Override
    public void onSnapshotRecord(long epoch, int streamKey, byte contentType, byte flags, long position,
                                 ByteBuffer buffer, int payloadIndex, int payloadLength) {
        frames.add("SNAPSHOT_RECORD epoch=" + epoch + " key=" + streamKey + " "
                + snapshotRecord(contentType, position, text(buffer, payloadIndex, payloadLength)) + flags(flags));
    }

    protected String snapshotRecord(byte contentType, long position, String payload) {
        return record(contentType, position, payload);
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

    /**
     * With its leading space.
     */
    protected String state(ByteBuffer buffer, int index, int length) {
        return " state=" + text(buffer, index, length);
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
