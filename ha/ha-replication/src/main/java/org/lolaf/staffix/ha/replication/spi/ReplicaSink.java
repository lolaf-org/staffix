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
package org.lolaf.staffix.ha.replication.spi;

import org.lolaf.ringos.Deadline;

import java.nio.ByteBuffer;

/**
 * Where a follower applies one replicated stream: its records in position order, whole, and its snapshots, each of
 * which starts the copy over. A payload or a state is lent as a range of the receive buffer, which is put back as it
 * was. Writes may be kept until {@link #writePending()}, which the follower calls before it acknowledges them.
 *
 * <p>One lane thread at a time. A method throws {@link org.lolaf.staffix.ha.replication.wire.WireFormatException} for what it
 * cannot make sense of, which the follower takes as the leader's error; any other exception as its own failure to
 * apply, after which it reconnects to resume past the last record written.
 */
public interface ReplicaSink {

    void apply(byte contentType, long position, ByteBuffer buffer, int payloadIndex, int payloadLength);

    /**
     * Discards the copy: the snapshot's records and its end follow.
     */
    void snapshotBegin(ByteBuffer buffer, int stateIndex, int stateLength);

    void snapshotRecord(byte contentType, long position, ByteBuffer buffer, int payloadIndex, int payloadLength);

    /**
     * @param stateIndex the stream's state when the snapshot caught up with it, which supersedes the one it began with
     */
    void snapshotEnd(ByteBuffer buffer, int stateIndex, int stateLength);

    void writePending();

    void discardPending();

    /**
     * What was accepted and not durably written yet, for the leader's view of this follower.
     */
    long getPendingWrites();

    void stop(Deadline deadline);
}
