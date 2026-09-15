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
package org.lolaf.staffix.stores.messages.async.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

import java.time.Duration;

/**
 * One configured instance of the asynchronous message store wrapper: its instance id and the settings a session naming that id gets.
 */
@Data
@ConfigurationPropertiesSource
public class AsyncStoreEntryProps {
    /**
     * Key into any other store contributor (e.g. an entry under
     * {@code staffix.messages-stores-memory.instances}).
     */
    private String wraps;
    /**
     * Directory where the async chronicle queues for FIX sessions are stored.
     */
    private String asyncQueueDirectory;
    /**
     * How often the wrapped store is checked after it has failed, to see whether it is back.
     */
    private Duration underlyingStoreResourceWatchTaskCheckDelay;
    /**
     * Max duration to flush all pending message on startup that for some reason have not been processed before
     * last shutdown
     */
    private Duration flushPendingMessagesOnStartupDelay;
    /**
     * Max duration a {@code find} call waits for the async queue to drain (all pending writes applied to the
     * wrapped store) before reading, so a resend sees messages that were stored just before. Set to {@code
     * null} to read immediately without waiting.
     */
    private Duration findWaitForEmptyQueueTimeout;
    /**
     * Default byte buffer size for reading FIX messages from chronicle and passing them to the wrapped store
     */
    private Integer messageByteBufferSize;
    /**
     * Configure to use a heap or direct bytebuffer to transferred logs from the async queue
     */
    private Boolean useDirectByteBuffer;
}
