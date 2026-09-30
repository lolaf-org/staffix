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
     * Instance id of the store to wrap, from any store module, for example an entry under
     * staffix.messages-stores-memory.instances. The wrapper takes that store's place under the same instance id,
     * so sessions naming it go through the wrapper.
     */
    private String wraps;
    /**
     * Directory holding the on-disk queues that buffer each session's writes.
     */
    private String asyncQueueDirectory;
    /**
     * How often the wrapped store is checked after it has failed, to see whether it is back.
     */
    private Duration underlyingStoreResourceWatchTaskCheckDelay;
    /**
     * How long a session start waits for writes left pending by the previous run to reach the wrapped store. Past it
     * the start fails rather than read stale sequence numbers. 2 minutes by default.
     */
    private Duration flushPendingMessagesOnStartupDelay;
    /**
     * How long a read waits for queued writes to reach the wrapped store, so a resend sees the messages stored just
     * before it. Past it the read fails rather than return an incomplete store. 5 seconds by default.
     */
    private Duration findWaitForEmptyQueueTimeout;
    /**
     * Size of the buffer a queued message is read into before it is passed on. A bigger message allocates a buffer
     * of its own, so size this above the usual message.
     */
    private Integer messageByteBufferSize;
    /**
     * Whether the buffers entries are read into are off-heap. True by default.
     */
    private Boolean useDirectByteBuffer;
}
