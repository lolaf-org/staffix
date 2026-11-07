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
package org.lolaf.staffix.ha.follower;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.ha.wire.SessionAnswer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestReplicaSession {

    private static long pendingWritesOf(ReplicaSession session) {
        HaSessionStatus.HaSessionStatusBuilder status = HaSessionStatus.builder();
        session.describe(status);
        return status.build().getPendingWrites();
    }

    @Test
    void aReplicatedSessionReportsTheWritesItsStoreHasPending() {
        FixSessionMessagesStore store = mock(FixSessionMessagesStore.class);
        when(store.getPendingWrites()).thenReturn(7L);

        assertThat(pendingWritesOf(ReplicaSession.replicated(FixSessionType.ACCEPTOR, "alpha", store))).isEqualTo(7);
    }

    @Test
    void aReplicatedSessionReportsTheWritesItsStoreLost() {
        FixSessionMessagesStore store = mock(FixSessionMessagesStore.class);
        when(store.getLostWrites()).thenReturn(4L);
        HaSessionStatus.HaSessionStatusBuilder status = HaSessionStatus.builder();

        ReplicaSession.replicated(FixSessionType.ACCEPTOR, "alpha", store).describe(status);

        assertThat(status.build().getLostWrites()).isEqualTo(4);
    }

    @Test
    void aSessionNotReplicatedHasNoPendingWrites() {
        ReplicaSession session = ReplicaSession.notReplicated(FixSessionType.ACCEPTOR, "alpha", SessionAnswer.NOT_REPLICATED);

        assertThat(pendingWritesOf(session)).isZero();
    }
}
