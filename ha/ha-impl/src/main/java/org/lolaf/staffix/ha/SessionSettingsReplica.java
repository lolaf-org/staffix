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

import lombok.RequiredArgsConstructor;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.ha.SessionSettingsDigests.Digest;
import org.lolaf.replicos.spi.ReplicaSink;
import org.lolaf.replicos.spi.WireFormatException;

import java.nio.ByteBuffer;

/**
 * Where a follower takes the leader's {@link SessionSettingsStream}: into its comparison, which writes nothing a lane
 * waits for.
 */
@RequiredArgsConstructor
final class SessionSettingsReplica implements ReplicaSink {

    private final SessionSettingsComparison comparison;

    @Override
    public void apply(byte contentType, long position, ByteBuffer buffer, int payloadIndex, int payloadLength) {
        take(contentType, buffer, payloadIndex, payloadLength);
    }

    @Override
    public void snapshotBegin(ByteBuffer buffer, int stateIndex, int stateLength) {
        requireNoState(stateLength);
        comparison.snapshotBegin();
    }

    @Override
    public void snapshotRecord(byte contentType, long position, ByteBuffer buffer, int payloadIndex, int payloadLength) {
        take(contentType, buffer, payloadIndex, payloadLength);
    }

    @Override
    public void snapshotEnd(ByteBuffer buffer, int stateIndex, int stateLength) {
        requireNoState(stateLength);
        comparison.snapshotEnd();
    }

    @Override
    public void writePending() {
    }

    @Override
    public void discardPending() {
    }

    @Override
    public long getPendingWrites() {
        return 0;
    }

    @Override
    public void stop(Deadline deadline) {
    }

    private void take(byte contentType, ByteBuffer buffer, int payloadIndex, int payloadLength) {
        Digest digest = SessionSettingsRecords.decode(contentType, buffer, payloadIndex, payloadLength);
        if (contentType == SessionSettingsRecords.FORGOTTEN) {
            comparison.onForgotten(digest.storedSession());
        } else {
            comparison.onDigest(digest);
        }
    }

    private static void requireNoState(int stateLength) {
        if (stateLength != 0) {
            throw new WireFormatException("The session settings stream has no state, got " + stateLength + " bytes");
        }
    }
}
