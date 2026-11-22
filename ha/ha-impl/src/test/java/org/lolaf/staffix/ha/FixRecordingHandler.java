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

import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.replication.wire.RecordingHandler;
import org.lolaf.staffix.ha.replication.wire.StreamReplicationMode;

import java.nio.ByteBuffer;

/**
 * Records the frames of FIX sessions: a stream as its session type and id, a record as its store event and seqnum,
 * a state as the next seqnums.
 */
public class FixRecordingHandler extends RecordingHandler {

    @Override
    protected int applicationId() {
        return ReplicatedSessions.APPLICATION_ID;
    }

    @Override
    protected String stream(StreamReplicationMode replicationMode, String streamName) {
        TypedSessionId session = TypedSessionId.ofStreamName(streamName);
        return session.getFixSessionType() + " " + replicationMode + " id=" + session.getFixSessionId();
    }

    @Override
    protected String record(byte contentType, long position, String payload) {
        return StoreEventType.from(contentType) + " seq=" + position + " message=" + payload;
    }

    /**
     * A message, the only event a snapshot copies, without its type.
     */
    @Override
    protected String snapshotRecord(byte contentType, long position, String payload) {
        String type = contentType == StoreEventType.STORE_MESSAGE.getCode() ? "" : StoreEventType.from(contentType) + " ";
        return type + "seq=" + position + " message=" + payload;
    }

    @Override
    protected String state(ByteBuffer buffer, int index, int length) {
        if (length != 2 * Long.BYTES) {
            return " state=" + length + " bytes";
        }
        return " nextIn=" + buffer.getLong(index) + " nextOut=" + buffer.getLong(index + Long.BYTES);
    }
}
