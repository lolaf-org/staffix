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

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.stores.core.async.AsyncEventInstanceProvider;
import org.lolaf.staffix.stores.core.async.AsyncStoreThreads;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps any message store so the write leaves the FIX thread: the message goes onto a persistent queue and a
 * consumer performs the real write.
 *
 * <p>Store latency stops reaching the message path, at the cost of a queue that has to drain before a shutdown
 * can be called complete.
 */
public class AsyncMessagesStore extends Startable.SimpleStartable<FixMessagesStore> implements FixMessagesStore {

    private final AsyncMessagesStoreSettings asyncMessagesStoreSettings;
    private final Map<FixSessionId, FixSessionMessagesStore> contexts;
    private final FixMessagesStore wrappedFixMessagesStore;
    private final AsyncStoreThreads<AsyncStoreEvent> asyncStoreThreads;
    private final AsyncEventInstanceProvider<AsyncStoreEvent> asyncEventInstanceProvider;

    protected AsyncMessagesStore(AsyncMessagesStoreSettings asyncMessagesStoreSettings) {
        this.asyncMessagesStoreSettings = asyncMessagesStoreSettings;
        this.contexts = new ConcurrentHashMap<>();
        this.wrappedFixMessagesStore = asyncMessagesStoreSettings.getWrappedFixMessagesStoreSettings().instance();
        boolean directByteBuffer = asyncMessagesStoreSettings.isUseDirectByteBuffer();
        int messageBufferSize = asyncMessagesStoreSettings.getMessageByteBufferSize();
        this.asyncEventInstanceProvider = releaseCallback -> new AsyncStoreEvent(
                directByteBuffer ? ByteBuffer.allocateDirect(messageBufferSize) : ByteBuffer.allocate(messageBufferSize), releaseCallback);
        this.asyncStoreThreads = new AsyncStoreThreads<>(asyncMessagesStoreSettings.getAsyncStoreSettings(), asyncMessagesStoreSettings.getInstanceId(), asyncEventInstanceProvider);
    }

    @Override
    public FixSessionMessagesStore getStore(FixSessionId fixSessionId) {
        return contexts.computeIfAbsent(fixSessionId, sid -> new AsyncMessageStore(wrappedFixMessagesStore.getStore(fixSessionId),
                fixSessionId, asyncMessagesStoreSettings, asyncStoreThreads, asyncEventInstanceProvider));
    }

    @Override
    protected void startMe() throws StartStopException {
        asyncStoreThreads.start();
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        asyncStoreThreads.stop(stopDeadline);
        contexts.clear();
    }

    @Override
    public String getInstanceId() {
        return asyncMessagesStoreSettings.getInstanceId();
    }

    public static class AsyncMessagesStoreFactoryImpl implements FixMessagesStoreSettings.FixMessagesStoreFactory<AsyncMessagesStoreSettings> {

        @Override
        public AsyncMessagesStore newInstance(AsyncMessagesStoreSettings settings) {
            return new AsyncMessagesStore(settings);
        }

        @Override
        public Class<AsyncMessagesStoreSettings> getSettingsClass() {
            return AsyncMessagesStoreSettings.class;
        }
    }
}