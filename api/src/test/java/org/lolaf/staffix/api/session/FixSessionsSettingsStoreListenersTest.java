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
package org.lolaf.staffix.api.session;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a store owes a listener that reacts to the very notification it is being given.
 *
 * <p>Both connectors do: a {@code FixInitiatorImpl} whose settings are removed stops itself from inside
 * {@code onRemovedSession}, and stopping is what unregisters it. Notifying straight off the live listener set makes
 * that a {@link java.util.ConcurrentModificationException} - not always, which is the trap: {@code HashSet}'s
 * iterator only checks its modification count in {@code next()}, so a listener that removes itself while it happens
 * to be the last one visited gets away with it, and a store with a single listener never sees the bug at all. It
 * takes a second listener behind the first, which is exactly what a store holding both ends of a session has.
 */
class FixSessionsSettingsStoreListenersTest {

    private static FixSessionSettings settings(String sender, String target) {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of("test", FixRegularVersion.VERSION_44, sender, target))
                .fixSessionType(FixSession.FixSessionType.INITIATOR)
                .build();
    }

    @Test
    void aListenerThatUnregistersItselfOnRemoveDoesNotBreakTheNotification() {
        InMemoryStore store = new InMemoryStore();
        FixSessionSettings removed = settings("SENDER", "TARGET");
        store.add(removed);

        RecordingListener selfUnregistering = new RecordingListener(store, true);
        RecordingListener behindIt = new RecordingListener(store, false);
        store.register(selfUnregistering);
        store.register(behindIt);

        store.remove(removed);

        assertThat(selfUnregistering.removedSessions).containsExactly(removed);
        assertThat(behindIt.removedSessions).as("the listener behind the one that unregistered itself").containsExactly(removed);
        assertThat(store.getListeners()).containsExactly(behindIt);
    }

    /**
     * The same for an update, which is the other callback a connector restarts itself from - and a restart both
     * unregisters and registers again while the store is still notifying.
     */
    @Test
    void aListenerThatReRegistersItselfOnUpdateDoesNotBreakTheNotification() {
        InMemoryStore store = new InMemoryStore();
        FixSessionSettings original = settings("SENDER", "TARGET");
        store.add(original);

        RecordingListener restarting = new RecordingListener(store, false) {
            @Override
            public void onUpdatedSession(FixSessionSettings oldSettings, FixSessionSettings newSettings) {
                super.onUpdatedSession(oldSettings, newSettings);
                store.unregister(this);
                store.register(this);
            }
        };
        RecordingListener behindIt = new RecordingListener(store, false);
        store.register(restarting);
        store.register(behindIt);

        store.update(settings("SENDER", "TARGET"));

        assertThat(restarting.updatedSessions).hasSize(1);
        assertThat(behindIt.updatedSessions).as("the listener behind the one that restarted itself").hasSize(1);
        assertThat(store.getListeners()).contains(restarting, behindIt);
    }

    private static class RecordingListener implements FixSessionsSettingsStore.Listener {
        final List<FixSessionSettings> removedSessions = new ArrayList<>();
        final List<FixSessionSettings> updatedSessions = new ArrayList<>();
        private final FixSessionsSettingsStore store;
        private final boolean unregisterOnRemove;

        RecordingListener(FixSessionsSettingsStore store, boolean unregisterOnRemove) {
            this.store = store;
            this.unregisterOnRemove = unregisterOnRemove;
        }

        @Override
        public void onAddedSession(FixSessionSettings settings) {
            // not under test
        }

        @Override
        public void onRemovedSession(FixSessionSettings settings) {
            removedSessions.add(settings);
            if (unregisterOnRemove) {
                store.unregister(this);
            }
        }

        @Override
        public void onUpdatedSession(FixSessionSettings oldSettings, FixSessionSettings newSettings) {
            updatedSessions.add(newSettings);
        }
    }

    private static class InMemoryStore extends FixSessionsSettingsStore.AbstractFixSessionSettingsStore {

        private final List<FixSessionSettings> stored = new ArrayList<>();

        @Override
        public void onAdd(FixSessionSettings settings) {
            stored.add(settings);
        }

        @Override
        public void onRemove(FixSessionSettings settings) {
            stored.remove(settings);
        }

        @Override
        public void onUpdate(FixSessionSettings settings) {
            stored.removeIf(s -> s.getFixSessionId().equals(settings.getFixSessionId()));
            stored.add(settings);
        }

        @Override
        public Collection<FixSessionSettings> getSettings() {
            return List.copyOf(stored);
        }

        @Override
        public Optional<FixSessionSettings> find(FixSessionId fixSessionId, FixSession.FixSessionType fixSessionType) {
            return stored.stream()
                    .filter(s -> s.getFixSessionId().equals(fixSessionId) && s.getFixSessionType() == fixSessionType)
                    .findFirst();
        }

        @Override
        public String getInstanceId() {
            return "test";
        }

        @Override
        public java.util.Set<FixSessionSettings> load() {
            return new java.util.HashSet<>(stored);
        }

        @Override
        protected void startMe() {
            // nothing to start
        }

        @Override
        protected void stopMe(org.lolaf.ringos.Deadline stopDeadline) {
            // nothing to stop
        }
    }
}
