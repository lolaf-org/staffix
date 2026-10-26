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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Records each frame as one line, so a test compares every field at once.
 */
public class RecordingHandler implements FrameHandler {

    public final List<String> frames = new ArrayList<>();

    private static String text(ByteBuffer buffer, int index, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = buffer.get(index + i);
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    @Override
    public void onHello(long epoch, int version, String nodeId, long lastAppliedEpoch, long lastAppliedOffset) {
        frames.add("HELLO epoch=" + epoch + " version=" + version + " node=" + nodeId + " lastEpoch=" + lastAppliedEpoch
                + " lastOffset=" + lastAppliedOffset);
    }

    @Override
    public void onWelcome(long epoch, int version, String leaderId, long resumeOffset) {
        frames.add("WELCOME epoch=" + epoch + " version=" + version + " leader=" + leaderId + " resume=" + resumeOffset);
    }

    @Override
    public void onSession(long epoch, int sessionKey, FixSessionType fixSessionType, ReplicationMode replicationMode,
                          String fixSessionId, String storageIdentity) {
        frames.add("SESSION epoch=" + epoch + " key=" + sessionKey + " " + fixSessionType + " " + replicationMode
                + " id=" + fixSessionId + " identity=" + storageIdentity);
    }

    @Override
    public void onSessionStatus(long epoch, int sessionKey, SessionStatus status) {
        frames.add("SESSION_STATUS epoch=" + epoch + " key=" + sessionKey + " " + status);
    }

    @Override
    public void onStoreEvent(long epoch, long logOffset, int sessionKey, StoreEventType eventType, long sequenceNumber,
                             ByteBuffer buffer, int messageIndex, int messageLength, byte fragmentFlags) {
        frames.add("STORE_EVENT epoch=" + epoch + " offset=" + logOffset + " key=" + sessionKey + " " + eventType
                + " seq=" + sequenceNumber + " message=" + text(buffer, messageIndex, messageLength)
                + ((fragmentFlags & WireFormat.CONTINUED_FRAGMENT) != 0 ? " continued" : "")
                + ((fragmentFlags & WireFormat.MORE_FRAGMENTS) != 0 ? " more" : ""));
    }

    @Override
    public void onSnapshotBegin(long epoch, int sessionKey, long cutOffset, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        frames.add("SNAPSHOT_BEGIN epoch=" + epoch + " key=" + sessionKey + " cut=" + cutOffset + " nextIn=" + nextIncomingSeqNum
                + " nextOut=" + nextOutgoingSeqNum);
    }

    @Override
    public void onSnapshotMessage(long epoch, int sessionKey, long sequenceNumber, ByteBuffer buffer, int messageIndex,
                                  int messageLength) {
        frames.add("SNAPSHOT_MESSAGE epoch=" + epoch + " key=" + sessionKey + " seq=" + sequenceNumber + " message="
                + text(buffer, messageIndex, messageLength));
    }

    @Override
    public void onSnapshotEnd(long epoch, int sessionKey, int messageCount) {
        frames.add("SNAPSHOT_END epoch=" + epoch + " key=" + sessionKey + " count=" + messageCount);
    }

    @Override
    public void onAck(long epoch, long appliedOffset, boolean inSync) {
        frames.add("ACK epoch=" + epoch + " applied=" + appliedOffset + " inSync=" + inSync);
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
