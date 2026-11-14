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
package org.lolaf.staffix.benchmarks.stores;

import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.stores.core.async.AsyncStoreSettings;
import org.lolaf.staffix.stores.messages.async.AsyncMessagesStore;
import org.lolaf.staffix.stores.messages.async.AsyncMessagesStoreSettings;
import org.openjdk.jmh.annotations.Param;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The async store in front of a store dropping every write, so that only the queue is measured, not the store behind
 * it.
 */
public class AsyncMessagesStoreBenchmark extends AbstractMessagesStoreBenchmark {

    @Param({"SHM", "DISK"})
    FileSystem queueFileSystem;
    /**
     * 0 hands the wrapped store one message at a time.
     */
    @Param({"0", "100"})
    int eventsBatching;

    @Override
    protected FixMessagesStore newStore() {
        return new AsyncMessagesStore.AsyncMessagesStoreFactoryImpl().newInstance(AsyncMessagesStoreSettings.builder()
                .asyncStoreSettings(AsyncStoreSettings.builder()
                        .asyncQueueDirectory(queueDirectory().toString())
                        .eventsBatching(eventsBatching)
                        .build())
                .wrappedFixMessagesStoreSettings(NoOpMessagesStore.Settings.INSTANCE)
                .build());
    }

    @Override
    protected void cleanUp() throws IOException {
        deleteDirectory(queueDirectory());
    }

    private Path queueDirectory() {
        return queueFileSystem.directory("async-queue");
    }
}
