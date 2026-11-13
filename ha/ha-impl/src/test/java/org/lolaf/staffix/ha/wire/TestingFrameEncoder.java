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
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;

import java.nio.ByteBuffer;

/**
 * Whole frames that production only writes in parts, a replication log holding a store event's body and a snapshot
 * sending fragments.
 */
@UtilityClass
public class TestingFrameEncoder {

    public static int storeEventBytes(int messageLength) {
        return WireFormat.HEADER_BYTES + FrameType.STORE_EVENT.getFixedPayloadBytes() + messageLength;
    }

    /**
     * @param message the bytes from its position to its limit, left untouched; null for an event carrying none
     */
    public static int storeEvent(ByteBuffer out, int index, long epoch, long logOffset, int sessionKey,
                                 StoreEventType eventType, long sequenceNumber, @Nullable ByteBuffer message) {
        int bodyLength = message == null
                ? FrameEncoder.storeEventBody(out, index + WireFormat.STORE_EVENT_BODY_OFFSET, sessionKey, eventType,
                        sequenceNumber, null, 0, 0, (byte) 0)
                : FrameEncoder.storeEventBody(out, index + WireFormat.STORE_EVENT_BODY_OFFSET, sessionKey, eventType,
                        sequenceNumber, message, message.position(), message.remaining(), (byte) 0);
        return FrameEncoder.storeEventHeader(out, index, epoch, logOffset, bodyLength);
    }

    /**
     * @param message the bytes from its position to its limit, left untouched
     */
    public static int snapshotMessage(ByteBuffer out, int index, long epoch, int sessionKey, long sequenceNumber,
                                      ByteBuffer message) {
        return FrameEncoder.snapshotMessage(out, index, epoch, sessionKey, sequenceNumber, message, message.position(),
                message.remaining(), (byte) 0);
    }
}
