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

import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.spring.boot.spi.FixMessagesStoreSettingsContributor;
import org.lolaf.staffix.stores.core.async.AsyncStoreSettings;
import org.lolaf.staffix.stores.messages.async.AsyncMessagesStoreSettings;

import java.util.Map;

/**
 * Contributes the asynchronous message store wrapper's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class AsyncMessagesStoreContributor implements FixMessagesStoreSettingsContributor {

    private final AsyncMessagesStoreProps props;

    public AsyncMessagesStoreContributor(AsyncMessagesStoreProps props) {
        this.props = props;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public void contribute(Map<String, FixMessagesStoreSettings> registry) {
        props.getInstances().forEach((key, p) -> {
            if (p.getWraps() == null) {
                throw new IllegalArgumentException("staffix.messages-stores-async.instances." + key + ".wraps is required");
            }
            FixMessagesStoreSettings wrapped = registry.get(p.getWraps());
            if (wrapped == null) {
                throw new IllegalArgumentException("staffix.messages-stores-async.instances." + key + ".wraps='" + p.getWraps()
                        + "' does not reference any store contributed by a configured modules");
            }
            if (p.getAsyncQueueDirectory() == null) {
                throw new IllegalArgumentException("staffix.messages-stores-async.instances." + key + ".async-queue-directory is required");
            }
            AsyncMessagesStoreSettings.AsyncMessagesStoreSettingsBuilder<?, ?> b = AsyncMessagesStoreSettings.builder()
                    .asyncStoreSettings(AsyncStoreSettings.builder().asyncQueueDirectory(p.getAsyncQueueDirectory()).build())
                    .wrappedFixMessagesStoreSettings(wrapped);
            if (p.getUnderlyingStoreResourceWatchTaskCheckDelay() != null)
                b.underlyingStoreResourceWatchTaskCheckDelay(p.getUnderlyingStoreResourceWatchTaskCheckDelay());
            if (p.getFlushPendingMessagesOnStartupDelay() != null)
                b.flushPendingMessagesOnStartupDelay(p.getFlushPendingMessagesOnStartupDelay());
            if (p.getFindWaitForEmptyQueueTimeout() != null)
                b.findWaitForEmptyQueueTimeout(p.getFindWaitForEmptyQueueTimeout());
            if (p.getMessageByteBufferSize() != null) b.messageByteBufferSize(p.getMessageByteBufferSize());
            if (p.getUseDirectByteBuffer() != null) b.useDirectByteBuffer(p.getUseDirectByteBuffer());
            registry.put(p.getWraps(), b.build());
        });
    }
}
