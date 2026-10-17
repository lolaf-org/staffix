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
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.monitoring.FixMessageDirection;
import org.lolaf.staffix.api.monitoring.FixMonitoringAttributes;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.codec.serde.UtcDateTimeSerde;
import org.lolaf.staffix.collections.IndexableMap;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * A logfmt head carrying the time and the {@link FixMonitoringAttributes} metrics, OTLP logs and traces use, then
 * {@code " - "} and the message:
 * {@code time=2026-10-16T10:15:30.123456Z fix.eid=E1 fix.iid=acceptor fix.sg=GRP fix.sn=CLIENT1 fix.md=i fix.mt=D - 8=FIX.4.4|9=148|...}.
 * A session event has no {@code fix.md} nor {@code fix.mt}.
 * <p>
 * Only the head is logfmt: a log shipper splits the line at the first {@code " - "} and parses the part before it.
 * Every attribute comes from what the logger already knows, never from parsing the message.
 */
@Getter
@Builder(toBuilder = true)
@NullMarked
public class LogfmtFileLogLineFormat implements FileLogLineFormat {

    /**
     * Replaces the SOH between the message's fields, which line tools and people cannot read; {@code '|'} by default,
     * how FIX tools render SOH. It is the one cost per byte of the message this format adds: {@code null} writes the
     * message as is, as does putting the logger behind the async logger. A {@code '|'} inside a field's value makes
     * the replacement ambiguous.
     */
    @Builder.Default
    private final @Nullable Character fixMessageFieldsDelimiter = '|';

    @Override
    public FileLogLineFormatter newFormatter(String fixEngineId, String fixInstanceId, FixSessionId fixSessionId,
                                             MessageTypeRegistry messageTypeRegistry, TimeUnit logTimePrecision) {
        String session = pair(FixMonitoringAttributes.FIX_ENGINE_ID, fixEngineId)
                + pair(FixMonitoringAttributes.FIX_INSTANCE_ID, fixInstanceId)
                + pair(FixMonitoringAttributes.FIX_SESSION_GROUP, fixSessionId.getGroup())
                + pair(FixMonitoringAttributes.FIX_SESSION_NAME, fixSessionId.getName());
        IndexableMap<MessageType, byte[]> messageTypes = new IndexableMap<>(MessageType.class);
        messageTypeRegistry.getMessageTypes().forEach(type ->
                messageTypes.put(type, bytes(pair(FixMonitoringAttributes.FIX_MESSAGE_TYPE, type.code()))));
        return new Formatter(bytes(session + pair(FixMonitoringAttributes.FIX_MESSAGE_DIRECTION, FixMessageDirection.IN.getValue())),
                bytes(session + pair(FixMonitoringAttributes.FIX_MESSAGE_DIRECTION, FixMessageDirection.OUT.getValue())),
                bytes(session), messageTypes, logTimePrecision, fixMessageFieldsDelimiter);
    }

    private static String pair(FixMonitoringAttributes attribute, String value) {
        return " " + attribute.getKey() + "=" + value;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(SerDe.CHARSET);
    }

    private static final class Formatter implements FileLogLineFormatter {
        private static final byte[] TIME = bytes("time=");
        private static final byte[] SEPARATOR = bytes(" - ");

        private final byte[] incomingHead;
        private final byte[] outgoingHead;
        private final byte[] eventHead;
        private final IndexableMap<MessageType, byte[]> messageTypes;
        private final TimeUnit logTimePrecision;
        private final boolean replaceSoh;
        private final byte delimiter;

        private Formatter(byte[] incomingHead, byte[] outgoingHead, byte[] eventHead,
                          IndexableMap<MessageType, byte[]> messageTypes, TimeUnit logTimePrecision,
                          @Nullable Character delimiter) {
            this.incomingHead = incomingHead;
            this.outgoingHead = outgoingHead;
            this.eventHead = eventHead;
            this.messageTypes = messageTypes;
            this.logTimePrecision = logTimePrecision;
            this.replaceSoh = delimiter != null;
            this.delimiter = delimiter == null ? CoreFields.FIELD_SEPARATOR_BYTE : (byte) delimiter.charValue();
        }

        @Override
        public void formatIncoming(UTCTime time, MessageType messageType, ByteBuffer message, ByteBuffer line) {
            writeMessage(time, incomingHead, messageType, message, line);
        }

        @Override
        public void formatOutgoing(UTCTime time, MessageType messageType, ByteBuffer message, ByteBuffer line) {
            writeMessage(time, outgoingHead, messageType, message, line);
        }

        @Override
        public void formatEvent(UTCTime time, ByteBuffer event, ByteBuffer line) {
            writeHead(time, eventHead, line);
            line.put(SEPARATOR).put(event).put((byte) '\n');
        }

        private void writeMessage(UTCTime time, byte[] head, MessageType messageType, ByteBuffer message,
                                  ByteBuffer line) {
            writeHead(time, head, line);
            byte[] messageTypePair = messageTypes.get(messageType);
            if (messageTypePair != null) {
                line.put(messageTypePair);
            }
            line.put(SEPARATOR);
            if (replaceSoh) {
                for (int i = message.position(); i < message.limit(); i++) {
                    byte b = message.get(i);
                    line.put(b == CoreFields.FIELD_SEPARATOR_BYTE ? delimiter : b);
                }
            } else {
                line.put(message);
            }
            line.put((byte) '\n');
        }

        private void writeHead(UTCTime time, byte[] head, ByteBuffer line) {
            line.put(TIME).put(UtcDateTimeSerde.serializeRfc3339(time, logTimePrecision)).put(head);
        }
    }
}
