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
package org.lolaf.staffix.impl.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestFixSessionRegistryImpl {

    private FixSessionRegistryImpl registry;

    private static FixSession mockSession(FixSessionId id) {
        FixSession session = mock(FixSession.class);
        when(session.getFixSessionId()).thenReturn(id);
        return session;
    }

    @BeforeEach
    void setUp() {
        registry = new FixSessionRegistryImpl();
    }

    @Test
    void findById_returnsEmpty_whenNoSessions() {
        FixSessionId id = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        assertThat(registry.find(id)).isEmpty();
    }

    @Test
    void findById_returnsSession_afterRegister() {
        FixSessionId id = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        FixSession session = mockSession(id);

        registry.register(session);

        assertThat(registry.find(id)).contains(session);
    }

    @Test
    void findById_returnsEmpty_afterUnregister() {
        FixSessionId id = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        FixSession s = mockSession(id);
        registry.register(s);
        assertThat(registry.find(id)).isPresent();

        registry.unregister(s);
        assertThat(registry.find(id)).isEmpty();
    }

    @Test
    void unregisterBySession_removesThatSession() {
        FixSessionId id = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        FixSession session = mockSession(id);
        registry.register(session);

        registry.unregister(session);

        assertThat(registry.find(id)).isEmpty();
    }

    /**
     * A stale unregister must not evict the session that has since taken the id over.
     * <p>
     * Reachable whenever an engine stops and another starts on the same {@link FixSessionId}: a second unregister for
     * the session already gone - a double stop, or {@code FixAcceptorImpl.stopMe} running over sessions the settings
     * listener has already dropped - would remove the newcomer if entries were removed by key alone, leaving a running
     * session unreachable through the registry.
     */
    @Test
    void unregisterBySession_leavesTheSessionThatTookTheIdOver() {
        FixSessionId id = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        FixSession stopped = mockSession(id);
        FixSession tookOver = mockSession(id);
        registry.register(stopped);
        registry.unregister(stopped);
        registry.register(tookOver);

        registry.unregister(stopped);

        assertThat(registry.find(id)).contains(tookOver);
        assertThat(registry.findAll(anyId -> true)).containsExactly(tookOver);
    }

    @Test
    void findByPredicate_returnsFirstMatch() {
        FixSessionId id1 = FixSessionId.of("test", FixRegularVersion.VERSION_44, "S1", "T1");
        FixSessionId id2 = FixSessionId.of("test", FixRegularVersion.VERSION_44, "S2", "T2");
        FixSession session1 = mockSession(id1);
        FixSession session2 = mockSession(id2);

        registry.register(session1);
        registry.register(session2);

        Optional<FixSession> result = registry.find(id -> id.getSenderCompID().getValue().equals("S2"));

        assertThat(result).contains(session2);
    }

    @Test
    void findByPredicate_returnsEmpty_whenNoMatch() {
        FixSessionId id = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        registry.register(mockSession(id));

        assertThat(registry.find(id2 -> false)).isEmpty();
    }

    @Test
    void findAll_returnsAllMatching() {
        FixSessionId id1 = FixSessionId.of("test", FixRegularVersion.VERSION_44, "S1", "T1");
        FixSessionId id2 = FixSessionId.of("test", FixRegularVersion.VERSION_44, "S2", "T2");
        FixSessionId id3 = FixSessionId.of("test", FixRegularVersion.VERSION_44, "S3", "T3");
        FixSession session1 = mockSession(id1);
        FixSession session2 = mockSession(id2);
        FixSession session3 = mockSession(id3);

        registry.register(session1);
        registry.register(session2);
        registry.register(session3);

        List<FixSession> result = registry.findAll(id -> !id.getSenderCompID().getValue().equals("S2"));

        assertThat(result).containsExactlyInAnyOrder(session1, session3);
    }

    @Test
    void findAll_returnsEmpty_whenNoMatch() {
        FixSessionId id = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        registry.register(mockSession(id));

        assertThat(registry.findAll(id2 -> false)).isEmpty();
    }

    /**
     * Two live sessions cannot share a {@link FixSessionId}: the second registration is a configuration error and is
     * refused, rather than silently displacing the session already there and leaving it unreachable.
     */
    @Test
    void register_rejectsASecondSessionWithTheSameId() {
        FixSessionId id = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        FixSession alreadyRegistered = mockSession(id);
        FixSession duplicate = mockSession(id);
        registry.register(alreadyRegistered);

        assertThatThrownBy(() -> registry.register(duplicate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(id.toString());

        assertThat(registry.find(id)).contains(alreadyRegistered);
        assertThat(registry.findAll(anyId -> true)).containsExactly(alreadyRegistered);
    }

    /**
     * Registering again after the first session has gone is not a duplicate, and must be allowed: that is an engine
     * restarting, or a second engine taking over an id the first has released.
     */
    @Test
    void register_acceptsTheSameIdAgainAfterUnregister() {
        FixSessionId id = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        FixSession first = mockSession(id);
        FixSession second = mockSession(id);
        registry.register(first);
        registry.unregister(first);

        registry.register(second);

        assertThat(registry.find(id)).contains(second);
    }
}
