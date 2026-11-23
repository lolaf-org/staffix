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
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.staffix.api.alerting.AlertLevel;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.ha.replication.EncryptionMode;
import org.lolaf.staffix.ha.replication.ReplicationEncryptionSettings;
import org.lolaf.staffix.ha.replication.testkit.TestingKeyExchanges;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Each node's status names what encrypts its lanes, and a node preferring encryption alerts while a lane is in clear.
 */
@ParameterizedClass
@ValueSource(booleans = {false, true})
class TestEncryptionStatus extends HaPairFixture {

    private static final String CIPHER = "AES/CTR/NoPadding 256 HmacSHA256";

    TestEncryptionStatus(boolean storeWriterThreads) {
        super(storeWriterThreads);
    }

    private Node encrypting(String nodeId, EncryptionMode mode) {
        return tuned(nodeId, settings -> settings.encryption(ReplicationEncryptionSettings.builder().mode(mode)
                .secret(TestingKeyExchanges.SECRET.getBytes(StandardCharsets.UTF_8)).build()));
    }

    @Test
    void anEncryptedPairNamesItsCipherOnBothNodesAndAlertsNothing() {
        a = encrypting("a", EncryptionMode.PREFERRED);
        b = encrypting("b", EncryptionMode.PREFERRED);
        startPairInSync();

        await().untilAsserted(() -> assertThat(a.ha.getStatus().getFollowerConnections()).singleElement()
                .extracting(HaStatus.FollowerConnection::getCipher).isEqualTo(CIPHER));
        assertThat(b.ha.getStatus().getLeader()).extracting(HaStatus.Leader::getCipher).isEqualTo(CIPHER);
        assertThat(HaProbes.encryptionEvaluator(a.ha).evaluate()).isEmpty();
        assertThat(HaProbes.encryptionEvaluator(b.ha).evaluate()).isEmpty();
    }

    @Test
    void aLeaderPreferringEncryptionAlertsWhileItsFollowerHasItDisabled() {
        a = encrypting("a", EncryptionMode.PREFERRED);
        startPairInSync();

        assertThat(a.ha.getStatus().getFollowerConnections()).singleElement().satisfies(follower -> {
            assertThat(follower.isConnected()).isTrue();
            assertThat(follower.getCipher()).as("in clear").isNull();
        });
        assertThat(HaProbes.encryptionEvaluator(a.ha).evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.WARNING);
            assertThat(alert.getMessage())
                    .isEqualTo("Replicating in clear to follower b: this node prefers encryption, the follower has it disabled");
        });
        assertThat(HaProbes.encryptionEvaluator(b.ha).evaluate()).as("encryption disabled").isEmpty();
    }

    @Test
    void aFollowerPreferringEncryptionAlertsWhileItsLeaderHasItDisabled() {
        b = encrypting("b", EncryptionMode.PREFERRED);
        startPairInSync();

        await().until(() -> b.ha.getStatus().getLeader().isConnected());
        assertThat(b.ha.getStatus().getLeader().getCipher()).as("in clear").isNull();
        assertThat(HaProbes.encryptionEvaluator(b.ha).evaluate()).hasValueSatisfying(alert -> {
            assertThat(alert.getLevel()).isEqualTo(AlertLevel.WARNING);
            assertThat(alert.getMessage())
                    .isEqualTo("Following the leader a in clear: this node prefers encryption, the leader has it disabled");
        });
        assertThat(HaProbes.encryptionEvaluator(a.ha).evaluate()).as("encryption disabled").isEmpty();
    }
}
