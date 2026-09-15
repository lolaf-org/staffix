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
package org.lolaf.staffix.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.session.*;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.api.version.SemVer;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.tests.TestingFixMessagesStoreSettings;
import org.lolaf.staffix.tests.TestingFixSessionMessagesStore;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TestFixEngineReloadSessionsSettings {

    private static final String STORE_ID = "test-store";

    private final ControllableStore store = new ControllableStore(STORE_ID);
    private FixEngine fixEngine;

    private static FixSessionSettings session(String sender, String target) {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of("test", FixRegularVersion.VERSION_44, sender, target))
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .desiredSessionState(FixSessionState.LOGGED_OUT)
                .build();
    }

    private AdminApi adminApi() {
        if (fixEngine == null) {
            FixApplication application = mock(FixApplication.class);
            when(application.getFixApiVersion()).thenReturn(FixApiVersion.of("test app", SemVer.of(1, 0, 0), "test vendor"));
            fixEngine = FixEngineBuilder.builder()
                    .fixMessagesStore(TestingFixMessagesStoreSettings.builder()
                            .testingFixSessionMessagesStore(new TestingFixSessionMessagesStore())
                            .build())
                    .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                            .application(InstanceProvider.DEFAULT_INSTANCE_ID, application)
                            .build())
                    .fixSessionsSettingsStore(new ControllableStoreSettings(store))
                    .build()
                    .instance();
            fixEngine.start();
        }
        return (AdminApi) fixEngine;
    }

    @AfterEach
    void shutdown() {
        if (fixEngine != null) {
            fixEngine.stop(Deadline.unlimited());
        }
    }

    @Test
    void reloadAddsSessionsDiscoveredInTheBackingSource() {
        FixSessionsSettingsStore.Listener listener = mock(FixSessionsSettingsStore.Listener.class);
        store.register(listener);

        FixSessionSettings a = session("SENDER_A", "TARGET_A");
        FixSessionSettings b = session("SENDER_B", "TARGET_B");
        store.setSource(a, b);

        adminApi().reloadFixSessionsSettingsStore(STORE_ID);

        assertThat(store.getSettings()).containsExactlyInAnyOrder(a, b);
        verify(listener).onAddedSession(a);
        verify(listener).onAddedSession(b);
        verifyNoMoreInteractions(listener);
    }

    @Test
    void reloadAppliesAddUpdateAndRemoveKeyedBySessionId() {
        FixSessionSettings a = session("SENDER_A", "TARGET_A");
        FixSessionSettings b = session("SENDER_B", "TARGET_B");
        FixSessionSettings d = session("SENDER_D", "TARGET_D");
        // seed the managed settings before registering the listener so it only observes the reload effects
        store.add(a);
        store.add(b);
        store.add(d);

        FixSessionsSettingsStore.Listener listener = mock(FixSessionsSettingsStore.Listener.class);
        store.register(listener);

        // same session id as 'a' but a different value -> update; 'd' unchanged -> no callback; 'b' absent -> removed
        FixSessionSettings aUpdated = a.toBuilder().desiredSessionState(FixSessionState.LOGGED_IN).build();
        FixSessionSettings c = session("SENDER_C", "TARGET_C");
        store.setSource(aUpdated, d, c);

        adminApi().reloadFixSessionsSettingsStore(STORE_ID);

        assertThat(store.getSettings()).containsExactlyInAnyOrder(aUpdated, d, c);
        verify(listener).onUpdatedSession(a, aUpdated);
        verify(listener).onRemovedSession(b);
        verify(listener).onAddedSession(c);
        verifyNoMoreInteractions(listener); // unchanged 'd' triggers nothing
    }

    @Test
    void reloadDoesNothingWhenBackingSourceMatchesManagedSettings() {
        FixSessionSettings a = session("SENDER_A", "TARGET_A");
        store.add(a);

        FixSessionsSettingsStore.Listener listener = mock(FixSessionsSettingsStore.Listener.class);
        store.register(listener);
        store.setSource(a);

        adminApi().reloadFixSessionsSettingsStore(STORE_ID);

        assertThat(store.getSettings()).containsExactly(a);
        verify(listener, never()).onAddedSession(any());
        verify(listener, never()).onRemovedSession(any());
        verify(listener, never()).onUpdatedSession(any(), any());
    }

    @Test
    void reloadThrowsForUnknownStoreInstanceId() {
        assertThatThrownBy(() -> adminApi().reloadFixSessionsSettingsStore("does-not-exist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does-not-exist");
    }

    /**
     * A {@link FixSessionsSettingsStore} whose {@link #load()} result is fully controlled by the test, so the engine's
     * reconciliation against the managed settings can be exercised without a real backing source.
     */
    private static class ControllableStore extends FixSessionsSettingsStore.AbstractFixSessionSettingsStore {

        private final String instanceId;
        private final Set<FixSessionSettings> managed = new HashSet<>();
        private Set<FixSessionSettings> source = new HashSet<>();

        ControllableStore(String instanceId) {
            this.instanceId = instanceId;
        }

        void setSource(FixSessionSettings... settings) {
            this.source = new HashSet<>(Set.of(settings));
        }

        @Override
        public String getInstanceId() {
            return instanceId;
        }

        @Override
        public Collection<FixSessionSettings> getSettings() {
            return managed;
        }

        @Override
        public Optional<FixSessionSettings> find(FixSessionId fixSessionId, FixSession.FixSessionType fixSessionType) {
            return managed.stream()
                    .filter(s -> s.getFixSessionId().equals(fixSessionId) && s.getFixSessionType() == fixSessionType)
                    .findFirst();
        }

        @Override
        public Set<FixSessionSettings> load() {
            return new HashSet<>(source);
        }

        @Override
        public void onAdd(FixSessionSettings settings) {
            managed.add(settings);
        }

        @Override
        public void onRemove(FixSessionSettings settings) {
            managed.remove(settings);
        }

        @Override
        public void onUpdate(FixSessionSettings settings) {
            managed.removeIf(s -> s.getFixSessionId().equals(settings.getFixSessionId()));
            managed.add(settings);
        }

        @Override
        protected void startMe() {
            // nothing to do
        }

        @Override
        protected void stopMe(Deadline stopDeadline) {
            // nothing to do
        }
    }

    private static class ControllableStoreSettings implements FixSessionsSettingsStoreSettings {

        private final ControllableStore store;

        ControllableStoreSettings(ControllableStore store) {
            this.store = store;
        }

        @Override
        public String getInstanceId() {
            return store.getInstanceId();
        }

        @Override
        public FixSessionsSettingsStore instance() {
            return store;
        }
    }
}
