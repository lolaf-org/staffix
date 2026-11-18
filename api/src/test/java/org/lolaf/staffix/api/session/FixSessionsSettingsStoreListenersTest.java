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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void listenersAreNotifiedInTheOrderTheyRegistered() {
        InMemoryStore store = new InMemoryStore();
        FixSessionSettings removed = settings("SENDER", "TARGET");
        store.add(removed);
        List<Integer> notified = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int position = i;
            store.register(new RecordingListener(store, false) {
                @Override
                public void onRemovedSession(FixSessionSettings settings) {
                    notified.add(position);
                }
            });
        }

        store.remove(removed);

        assertThat(notified).isSorted().hasSize(50);
    }

    @Test
    void aListenerRegisteringOnAnotherThreadDoesNotBreakANotification() throws InterruptedException {
        InMemoryStore store = new InMemoryStore();
        FixSessionSettings added = settings("SENDER", "TARGET");
        for (int i = 0; i < 20; i++) {
            store.register(new RecordingListener(store, false));
        }
        AtomicBoolean stop = new AtomicBoolean();
        Thread registering = new Thread(() -> {
            while (!stop.get()) {
                RecordingListener listener = new RecordingListener(store, false);
                store.register(listener);
                store.unregister(listener);
            }
        });
        registering.start();
        try {
            for (int i = 0; i < 20_000; i++) {
                store.add(added);
            }
        } finally {
            stop.set(true);
            registering.join();
        }
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

    @Test
    void bindingTwiceToTheSameEngineIsAccepted() {
        InMemoryStore store = new InMemoryStore();
        store.bind("engine-a");
        store.bind("engine-a");

        assertThat(store.getEngineId()).isEqualTo("engine-a");
    }

    @Test
    void bindingToAnotherEngineIsRefused() {
        InMemoryStore store = new InMemoryStore();
        store.bind("engine-a");

        assertThatThrownBy(() -> store.bind("engine-b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already bound to engine engine-a");
    }

    @Test
    void engineIdOfAnUnboundStoreIsRefused() {
        assertThatThrownBy(() -> new InMemoryStore().getEngineId())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not bound to an engine");
    }

    private static class InMemoryStore extends FixSessionsSettingsStore.AbstractFixSessionSettingsStore {

        @Override
        protected FixSessionSettings onAdd(FixSessionSettings settings) {
            return settings;
        }

        @Override
        protected void onRemove(FixSessionSettings settings) {
            // nothing but the managed settings
        }

        @Override
        protected FixSessionSettings onUpdate(FixSessionSettings settings) {
            return settings;
        }

        @Override
        public String getInstanceId() {
            return "test";
        }

        @Override
        protected java.util.Set<FixSessionSettings> onLoad() {
            return new java.util.HashSet<>(getSettings());
        }

        @Override
        public boolean isPersistent() {
            return false;
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
