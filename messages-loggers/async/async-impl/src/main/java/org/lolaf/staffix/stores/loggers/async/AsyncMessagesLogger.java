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
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.stores.core.async.AsyncEventInstanceProvider;
import org.lolaf.staffix.stores.core.async.AsyncStoreThreads;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps any message logger so the write leaves the FIX thread, over the same persistent queue the asynchronous
 * store uses.
 */
public class AsyncMessagesLogger extends Startable.SimpleStartable<FixMessagesLogger> implements FixMessagesLogger {

    private final AsyncMessagesLoggerSettings asyncMessagesLoggerSettings;
    private final FixMessagesLogger wrappedFixMessagesLogger;
    private final AsyncStoreThreads<AsyncLogEvent> asyncStoreThreads;
    private final Map<FixSessionId, AsyncLogger> loggers;
    private final AsyncEventInstanceProvider<AsyncLogEvent> asyncEventInstanceProvider;

    protected AsyncMessagesLogger(AsyncMessagesLoggerSettings asyncMessagesLoggerSettings) {
        this.asyncMessagesLoggerSettings = asyncMessagesLoggerSettings;
        boolean directByteBuffer = asyncMessagesLoggerSettings.isUseDirectByteBuffer();
        int logBufferSize = asyncMessagesLoggerSettings.getLogByteBufferSize();
        this.asyncEventInstanceProvider = releaseCallback -> new AsyncLogEvent(
                directByteBuffer ? ByteBuffer.allocateDirect(logBufferSize) : ByteBuffer.allocate(logBufferSize),
                directByteBuffer ? Bytes.elasticByteBuffer(16) : Bytes.elasticHeapByteBuffer(16), releaseCallback);
        this.asyncStoreThreads = new AsyncStoreThreads<>(asyncMessagesLoggerSettings.getAsyncStoreSettings(), asyncMessagesLoggerSettings.getInstanceId(), asyncEventInstanceProvider);
        this.loggers = new ConcurrentHashMap<>();
        this.wrappedFixMessagesLogger = asyncMessagesLoggerSettings.getWrappedLoggerSettings().instance();
    }

    @Override
    public Logger getLogger(String fixInstanceId, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry) {
        return loggers.computeIfAbsent(fixSessionId, sid -> new AsyncLogger(wrappedFixMessagesLogger.getLogger(fixInstanceId, fixSessionId, messageTypeRegistry),
                fixSessionId, messageTypeRegistry, asyncMessagesLoggerSettings, asyncStoreThreads, asyncEventInstanceProvider));
    }

    @Override
    protected void startMe() throws StartStopException {
        asyncStoreThreads.start();
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        asyncStoreThreads.stop(stopDeadline);
        loggers.clear();
    }

    @Override
    public String getInstanceId() {
        return asyncMessagesLoggerSettings.getInstanceId();
    }

    public static class AsyncMessagesLoggerFactoryImpl implements FixMessagesLoggerSettings.FixMessagesLoggerFactory<AsyncMessagesLoggerSettings> {

        @Override
        public FixMessagesLogger newInstance(AsyncMessagesLoggerSettings settings) {
            return new AsyncMessagesLogger(settings);
        }

        @Override
        public Class<AsyncMessagesLoggerSettings> getSettingsClass() {
            return AsyncMessagesLoggerSettings.class;
        }
    }
}