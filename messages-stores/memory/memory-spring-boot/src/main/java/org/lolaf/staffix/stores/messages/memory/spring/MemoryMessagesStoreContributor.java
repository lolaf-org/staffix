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
package org.lolaf.staffix.stores.messages.memory.spring;

import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.spring.boot.spi.FixMessagesStoreSettingsContributor;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;

import java.util.Map;

/**
 * Contributes the in-memory message store's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class MemoryMessagesStoreContributor implements FixMessagesStoreSettingsContributor {

    private final MemoryMessagesStoreProps props;

    public MemoryMessagesStoreContributor(MemoryMessagesStoreProps props) {
        this.props = props;
    }

    private static FixMessagesStoreSettings build(String mapKey, MemoryStoreEntryProps p) {
        MemoryMessageStoreSettings.MemoryMessageStoreSettingsBuilder b = MemoryMessageStoreSettings.builder()
                .instanceId(mapKey);
        if (p.getMaxEntriesInMemory() != null) b.maxEntriesInMemory(p.getMaxEntriesInMemory());
        if (p.getUseDirectMemory() != null) b.useDirectMemory(p.getUseDirectMemory());
        return b.build();
    }

    @Override
    public void contribute(Map<String, FixMessagesStoreSettings> registry) {
        props.getInstances().forEach((key, p) -> {
            if (registry.putIfAbsent(key, build(key, p)) != null) {
                throw new IllegalStateException("staffix.messages-stores-memory.instances." + key
                        + " collides with another store contributor for the same key");
            }
        });
    }
}
