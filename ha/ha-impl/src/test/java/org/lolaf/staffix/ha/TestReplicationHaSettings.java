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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReplicationHaSettings {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 6})
    void sendBuffersMustBeAPowerOfTwoOfAtLeastTwo(int sendBuffers) {
        ReplicationHaSettings settings = ReplicationHaSettings.builder().sendBuffers(sendBuffers).build();

        assertThatThrownBy(settings::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sendBuffers");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S"})
    void theLogFullTimeoutMustBePositive(String logFullTimeout) {
        ReplicationHaSettings settings = ReplicationHaSettings.builder().logFullTimeout(Duration.parse(logFullTimeout))
                .build();

        assertThatThrownBy(settings::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logFullTimeout");
    }

    @Test
    void theSnapshotChunkBytesMustBePositive() {
        ReplicationHaSettings settings = ReplicationHaSettings.builder().snapshotChunkBytes(0).build();

        assertThatThrownBy(settings::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshotChunkBytes");
    }

    @Test
    void followerStoreWriterThreadsMustNotBeNegative() {
        ReplicationHaSettings settings = ReplicationHaSettings.builder().followerStoreWriterThreads(-1).build();

        assertThatThrownBy(settings::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("followerStoreWriterThreads");
    }

    @Test
    void theSlowFollowerHoldOffMustNotBeNegative() {
        ReplicationHaSettings settings = ReplicationHaSettings.builder().slowFollowerHoldOff(Duration.ofSeconds(-1)).build();

        assertThatThrownBy(settings::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("slowFollowerHoldOff");
    }

    @Test
    void timeoutsMustBeWhatASwitchoverCanRunOn() {
        assertThatThrownBy(() -> ReplicationHaSettings.builder().switchoverTimeout(Duration.ofSeconds(-1)).build().validate())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("switchoverTimeout");
        assertThatThrownBy(() -> ReplicationHaSettings.builder().handOverTimeout(Duration.ZERO).build().validate())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("handOverTimeout");
    }

    @Test
    void switchoverMaxPendingWritesMustNotBeNegative() {
        ReplicationHaSettings settings = ReplicationHaSettings.builder().switchoverMaxPendingWrites(-1).build();

        assertThatThrownBy(settings::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("switchoverMaxPendingWrites");
    }
}
