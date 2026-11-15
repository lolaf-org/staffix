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
import net.openhft.chronicle.queue.RollCycles;
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
     * Maximum events the wrapped logger gets per call, through its batching API. Zero, the default, hands over one
     * event at a time.
     */
    private Integer eventsBatching;
    /**
     * With batching, how long events wait for a full batch before they are handed over anyway. 100 ms by default.
     */
    private Duration batchingFlushInterval;
    /**
     * Threads reading the queues into the wrapped logger, each queue read by one of them. 1 by default.
     */
    private Integer readerThreadsCount;
    /**
     * Without batching, the most events a reader thread hands over from one queue before moving to the next. 1024 by
     * default.
     */
    private Integer readerThreadMaxEventsProcessingPerStore;
    /**
     * How often each queue starts a new file, for example five-minutely; a file is deleted once it is read past. On
     * tmpfs it bounds the memory a queue holds. Fast-hourly by default.
     */
    private RollCycles rollCycle;
    /**
     * How often a thread touches the pages ahead of each queue's writer, so that a write takes no page fault. Zero
     * turns pretouching off. 1 ms by default.
     */
    private Duration pretouchInterval;
    /**
     * How far ahead of each queue's writer pages are touched, as the time the writer takes to fill them at its recent
     * rate. Raise it when a queue's stop log line reports pages the writer reached first. 40 ms by default.
     */
    private Duration pretouchAheadTime;
    /**
     * The least distance ahead of each queue's writer, in bytes, which covers a burst after an idle spell. 1 MB by
     * default.
     */
    private Long pretouchMinAheadBytes;
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
    /**
     * How often a logger whose resource is down is checked for its return, its calls being skipped meanwhile. Zero
     * disables the check, so every call reaches the resource.
     */
    private Duration resourceCheckInterval;
    /**
     * How long logger calls must keep succeeding before the session's messages-logger alert clears.
     */
    private Duration alertClearDelay;
}
