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
package org.lolaf.staffix.ha;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.wire.StreamReplicationMode;
import org.lolaf.staffix.ha.replication.wire.WireFormat;

import java.nio.ByteBuffer;

/**
 * The frames of FIX sessions as their replication encodes them.
 */
@UtilityClass
public class FixFrames {

    /**
     * A store event as the FIX side replicates it: its type's code as content type, its seqnum as position.
     *
     * @param message the bytes from its position to its limit, left untouched; null for an event carrying none
     */
    public static int record(ByteBuffer out, int index, long epoch, long logOffset, int streamKey,
                             StoreEventType eventType, long sequenceNumber, @Nullable ByteBuffer message) {
        byte flags = eventType == StoreEventType.RESET_SEQ_NUM ? WireFormat.RESET : 0;
        int bodyLength = FrameEncoder.recordBody(out, index + WireFormat.RECORD_BODY_OFFSET, streamKey,
                eventType.getCode(), flags, sequenceNumber, message, message == null ? 0 : message.position(),
                message == null ? 0 : message.remaining());
        return FrameEncoder.recordHeader(out, index, epoch, logOffset, bodyLength);
    }

    /**
     * @param message the bytes from its position to its limit, left untouched
     */
    public static int snapshotRecord(ByteBuffer out, int index, long epoch, int streamKey, long sequenceNumber,
                                     ByteBuffer message) {
        return FrameEncoder.snapshotRecord(out, index, epoch, streamKey, StoreEventType.STORE_MESSAGE.getCode(), (byte) 0,
                sequenceNumber, message, message.position(), message.remaining());
    }

    /**
     * A FIX session's snapshot state: its next incoming and outgoing seqnums.
     */
    public static ByteBuffer seqNums(long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        return ByteBuffer.allocate(2 * Long.BYTES).putLong(0, nextIncomingSeqNum).putLong(Long.BYTES, nextOutgoingSeqNum);
    }

    public static int snapshotBegin(ByteBuffer out, int index, long epoch, int streamKey, long snapshotOffset,
                                    long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        return FrameEncoder.snapshotBegin(out, index, epoch, streamKey, snapshotOffset,
                seqNums(nextIncomingSeqNum, nextOutgoingSeqNum), 0, 2 * Long.BYTES);
    }

    public static int snapshotEnd(ByteBuffer out, int index, long epoch, int streamKey, int recordCount,
                                  long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        return FrameEncoder.snapshotEnd(out, index, epoch, streamKey, recordCount,
                seqNums(nextIncomingSeqNum, nextOutgoingSeqNum), 0, 2 * Long.BYTES);
    }

    /**
     * A FIX session's stream, named after its type and id.
     */
    public static int stream(ByteBuffer out, int index, long epoch, int streamKey, FixSessionType fixSessionType,
                             ReplicationMode replicationMode, String fixSessionId, @Nullable String storageIdentity) {
        return FrameEncoder.stream(out, index, epoch, streamKey, StreamReplicationMode.from(replicationMode.getCode()),
                new TypedSessionId(fixSessionType, fixSessionId).toStreamName(), storageIdentity);
    }

    public static int hello(ByteBuffer out, int index, long epoch, String nodeId, long lastAppliedEpoch,
                            long lastAppliedOffset, int lane, EncryptionMode encryptionMode, byte[] nonce) {
        return FrameEncoder.hello(out, index, epoch, ReplicatedSessions.APPLICATION_ID, nodeId, lastAppliedEpoch,
                lastAppliedOffset, lane, encryptionMode, nonce);
    }

    public static int welcome(ByteBuffer out, int index, long epoch, String leaderId, long resumeOffset, int lanes,
                              EncryptionMode encryptionMode, byte[] nonce) {
        return FrameEncoder.welcome(out, index, epoch, ReplicatedSessions.APPLICATION_ID, leaderId, resumeOffset, lanes,
                encryptionMode, nonce);
    }
}
