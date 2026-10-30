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

import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;

import java.nio.ByteBuffer;

/**
 * Touches every field of the frequent frames and keeps only primitives, so measuring allocation measures the codec.
 */
class CountingHandler implements FrameHandler {

    long frames;
    long checksum;

    @Override
    public void onHello(long epoch, int version, String nodeId, long lastAppliedEpoch, long lastAppliedOffset) {
        frames++;
    }

    @Override
    public void onWelcome(long epoch, int version, String leaderId, long resumeOffset) {
        frames++;
    }

    @Override
    public void onSession(long epoch, int sessionKey, FixSessionType fixSessionType, ReplicationMode replicationMode,
                          String fixSessionId, String storageIdentity) {
        frames++;
    }

    @Override
    public void onSessionStatus(long epoch, int sessionKey, SessionStatus status) {
        frames++;
    }

    @Override
    public void onStoreEvent(long epoch, long logOffset, int sessionKey, StoreEventType eventType, long sequenceNumber,
                             ByteBuffer buffer, int messageIndex, int messageLength, byte fragmentFlags) {
        frames++;
        checksum += epoch + logOffset + sessionKey + eventType.getCode() + sequenceNumber + buffer.get(messageIndex) + messageLength;
    }

    @Override
    public void onSnapshotBegin(long epoch, int sessionKey, long cutOffset, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        frames++;
    }

    @Override
    public void onSnapshotMessage(long epoch, int sessionKey, long sequenceNumber, ByteBuffer buffer, int messageIndex,
                                  int messageLength, byte fragmentFlags) {
        frames++;
    }

    @Override
    public void onSnapshotEnd(long epoch, int sessionKey, int messageCount, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        frames++;
    }

    @Override
    public void onAck(long epoch, long appliedOffset, boolean inSync) {
        frames++;
        checksum += epoch + appliedOffset + (inSync ? 1 : 0);
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
