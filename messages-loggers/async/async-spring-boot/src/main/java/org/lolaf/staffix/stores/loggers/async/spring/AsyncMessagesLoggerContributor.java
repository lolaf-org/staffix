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
package org.lolaf.staffix.stores.loggers.async.spring;

import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.spring.boot.spi.FixMessagesLoggerSettingsContributor;
import org.lolaf.staffix.stores.core.async.AsyncStoreSettings;
import org.lolaf.staffix.stores.loggers.async.AsyncMessagesLoggerSettings;

import java.util.Map;

/**
 * Contributes the asynchronous message logger wrapper's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class AsyncMessagesLoggerContributor implements FixMessagesLoggerSettingsContributor {

    private final AsyncMessagesLoggerProps props;

    public AsyncMessagesLoggerContributor(AsyncMessagesLoggerProps props) {
        this.props = props;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public void contribute(Map<String, FixMessagesLoggerSettings> registry) {
        props.getInstances().forEach((key, p) -> {
            if (p.getWraps() == null) {
                throw new IllegalArgumentException("staffix.messages-loggers-async.instances." + key + ".wraps is required");
            }
            FixMessagesLoggerSettings wrapped = registry.get(p.getWraps());
            if (wrapped == null) {
                throw new IllegalArgumentException("staffix.messages-loggers-async.instances." + key + ".wraps='" + p.getWraps()
                        + "' does not reference any logger contributed by slf4j/file/otlp modules");
            }
            if (p.getAsyncQueueDirectory() == null) {
                throw new IllegalArgumentException("staffix.messages-loggers-async.instances." + key + ".async-queue-directory is required");
            }
            AsyncMessagesLoggerSettings.AsyncMessagesLoggerSettingsBuilder<?, ?> b = AsyncMessagesLoggerSettings.builder()
                    .asyncStoreSettings(AsyncStoreSettings.builder().asyncQueueDirectory(p.getAsyncQueueDirectory()).build())
                    .wrappedLoggerSettings(wrapped);
            if (p.getUnderlyingLoggerResourceWatchTaskCheckDelay() != null)
                b.underlyingLoggerResourceWatchTaskCheckDelay(p.getUnderlyingLoggerResourceWatchTaskCheckDelay());
            if (p.getFlushPendingMessagesOnStartupDelay() != null)
                b.flushPendingMessagesOnStartupDelay(p.getFlushPendingMessagesOnStartupDelay());
            if (p.getLogByteBufferSize() != null) b.logByteBufferSize(p.getLogByteBufferSize());
            if (p.getUseDirectByteBuffer() != null) b.useDirectByteBuffer(p.getUseDirectByteBuffer());
            registry.put(p.getWraps(), b.build());
        });
    }
}
