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
package org.lolaf.staffix.ha.replication.spi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TestReplicaSinksBinding {

    @Test
    void aReplicatedStreamNeedsASnapshotFirst() {
        ReplicaSink sink = mock(ReplicaSink.class);

        ReplicaSinks.Binding binding = ReplicaSinks.Binding.replicated(sink);

        assertThat(binding.getSink()).isSameAs(sink);
        assertThat(binding.getAnswer()).isEqualTo(StreamAnswer.SNAPSHOT_NEEDED);
    }

    @Test
    void aRefusalHasNoSink() {
        ReplicaSinks.Binding binding = ReplicaSinks.Binding.refused(StreamAnswer.MODE_MISMATCH);

        assertThat(binding.getSink()).isNull();
        assertThat(binding.getAnswer()).isEqualTo(StreamAnswer.MODE_MISMATCH);
    }

    @Test
    void anAnswerThatIsNoRefusalIsRejected() {
        assertThatThrownBy(() -> ReplicaSinks.Binding.refused(StreamAnswer.REPLICATING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLICATING is not a refusal");
    }
}
