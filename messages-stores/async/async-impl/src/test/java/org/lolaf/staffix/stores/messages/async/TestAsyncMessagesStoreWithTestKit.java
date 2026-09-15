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
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.stores.core.async.AsyncEventInstanceProvider;
import org.lolaf.staffix.stores.core.async.AsyncStoreSettings;
import org.lolaf.staffix.stores.core.async.AsyncStoreThreads;
import org.lolaf.staffix.stores.core.async.StoreUnderlyingResourceStateListener;
import org.lolaf.staffix.stores.messages.testkit.AbstractFixSessionMessagesStoreTest;
import org.lolaf.staffix.tests.TestingFixMessagesStoreSettings;
import org.lolaf.staffix.tests.TestingFixSessionMessagesStore;

import java.nio.ByteBuffer;
import java.util.function.BiPredicate;

import static org.mockito.Mockito.mock;

class TestAsyncMessagesStoreWithTestKit extends AbstractFixSessionMessagesStoreTest<AsyncMessageStore> {
    AsyncEventInstanceProvider<AsyncStoreEvent> asyncEventInstanceProvider;
    AsyncMessagesStoreSettings asyncMessagesStoreSettings;
    AsyncStoreThreads<AsyncStoreEvent> asyncStoreThreads;
    TestingFixSessionMessagesStore testingFixSessionMessagesStore;

    @Override
    protected void beforeSetUp() throws Exception {
        StoreUnderlyingResourceStateListener storeUnderlyingResourceStateListener = mock(StoreUnderlyingResourceStateListener.class);
        asyncMessagesStoreSettings = AsyncMessagesStoreSettings.builder()
                .asyncStoreSettings(AsyncStoreSettings.builder()
                        .asyncQueueDirectory("./target/" + System.currentTimeMillis())
                        .storeUnderlyingResourceStateListener(storeUnderlyingResourceStateListener)
                        .build())
                .wrappedFixMessagesStoreSettings(TestingFixMessagesStoreSettings.builder()
                        .build())
                .build();

        asyncEventInstanceProvider = releaseCallback -> new AsyncStoreEvent(ByteBuffer.allocate(1024), releaseCallback);
        asyncStoreThreads = new AsyncStoreThreads<>(asyncMessagesStoreSettings.getAsyncStoreSettings(), asyncMessagesStoreSettings.getInstanceId(), asyncEventInstanceProvider);
        asyncStoreThreads.start();
        super.beforeSetUp();
    }

    @Override
    protected void afterTearDown() throws Exception {
        asyncStoreThreads.stop(Deadline.unlimited());
        super.afterTearDown();
    }

    @Override
    protected AsyncMessageStore createSessionStore(FixSessionId sessionId, BiPredicate<MessageType, ByteBuffer> messagesFilter) {
        if (testingFixSessionMessagesStore == null) {
            testingFixSessionMessagesStore = new TestingFixSessionMessagesStore(messagesFilter);
        }
        return new AsyncMessageStore(testingFixSessionMessagesStore, sessionId, asyncMessagesStoreSettings, asyncStoreThreads, asyncEventInstanceProvider);
    }
}