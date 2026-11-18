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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.ha.wire.AllocatedBytes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestReplicatedSessions {

    private static final FixSessionId ID = FixSessionId.of("main", FixRegularVersion.VERSION_44, "SENDER", "TARGET");

    private final ReplicatedSessions sessions = new ReplicatedSessions();

    private ReplicatedSession register(FixSessionType fixSessionType) {
        return sessions.register(FixSessionSettings.builder()
                .fixSessionId(ID)
                .fixSessionType(fixSessionType)
                .highAvailability(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.SYNC_APPLIED).build())
                .build(), mock(FixSessionMessagesStore.class));
    }

    @Test
    void anIdConfiguredOnBothSidesIsTwoSessions() {
        ReplicatedSession initiator = register(FixSessionType.INITIATOR);
        ReplicatedSession acceptor = register(FixSessionType.ACCEPTOR);

        assertThat(initiator).isNotSameAs(acceptor);
        assertThat(sessions.find(ID, FixSessionType.INITIATOR)).isSameAs(initiator);
        assertThat(sessions.find(ID, FixSessionType.ACCEPTOR)).isSameAs(acceptor);
    }

    @Test
    void registeringAgainKeepsTheSession() {
        ReplicatedSession first = register(FixSessionType.INITIATOR);

        assertThat(register(FixSessionType.INITIATOR)).isSameAs(first);
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    void aSessionNotBuiltIsNotFound() {
        register(FixSessionType.INITIATOR);

        assertThat(sessions.find(ID, FixSessionType.ACCEPTOR)).isNull();
    }

    @Test
    void findingAllocatesNothing() {
        register(FixSessionType.INITIATOR);
        sessions.find(ID, FixSessionType.INITIATOR);

        long before = AllocatedBytes.ofCurrentThread();
        for (int i = 0; i < 100_000; i++) {
            sessions.find(ID, FixSessionType.INITIATOR);
        }

        assertThat(AllocatedBytes.ofCurrentThread() - before).as("less than a byte per find").isLessThan(100_000);
    }
}
