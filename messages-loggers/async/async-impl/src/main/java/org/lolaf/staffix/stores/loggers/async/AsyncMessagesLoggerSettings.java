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

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.lolaf.staffix.api.logging.AbstractFixMessageLoggerSettings;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.logging.LogObfuscator;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.stores.core.async.AsyncStoreSettings;

import java.time.Duration;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * The logger being wrapped, and the queue and threads in front of it.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class AsyncMessagesLoggerSettings extends AbstractFixMessageLoggerSettings {

    /**
     * Async store base settings
     */
    private AsyncStoreSettings asyncStoreSettings;

    /**
     * Delay for the task to check if a logger underlying resource is back up and can resume normal operation
     */
    @Builder.Default
    private Duration underlyingLoggerResourceWatchTaskCheckDelay = Duration.ofSeconds(2);
    /**
     * Max duration to flush all pending message on startup that for some reason have not been processed before last shutdown
     */
    @Builder.Default
    private Duration flushPendingMessagesOnStartupDelay = Duration.ofSeconds(60);
    /**
     * Default byte buffer size for reading logs from chronicle and passing them to the wrapped logger
     */
    @Builder.Default
    private int logByteBufferSize = 2048;
    /**
     * Configure to use a heap or direct bytebuffer to transferred logs from the async queue
     */
    @Builder.Default
    private boolean useDirectByteBuffer = true;

    /**
     * Wrapped logger settings
     */
    private FixMessagesLoggerSettings wrappedLoggerSettings;

    @Override
    public List<LogObfuscator> getLogObfuscators() {
        return wrappedLoggerSettings.getLogObfuscators();
    }

    @Override
    public boolean isLogEvents() {
        return wrappedLoggerSettings.isLogEvents();
    }

    @Override
    public boolean isLogIncoming() {
        return wrappedLoggerSettings.isLogIncoming();
    }

    @Override
    public boolean isLogOutgoing() {
        return wrappedLoggerSettings.isLogOutgoing();
    }

    @Override
    public String getInstanceId() {
        return wrappedLoggerSettings.getInstanceId();
    }

    @Override
    public BiPredicate<MessageType, FixMessagesLogger.LogEventType> getMessageFilter() {
        return wrappedLoggerSettings.getMessageFilter();
    }
}