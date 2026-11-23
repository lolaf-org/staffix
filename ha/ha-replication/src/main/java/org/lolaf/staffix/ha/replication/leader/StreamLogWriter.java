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
package org.lolaf.staffix.ha.replication.leader;

import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.ha.replication.StreamWriter;
import org.lolaf.staffix.ha.replication.log.LogFullWait;
import org.lolaf.staffix.ha.replication.log.LogRecord;
import org.lolaf.staffix.ha.replication.log.ReplicationLog;
import org.lolaf.staffix.ha.replication.spi.RecordFlag;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;

import java.nio.ByteBuffer;
import java.time.Duration;

/**
 * Appends to the log of the stream's lane, wakes the followers' senders, and waits on {@link Followers} for acks.
 */
public final class StreamLogWriter implements StreamWriter {

    private final int streamKey;
    private final ReplicationLog log;
    private final Followers followers;
    private final LogRecord logRecord;
    private final LogFullWait logFullWait;
    private final IdleStrategy ackIdle;
    private volatile boolean waiterAdded;

    /**
     * @param ackIdle what an ack wait parks on, woken by the acks
     */
    public StreamLogWriter(int streamKey, ReplicationLog log, Followers followers, Duration logFullTimeout,
                           IdleStrategy ackIdle) {
        this.streamKey = streamKey;
        this.log = log;
        this.followers = followers;
        this.logRecord = new LogRecord();
        this.logFullWait = new LogFullWait(logFullTimeout);
        this.ackIdle = ackIdle;
    }

    @Override
    public long append(byte contentType, RecordFlag flag, long position, @Nullable ByteBuffer buffer, int payloadIndex,
                       int payloadLength) {
        long offset = log.append(logRecord.set(streamKey, contentType, flag, position, buffer, payloadIndex,
                payloadLength), logFullWait);
        followers.wakeSenders();
        return offset;
    }

    @Override
    public int getMaxPayloadLength() {
        return log.getMaxPayloadLength();
    }

    @Override
    public Ack awaitAck(long offset, StreamReplicationMode mode, long timeoutNanos) {
        if (!waiterAdded) {
            followers.addWaiter(ackIdle);
            waiterAdded = true;
        }
        return followers.awaitAck(streamKey, offset, mode, timeoutNanos, ackIdle);
    }

    @Override
    public void close() {
        if (waiterAdded) {
            followers.removeWaiter(ackIdle);
            waiterAdded = false;
        }
    }
}
