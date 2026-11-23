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
package org.lolaf.staffix.ha;

import com.github.valfirst.slf4jtest.LoggingEvent;
import com.github.valfirst.slf4jtest.TestLogger;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.ha.FixEngineHa.EngineAccess;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;
import org.lolaf.staffix.ha.SessionSettingsDigests.Digest;
import org.lolaf.staffix.ha.SessionSettingsDigests.StoredSession;
import org.mockito.ArgumentCaptor;
import org.slf4j.event.Level;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSessionSettingsApplier {

    private static final String STORE = "files";
    private static final String LEADER_STORAGE = "file:leader";

    private final EngineAccess engine;
    private final SessionSettingsApplier applier;
    private final TestLogger applierLog;

    TestSessionSettingsApplier() {
        this.engine = mock(EngineAccess.class);
        this.applier = new SessionSettingsApplier(engine);
        this.applierLog = TestLoggerFactory.getTestLogger(SessionSettingsApplier.class);
    }

    @AfterEach
    void clearLog() {
        applierLog.clear();
    }

    private static FixSessionsSettingsStore store(String storageIdentity, FixSessionSettings... sessions) {
        FixSessionsSettingsStore store = mock(FixSessionsSettingsStore.class);
        when(store.getInstanceId()).thenReturn(STORE);
        when(store.getStorageIdentity()).thenReturn(Optional.ofNullable(storageIdentity));
        when(store.getSettings()).thenReturn(List.of(sessions));
        return store;
    }

    private static List<Digest> leaders(FixSessionSettings... sessions) {
        return SessionSettingsDigests.of(List.of(store(LEADER_STORAGE, sessions)), store -> true);
    }

    private static FixSessionSettings session(String name) {
        return RecordingEngine.session(name, FixSessionType.ACCEPTOR);
    }

    private void storesHere(FixSessionsSettingsStore... stores) {
        when(engine.getFixSessionsSettingsStores()).thenReturn(List.of(stores));
    }

    private List<String> errors() {
        return applierLog.getLoggingEvents().stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(LoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    @Test
    void aSessionWhoseDigestDiffersIsWrittenWithTheLeadersDocument() {
        storesHere(store("file:here", session("same")));
        FixSessionSettings changed = session("changed");

        applier.apply(leaders(session("same"), changed));

        ArgumentCaptor<FixSessionSettings> applied = ArgumentCaptor.forClass(FixSessionSettings.class);
        verify(engine).applySessionSettings(eq(STORE), applied.capture());
        assertThat(applied.getValue().getFixSessionId()).isEqualTo(changed.getFixSessionId());
        assertThat(applied.getValue().getFixSessionType()).isEqualTo(FixSessionType.ACCEPTOR);
    }

    @Test
    void aSessionTheLeaderRemovedIsRemovedHere() {
        FixSessionSettings gone = session("gone");
        FixSessionsSettingsStore here = store("file:here", gone, session("kept"));
        storesHere(here);

        applier.apply(List.of(Digest.removal(new StoredSession(STORE, TypedSessionId.of(gone)), LEADER_STORAGE)));

        verify(here).remove(gone);
        verify(engine, never()).applySessionSettings(anyString(), any());
    }

    @Test
    void aStoreSharedWithTheLeaderIsReloadedRatherThanWritten() {
        storesHere(store(LEADER_STORAGE));

        applier.apply(leaders(session("a")));

        verify(engine).reloadSessionSettings(STORE);
        verify(engine, never()).applySessionSettings(anyString(), any());
    }

    @Test
    void aReloadThatFailsIsLogged() {
        storesHere(store(LEADER_STORAGE));
        doThrow(new IllegalStateException("down")).when(engine).reloadSessionSettings(STORE);

        applier.apply(leaders(session("a")));

        assertThat(errors()).containsExactly("Cannot reload session settings store files, shared with the leader");
    }

    @Test
    void aStoreThisNodeLacksOrCannotIdentifyIsLeftAlone() {
        applier.apply(leaders(session("a")));
        storesHere(store(null));
        applier.apply(leaders(session("a")));

        verify(engine, never()).applySessionSettings(anyString(), any());
        verify(engine, never()).reloadSessionSettings(anyString());
    }

    @Test
    void aDigestWithoutADocumentIsOnlyCompared() {
        storesHere(store("file:here"));

        applier.apply(List.of(leaders(session("a")).get(0).withoutDocument()));

        verify(engine, never()).applySessionSettings(anyString(), any());
    }

    @Test
    void aDocumentThatCannotBeReadIsLoggedAndTheOthersStillApplied() {
        storesHere(store("file:here"));
        Digest good = leaders(session("good")).get(0);
        Digest unreadable = new Digest(STORE, FixSessionType.ACCEPTOR, "unreadable", "abc", LEADER_STORAGE, "{not json",
                false);

        applier.apply(List.of(unreadable, good));

        verify(engine).applySessionSettings(eq(STORE), any());
        assertThat(errors()).containsExactly("Cannot apply the leader's settings of FIX session unreadable to store files");
    }

    @Test
    void aRemovalThatFailsIsLogged() {
        FixSessionSettings gone = session("gone");
        FixSessionsSettingsStore here = store("file:here", gone);
        doThrow(new IllegalStateException("read only")).when(here).remove(gone);
        storesHere(here);

        applier.apply(List.of(Digest.removal(new StoredSession(STORE, TypedSessionId.of(gone)), LEADER_STORAGE)));

        assertThat(errors()).singleElement().asString().startsWith("Cannot remove FIX session");
    }
}
