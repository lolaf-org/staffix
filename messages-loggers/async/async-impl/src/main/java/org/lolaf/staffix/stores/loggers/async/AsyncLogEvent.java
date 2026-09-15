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

import lombok.Getter;
import lombok.Value;
import net.openhft.chronicle.bytes.Bytes;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.stores.core.async.AsyncEvent;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * One logged message crossing the queue to the consumer that writes it.
 */
@Getter
public class AsyncLogEvent implements FixMessagesLogger.BatchingLogger.LogEvent, AsyncEvent {
    private final UTCTime.TimeImpl logTime;
    private final Bytes<ByteBuffer> messageTypeBuffer;
    private final Consumer<AsyncLogEvent> released;
    private FixMessagesLogger.LogEventType logEventType;
    private MessageType messageType;
    private ByteBuffer message;
    private String event;
    private Object[] eventParams;

    public AsyncLogEvent(ByteBuffer message, Bytes<ByteBuffer> messageTypeBuffer, Consumer<AsyncLogEvent> released) {
        this.message = message;
        this.messageTypeBuffer = messageTypeBuffer;
        this.logTime = new UTCTime.TimeImpl();
        this.released = released == null ? b -> {
            // nothing to do
        } : released;
    }

    private static ByteBuffer cloneByteBuffer(final ByteBuffer original) {
        ByteBuffer clone = (original.isDirect()) ?
                ByteBuffer.allocateDirect(original.capacity()) :
                ByteBuffer.allocate(original.capacity());
        ByteBuffer readOnlyCopy = original.asReadOnlyBuffer();

        readOnlyCopy.flip();
        clone.put(readOnlyCopy);
        clone.position(original.position());
        clone.limit(original.limit());
        clone.order(original.order());
        return clone;
    }

    public void from(FixMessagesLogger.LogEventType logEventType, String event, Object[] eventParams) {
        this.logEventType = logEventType;
        this.messageType = null;
        this.event = event;
        this.eventParams = eventParams;
    }

    public void from(FixMessagesLogger.LogEventType logEventType, MessageType messageType, ByteBuffer message) {
        this.logEventType = logEventType;
        this.messageType = messageType;
        this.event = null;
        this.eventParams = null;
        this.message = message;
    }

    public void from(UTCTime utcTime) {
        this.logTime.from(utcTime.getEpochSeconds(), utcTime.getNanosOfSecond());
    }

    public void afterSerialization() {
        this.messageType = null;
        this.message = null;
        this.logEventType = null;
        this.event = null;
        this.eventParams = null;
    }

    @Override
    public void release() {
        // called after deserialization
        released.accept(this);
    }

    @Override
    public FixMessagesLogger.BatchingLogger.LogEvent asImmutable() {
        return new ImmutableLogEvent(logTime.asImmutable(), messageType, cloneByteBuffer(message), logEventType);
    }

    @Value
    private static class ImmutableLogEvent implements FixMessagesLogger.BatchingLogger.LogEvent {
        UTCTime logTime;
        MessageType messageType;
        ByteBuffer message;
        FixMessagesLogger.LogEventType logEventType;

        @Override
        public FixMessagesLogger.BatchingLogger.LogEvent asImmutable() {
            return this;
        }
    }
}