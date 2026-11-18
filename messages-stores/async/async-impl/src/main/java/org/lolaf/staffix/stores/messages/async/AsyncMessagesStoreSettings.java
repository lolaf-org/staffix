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
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.metadata.JvmFlagSet;
import org.lolaf.staffix.api.metadata.RequiresJvmFlags;
import org.lolaf.staffix.api.metadata.Wrapped;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.stores.core.async.AsyncStoreSettings;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.function.BiPredicate;

/**
 * The store being wrapped, and the queue and threads in front of it.
 */
@NullMarked
@RequiresJvmFlags(JvmFlagSet.CHRONICLE)
@Getter
@SuperBuilder(toBuilder = true)
public class AsyncMessagesStoreSettings implements FixMessagesStoreSettings {

    /**
     * The queue in front of the wrapped store: where it lives and how it is read.
     */
    private AsyncStoreSettings asyncStoreSettings;

    /**
     * Delay for the task to check if a store underlying resource is back up and can resume normal operation
     */
    @Builder.Default
    private Duration underlyingStoreResourceWatchTaskCheckDelay = Duration.ofSeconds(2);
    /**
     * Max duration {@code start} waits for the writes a previous run left pending; past it, {@code start} fails rather
     * than read stale sequence numbers. {@code null} does not wait, so any pending write fails the start.
     */
    @Builder.Default
    @Nullable
    private Duration flushPendingMessagesOnStartupDelay = Duration.ofSeconds(120);
    /**
     * Max duration a {@code find} call waits for the writes made before it to reach the wrapped store, so a resend sees
     * messages stored just before; past it, {@code find} throws rather than read an incomplete store.
     * {@code null} reads immediately, but a {@code find} while the wrapped store is down always throws.
     */
    @Builder.Default
    @Nullable
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
     * The store the writes go to; its instance id is this store's.
     */
    @Wrapped
    private FixMessagesStoreSettings wrappedFixMessagesStoreSettings;
    /**
     * See {@link FixMessagesStoreSettings#getResourceCheckInterval()}.
     */
    @Builder.Default
    private Duration resourceCheckInterval = DEFAULT_RESOURCE_CHECK_INTERVAL;
    /**
     * See {@link FixMessagesStoreSettings#getAlertClearDelay()}.
     */
    @Builder.Default
    private Duration alertClearDelay = DEFAULT_ALERT_CLEAR_DELAY;

    @Override
    public String getInstanceId() {
        return wrappedFixMessagesStoreSettings.getInstanceId();
    }

    @Override
    public BiPredicate<MessageType, ByteBuffer> getMessageFilter() {
        return wrappedFixMessagesStoreSettings.getMessageFilter();
    }
}