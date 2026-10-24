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

import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.FixEngineHa;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.tests.TestingHaSettings;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsSecondArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestFixEngineHighAvailability extends AbstractFixTests {

    private final FixEngineHa ha = mock(FixEngineHa.class);

    private static FixSessionSettings.HaSessionSettings replicated(ReplicationMode mode) {
        return FixSessionSettings.HaSessionSettings.builder().replicationMode(mode).build();
    }

    private void withHighAvailability() {
        when(ha.replicatingStore(any(), any())).then(returnsSecondArg());
        initiatorFixEngineBuilder = initiatorFixEngineBuilder.toBuilder()
                .highAvailability(TestingHaSettings.builder().mock(ha).build())
                .build();
    }

    @Test
    void aReplicatedSessionIsRefusedOnAnEngineWithoutHighAvailability() {
        assertThatThrownBy(() -> setupInitiatorSessionSettings(b -> b.haSessionSettings(replicated(ReplicationMode.ASYNC)).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("asks for replication mode ASYNC, but engine default has no high availability configured");
    }

    @Test
    void aReplicatedSessionWritesThroughTheReplicatingStore() {
        withHighAvailability();

        setupInitiatorSessionSettings(b -> b.haSessionSettings(replicated(ReplicationMode.SYNC)).build());
        logonClient();

        verify(ha).start();
        verify(ha).replicatingStore(argThat(settings -> settings.getHaSessionSettings().getReplicationMode() == ReplicationMode.SYNC),
                any());
    }

    @Test
    void anUnreplicatedSessionKeepsItsStoreUnwrapped() {
        withHighAvailability();

        setupInitiatorSessionSettings(FixSessionSettings.FixSessionSettingsBuilder::build);
        logonClient();

        verify(ha, never()).replicatingStore(any(), any());
    }

    @Test
    void theHighAvailabilityStopsWithTheEngine() {
        withHighAvailability();
        setupInitiatorSessionSettings(FixSessionSettings.FixSessionSettingsBuilder::build);

        initiatorFixEngine.stop(Deadline.unlimited());

        verify(ha).stop(any());
    }
}
