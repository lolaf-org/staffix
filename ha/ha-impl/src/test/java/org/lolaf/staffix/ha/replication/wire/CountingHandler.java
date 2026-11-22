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

import java.nio.ByteBuffer;

/**
 * Touches every field of the frequent frames and keeps only primitives, so measuring allocation measures the codec.
 */
class CountingHandler implements FrameHandler {

    long frames;
    long checksum;

    @Override
    public void onHello(long epoch, int version, int applicationId, String nodeId, long lastAppliedEpoch,
                        long lastAppliedOffset, int lane, EncryptionMode encryptionMode, byte[] nonce) {
        frames++;
    }

    @Override
    public void onWelcome(long epoch, int version, int applicationId, String leaderId, long resumeOffset, int lanes,
                          EncryptionMode encryptionMode, byte[] nonce) {
        frames++;
    }

    @Override
    public void onStream(long epoch, int streamKey, StreamReplicationMode replicationMode, String streamName,
                         String storageIdentity) {
        frames++;
    }

    @Override
    public void onStreamAnswer(long epoch, int streamKey, StreamAnswer answer) {
        frames++;
    }

    @Override
    public void onRecord(long epoch, long logOffset, int streamKey, byte contentType, byte flags, long position,
                         ByteBuffer buffer, int payloadIndex, int payloadLength) {
        frames++;
        checksum += epoch + logOffset + streamKey + contentType + flags + position + buffer.get(payloadIndex) + payloadLength;
    }

    @Override
    public void onSnapshotBegin(long epoch, int streamKey, long snapshotOffset, ByteBuffer buffer, int stateIndex,
                                int stateLength) {
        frames++;
    }

    @Override
    public void onSnapshotRecord(long epoch, int streamKey, byte contentType, byte flags, long position,
                                 ByteBuffer buffer, int payloadIndex, int payloadLength) {
        frames++;
    }

    @Override
    public void onSnapshotEnd(long epoch, int streamKey, int recordCount, ByteBuffer buffer, int stateIndex,
                              int stateLength) {
        frames++;
    }

    @Override
    public void onAck(long epoch, long appliedOffset, boolean inSync, long pendingWrites) {
        frames++;
        checksum += epoch + appliedOffset + (inSync ? 1 : 0);
    }

    @Override
    public void onReceived(long epoch, long receivedOffset) {
        frames++;
        checksum += epoch + receivedOffset;
    }

    @Override
    public void onHeartbeat(long epoch, long logTailOffset) {
        frames++;
        checksum += epoch + logTailOffset;
    }

    @Override
    public void onClose(long epoch, CloseReason reason, String text) {
        frames++;
    }
}
