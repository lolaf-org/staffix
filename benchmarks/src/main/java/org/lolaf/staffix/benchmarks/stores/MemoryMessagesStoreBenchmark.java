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
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessagesStore;
import org.openjdk.jmh.annotations.Param;

/**
 * The memory store, the baseline the stores that persist are measured against.
 */
public class MemoryMessagesStoreBenchmark extends AbstractMessagesStoreBenchmark {

    @Param({"true", "false"})
    boolean useDirectMemory;
    /**
     * As many as the file store holds by default.
     */
    @Param({"10000"})
    int maxEntriesInMemory;

    @Override
    protected FixMessagesStore newStore() {
        return new MemoryMessagesStore.MemoryStoreFactoryImpl().newInstance(MemoryMessageStoreSettings.builder()
                .useDirectMemory(useDirectMemory)
                .maxEntriesInMemory(maxEntriesInMemory)
                .build());
    }
}
