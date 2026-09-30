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
     * Instance id of the logger to wrap, from any logger module, for example an entry under
     * staffix.messages-loggers-slf4j.instances. The wrapper takes that logger's place under the same instance id,
     * so sessions naming it go through the wrapper.
     */
    private String wraps;
    /**
     * Directory holding the on-disk queues that buffer each session's writes.
     */
    private String asyncQueueDirectory;
    /**
     * How often the wrapped logger is checked after it has failed, to see whether it is back.
     */
    private Duration underlyingLoggerResourceWatchTaskCheckDelay;
    /**
     * How long a session start waits for entries left in the queue by the previous run to be written through. The
     * session starts either way. 60 seconds by default.
     */
    private Duration flushPendingMessagesOnStartupDelay;
    /**
     * Size of the buffer a queued entry is read into before it is passed on. A bigger entry allocates a buffer of its
     * own, so size this above the usual message.
     */
    private Integer logByteBufferSize;
    /**
     * Whether the buffers entries are read into are off-heap. True by default.
     */
    private Boolean useDirectByteBuffer;
}
