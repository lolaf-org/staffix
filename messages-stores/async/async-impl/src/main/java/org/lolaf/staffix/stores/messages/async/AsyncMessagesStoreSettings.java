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
package org.lolaf.staffix.stores.messages.async;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.stores.core.async.AsyncStoreSettings;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.function.BiPredicate;

/**
 * The store being wrapped, and the queue and threads in front of it.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class AsyncMessagesStoreSettings implements FixMessagesStoreSettings {

    /**
     * Async store base settings
     */
    private AsyncStoreSettings asyncStoreSettings;

    /**
     * Delay for the task to check if a store underlying resource is back up and can resume normal operation
     */
    @Builder.Default
    private Duration underlyingStoreResourceWatchTaskCheckDelay = Duration.ofSeconds(2);
    /**
     * Max duration to flush all pending message on startup that for some reason have not been processed before last shutdown
     */
    @Builder.Default
    private Duration flushPendingMessagesOnStartupDelay = Duration.ofSeconds(60);
    /**
     * Max duration a {@code find} call waits for the async queue to drain (all pending writes applied to the wrapped
     * store) before reading, so a resend sees messages that were stored just before. Set to {@code null} to read
     * immediately without waiting.
     */
    @Builder.Default
    private Duration findWaitForEmptyQueueTimeout = Duration.ofSeconds(5);
    /**
     * Default byte buffer size for reading FIX messages from chronicle and passing them to the wrapped store
     */
    @Builder.Default
    private int messageByteBufferSize = 2048;
    /**
     * Configure to use a heap or direct bytebuffer to transferred FIX messages from the async queue
     */
    @Builder.Default
    private boolean useDirectByteBuffer = true;
    /**
     * Wrapped store settings
     */
    private FixMessagesStoreSettings wrappedFixMessagesStoreSettings;

    @Override
    public String getInstanceId() {
        return wrappedFixMessagesStoreSettings.getInstanceId();
    }

    @Override
    public BiPredicate<MessageType, ByteBuffer> getMessageFilter() {
        return wrappedFixMessagesStoreSettings.getMessageFilter();
    }
}