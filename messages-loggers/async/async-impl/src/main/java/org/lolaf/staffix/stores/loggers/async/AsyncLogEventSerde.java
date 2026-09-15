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
package org.lolaf.staffix.stores.loggers.async;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.ValueOut;
import net.openhft.chronicle.wire.Wire;
import org.lolaf.ringos.unsafe.UnsafeOperations;
import org.lolaf.ringos.unsafe.UnsafeOperationsApi;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.serde.Hashing;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.stores.core.async.AsyncEventInstanceProvider;
import org.lolaf.staffix.stores.core.async.AsyncEventSerde;

import java.nio.ByteBuffer;

/**
 * Writes an {@link AsyncLogEvent} to the persistent queue and reads it back.
 */
public class AsyncLogEventSerde implements AsyncEventSerde<AsyncLogEvent> {

    private final MessageTypeRegistry messageTypeRegistry;

    private final AsyncLogEvent incoming;
    private final AsyncLogEvent outgoing;
    private final AsyncLogEvent event;
    private final AsyncLogEvent eventWithParams;

    public AsyncLogEventSerde(MessageTypeRegistry messageTypeRegistry, AsyncEventInstanceProvider<AsyncLogEvent> asyncEventInstanceProvider) {
        this.messageTypeRegistry = messageTypeRegistry;
        this.incoming = asyncEventInstanceProvider.instanciateAsyncEvent(null);
        this.outgoing = asyncEventInstanceProvider.instanciateAsyncEvent(null);
        this.event = asyncEventInstanceProvider.instanciateAsyncEvent(null);
        this.eventWithParams = asyncEventInstanceProvider.instanciateAsyncEvent(null);
    }


    public AsyncLogEvent logIncoming(UTCTime logTime, MessageType messageType, ByteBuffer message) {
        incoming.from(FixMessagesLogger.LogEventType.INCOMING_MSG, messageType, message);
        incoming.from(logTime);
        return incoming;
    }

    public AsyncLogEvent logOutgoing(UTCTime logTime, MessageType messageType, ByteBuffer message) {
        outgoing.from(FixMessagesLogger.LogEventType.OUTGOING_MSG, messageType, message);
        outgoing.from(logTime);
        return outgoing;
    }

    public AsyncLogEvent event(UTCTime eventTime, String eventString, Object... params) {
        event.from(FixMessagesLogger.LogEventType.EVENT_WITH_PARAMS, eventString, params);
        event.from(eventTime);
        return event;
    }

    public AsyncLogEvent event(UTCTime eventTime, String event) {
        eventWithParams.from(FixMessagesLogger.LogEventType.EVENT, event, null);
        eventWithParams.from(eventTime);
        return eventWithParams;
    }


    @Override
    public void serialize(DocumentContext documentContext, AsyncLogEvent toSerialize) {
        ValueOut wire = writeEventHeader(documentContext, toSerialize.getLogEventType(), toSerialize.getLogTime());
        if (toSerialize.getLogEventType().isMessageEvent()) {
            writeMessageTypeAndLog(toSerialize.getMessageType(), toSerialize.getMessage(), wire, documentContext);
        } else {
            wire.writeString(toSerialize.getEvent());
            Object[] params = toSerialize.getEventParams();
            if (params != null) {
                wire.writeInt(params.length);
                for (Object param : params) {
                    wire.writeString(param.toString());
                }
            }
        }
        toSerialize.afterSerialization();
    }

    private void writeMessageTypeAndLog(MessageType messageType, ByteBuffer message, ValueOut wire, DocumentContext dc) {
        wire.bytes(messageType.serialized());
        Bytes<?> documentBytes = dc.wire().bytes();
        documentBytes.write(documentBytes.writePosition(), message, message.position(), message.limit());
        documentBytes.writePosition(documentBytes.writePosition() + message.remaining());
    }

    private ValueOut writeEventHeader(DocumentContext dc, FixMessagesLogger.LogEventType logEventType, UTCTime logTime) {
        ValueOut wire = dc.wire().write();
        wire.writeByte(logEventType.getCode());
        wire.writeLong(logTime.getEpochSeconds());
        wire.writeInt(logTime.getNanosOfSecond());
        return wire;
    }

    @Override
    public void deserialize(DocumentContext documentContext, AsyncLogEvent toDeserialize) {
        Wire wire = documentContext.wire();
        ValueIn in = wire.read();
        FixMessagesLogger.LogEventType logEventType = FixMessagesLogger.LogEventType.from(in.readByte());

        toDeserialize.getLogTime().from(in.readLong(), in.readInt());
        switch (logEventType) {
            case INCOMING_MSG:
            case OUTGOING_MSG:
                toDeserialize.from(logEventType, readMessageType(in, toDeserialize.getMessageTypeBuffer(), messageTypeRegistry), readLog(wire, toDeserialize.getMessage()));
                break;
            case EVENT:
                toDeserialize.from(logEventType, in.readString(), null);
                break;
            case EVENT_WITH_PARAMS:
                String eventLocal = in.readString();
                int paramsCount = in.readInt();
                Object[] params = new Object[paramsCount];
                for (int i = 0; i < paramsCount; i++) {
                    params[i] = in.readString();
                }
                toDeserialize.from(logEventType, eventLocal, params);
                break;
        }
    }

    private MessageType readMessageType(ValueIn in, Bytes<ByteBuffer> messageTypeBuffer, MessageTypeRegistry messageTypeRegistry) {
        in.bytes(messageTypeBuffer.clear());
        // by default chronicle does not copy positions into destination ByteBuffer
        ByteBuffer byteBuffer = messageTypeBuffer.underlyingObject();
        byteBuffer.position((int) messageTypeBuffer.readPosition());
        byteBuffer.limit((int) messageTypeBuffer.writePosition());
        return messageTypeRegistry.find(Hashing.hash(byteBuffer, byteBuffer.position(), byteBuffer.limit() - byteBuffer.position()));
    }

    private ByteBuffer readLog(Wire wire, ByteBuffer logByteBuffer) {
        logByteBuffer.clear();
        Bytes<?> documentBytes = wire.bytes();
        int readRemaining = (int) documentBytes.readRemaining();
        if (readRemaining > logByteBuffer.capacity()) {
            UnsafeOperationsApi.ifAvailableDo(UnsafeOperations::invokeCleanerIfNeeded, logByteBuffer);
            logByteBuffer = logByteBuffer.isDirect()
                    ? ByteBuffer.allocateDirect(readRemaining) : ByteBuffer.allocate(readRemaining);
        }
        documentBytes.read(logByteBuffer);
        return logByteBuffer.flip();
    }
}