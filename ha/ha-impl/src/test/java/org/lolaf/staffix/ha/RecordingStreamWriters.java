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

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.lolaf.replicos.StreamWriter;
import org.lolaf.replicos.spi.RecordFlag;
import org.lolaf.replicos.spi.StreamReplicationMode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Writers keeping what their streams append, in one order across the streams, as the log would.
 */
final class RecordingStreamWriters {

    private final int maxPayloadLength;
    private final Queue<Appended> appended;
    /**
     * False appends without keeping anything, which allocates nothing.
     */
    @Setter
    private boolean recording;
    /**
     * What every ack wait returns at once.
     */
    @Setter
    private StreamWriter.Ack ack;
    /**
     * The flag of the record {@link #next()} last returned.
     */
    @Getter
    private @Nullable RecordFlag lastFlag;
    @Getter
    private int openWriters;
    private long nextOffset;

    RecordingStreamWriters(int maxPayloadLength) {
        this.maxPayloadLength = maxPayloadLength;
        this.appended = new ArrayDeque<>();
        this.recording = true;
        this.ack = StreamWriter.Ack.NO_FOLLOWER_IN_SYNC;
    }

    StreamWriter writer(int streamKey) {
        openWriters++;
        return new Writer(streamKey);
    }

    /**
     * The next record appended as "key type position payload", "none" past the last.
     */
    String next() {
        Appended record = appended.poll();
        if (record == null) {
            return "none";
        }
        lastFlag = record.flag;
        return record.text;
    }

    int count() {
        return appended.size();
    }

    private static final class Appended {

        private final String text;
        private final RecordFlag flag;

        private Appended(String text, RecordFlag flag) {
            this.text = text;
            this.flag = flag;
        }
    }

    private final class Writer implements StreamWriter {

        private final int streamKey;

        private Writer(int streamKey) {
            this.streamKey = streamKey;
        }

        @Override
        public long append(byte contentType, RecordFlag flag, long position, @Nullable ByteBuffer buffer,
                           int payloadIndex, int payloadLength) {
            if (payloadLength > maxPayloadLength) {
                throw new IllegalArgumentException("A payload of " + payloadLength + " bytes is longer than "
                        + maxPayloadLength);
            }
            if (recording) {
                byte[] payload = new byte[payloadLength];
                if (buffer != null) {
                    buffer.duplicate().position(payloadIndex).get(payload);
                }
                appended.add(new Appended(streamKey + " " + contentType + " " + position + " "
                        + new String(payload, StandardCharsets.US_ASCII), flag));
            }
            return nextOffset++;
        }

        @Override
        public int getMaxPayloadLength() {
            return maxPayloadLength;
        }

        @Override
        public Ack awaitAck(long offset, StreamReplicationMode mode, long timeoutNanos) {
            return ack;
        }

        @Override
        public void close() {
            openWriters--;
        }
    }
}
