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
package org.lolaf.staffix.stores.loggers.file;

import lombok.Builder;
import lombok.Getter;
import org.jspecify.annotations.NullMarked;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.codec.serde.UtcDateTimeSerde;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * The time, a prefix marking the direction or a session event, then the message as received or sent:
 * {@code 20261016-10:15:30.123456 <-: 8=FIX.4.4...}. The default format.
 */
@Getter
@Builder(toBuilder = true)
@NullMarked
public class PlainFileLogLineFormat implements FileLogLineFormat {

    /**
     * Marks a session event line in the log.
     */
    @Builder.Default
    private final String logEventPrefix = " EVENT: ";
    /**
     * Marks an outbound message line.
     */
    @Builder.Default
    private final String logOutPrefix = " ->: ";
    /**
     * Marks an inbound message line.
     */
    @Builder.Default
    private final String logInPrefix = " <-: ";

    @Override
    public FileLogLineFormatter newFormatter(String fixEngineId, String fixInstanceId, FixSessionId fixSessionId,
                                             MessageTypeRegistry messageTypeRegistry, TimeUnit logTimePrecision) {
        return new Formatter(logInPrefix.getBytes(SerDe.CHARSET), logOutPrefix.getBytes(SerDe.CHARSET),
                logEventPrefix.getBytes(SerDe.CHARSET), logTimePrecision);
    }

    private static final class Formatter implements FileLogLineFormatter {
        private final byte[] inPrefix;
        private final byte[] outPrefix;
        private final byte[] eventPrefix;
        private final TimeUnit logTimePrecision;

        private Formatter(byte[] inPrefix, byte[] outPrefix, byte[] eventPrefix, TimeUnit logTimePrecision) {
            this.inPrefix = inPrefix;
            this.outPrefix = outPrefix;
            this.eventPrefix = eventPrefix;
            this.logTimePrecision = logTimePrecision;
        }

        @Override
        public void formatIncoming(UTCTime time, MessageType messageType, ByteBuffer message, ByteBuffer line) {
            write(time, inPrefix, message, line);
        }

        @Override
        public void formatOutgoing(UTCTime time, MessageType messageType, ByteBuffer message, ByteBuffer line) {
            write(time, outPrefix, message, line);
        }

        @Override
        public void formatEvent(UTCTime time, ByteBuffer event, ByteBuffer line) {
            write(time, eventPrefix, event, line);
        }

        private void write(UTCTime time, byte[] prefix, ByteBuffer text, ByteBuffer line) {
            line.put(UtcDateTimeSerde.serializeTime(time, logTimePrecision)).put(prefix).put(text).put((byte) '\n');
        }
    }
}
