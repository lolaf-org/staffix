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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

import java.time.Duration;

/**
 * One configured instance of the asynchronous message logger wrapper: its instance id and the settings a session naming that id gets.
 */
@Data
@ConfigurationPropertiesSource
public class AsyncLoggerEntryProps {
    /**
     * Key into any other logger contributor (e.g. an entry under
     * {@code staffix.messages-loggers-slf4j.instances}). The async wrapper replaces the
     * wrapped entry in the registry; the wrapped logger's {@code instance-id} stays in effect
     * (the wrapper itself has no separate instance id).
     */
    private String wraps;
    /**
     * Directory where the async chronicle queues for FIX sessions are stored.
     */
    private String asyncQueueDirectory;
    /**
     * How often the wrapped logger is checked after it has failed, to see whether it is back.
     */
    private Duration underlyingLoggerResourceWatchTaskCheckDelay;
    /**
     * Max duration to flush all pending message on startup that for some reason have not been processed before
     * last shutdown
     */
    private Duration flushPendingMessagesOnStartupDelay;
    /**
     * Default byte buffer size for reading logs from chronicle and passing them to the wrapped logger
     */
    private Integer logByteBufferSize;
    /**
     * Configure to use a heap or direct bytebuffer to transferred logs from the async queue
     */
    private Boolean useDirectByteBuffer;
}
