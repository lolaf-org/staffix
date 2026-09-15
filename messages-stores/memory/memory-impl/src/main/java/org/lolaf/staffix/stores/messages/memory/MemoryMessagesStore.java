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
package org.lolaf.staffix.stores.messages.memory;

import lombok.Getter;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps messages and sequence numbers in memory only - the fastest store, and the one that loses everything on
 * restart.
 */
public class MemoryMessagesStore extends Startable.SimpleStartable<FixMessagesStore> implements FixMessagesStore {

    private final MemoryMessageStoreSettings settings;
    private final Map<FixSessionId, FixSessionMessagesStore> memoryFixSessionStoreContext;
    @Getter
    private final String instanceId;

    protected MemoryMessagesStore(MemoryMessageStoreSettings settings) {
        this.settings = settings;
        this.memoryFixSessionStoreContext = new ConcurrentHashMap<>();
        this.instanceId = settings.getInstanceId();
    }

    @Override
    public FixSessionMessagesStore getStore(FixSessionId fixSessionId) {
        return memoryFixSessionStoreContext.computeIfAbsent(fixSessionId, sid -> new MemoryFixSessionMessagesStore(settings));
    }

    @Override
    protected void startMe() throws StartStopException {
        // nothing to do
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        memoryFixSessionStoreContext.clear();
    }

    public static class MemoryStoreFactoryImpl implements FixMessagesStoreSettings.FixMessagesStoreFactory<MemoryMessageStoreSettings> {

        @Override
        public FixMessagesStore newInstance(MemoryMessageStoreSettings settings) {
            return new MemoryMessagesStore(settings);
        }

        @Override
        public Class<MemoryMessageStoreSettings> getSettingsClass() {
            return MemoryMessageStoreSettings.class;
        }
    }
}