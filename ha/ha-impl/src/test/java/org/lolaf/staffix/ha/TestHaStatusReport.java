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
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.api.ha.LeaderElection.Role;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ParameterizedClass
@ValueSource(booleans = {false, true})
class TestHaStatusReport extends HaPairFixture {

    TestHaStatusReport(boolean storeWriterThreads) {
        super(storeWriterThreads);
    }

    @Test
    void theStatusTellsWhichMonitoringPluginPublishesTheMeters() {
        assertThat(a.ha.getStatus().getMonitoringInstanceId()).isEqualTo("monitoring");
    }

    @Test
    void theStatusTellsTheLongestASwitchoverCanTake() {
        assertThat(a.ha.getStatus().getSwitchoverMaxDuration()).as("waiting, then the switchover").isEqualTo(Duration.ofSeconds(4));
        assertThat(notWaitingToSwitchOver("a").ha.getStatus().getSwitchoverMaxDuration()).as("the default hand over timeout")
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void theLeaderSeesItsFollowerInSyncAndTheFollowerItsLeader() {
        aLeadsBFollows();

        await().untilAsserted(() -> {
            HaStatus leader = a.ha.getStatus();
            assertThat(leader.getRole()).isEqualTo(Role.LEADER);
            assertThat(leader.getEpoch()).isEqualTo(3);
            assertThat(leader.isLeadershipValid()).isTrue();
            assertThat(leader.getRoleSince()).isNotNull();
            assertThat(leader.getLeader()).isNull();
            assertThat(leader.getFollowerConnections()).singleElement().satisfies(follower -> {
                assertThat(follower.getNodeId()).isEqualTo("b");
                assertThat(follower.isConnected()).isTrue();
                assertThat(follower.isInSync()).isTrue();
                assertThat(follower.isSnapshotInProgress()).isFalse();
                assertThat(follower.getUnsentBytes()).isZero();
                assertThat(follower.getLagMillis()).isZero();
            });
        });
        HaStatus follower = b.ha.getStatus();
        assertThat(follower.getRole()).isEqualTo(Role.FOLLOWER);
        assertThat(follower.isLeadershipValid()).isFalse();
        assertThat(follower.getFollowerConnections()).isEmpty();
        assertThat(follower.getLeader()).isNotNull();
        assertThat(follower.getLeader().getLeaderId()).isEqualTo("a");
        assertThat(follower.getLeader().isConnected()).isTrue();
        assertThat(follower.getLeader().isInSync()).isTrue();
    }

    @Test
    void eachNodeDescribesTheReplicatedSessionFromItsSide() {
        aLeadsBFollows();

        await().untilAsserted(() -> assertThat(a.ha.getSessionStatuses()).singleElement().satisfies(session -> {
            assertThat(session.getFixSessionId()).isEqualTo(MAIN.getFixSessionId());
            assertThat(session.isInSync()).isTrue();
            assertThat(session.getNotReplicatedReason()).isNull();
            assertThat(session.getNextOutgoingSeqNum()).isEqualTo(-1);
        }));
        HaSessionStatus replicated = b.ha.getSessionStatuses().get(0);
        assertThat(replicated.isInSync()).isTrue();
        assertThat(replicated.getNextIncomingSeqNum()).isEqualTo(1);
        assertThat(replicated.getNextOutgoingSeqNum()).isEqualTo(1);
        assertThat(replicated.isHandedOver()).isFalse();
    }

    @Test
    void aNodeWhoseRoleIsUnknownDescribesItsSessionsWithoutReplication() {
        a.start();

        assertThat(a.ha.getStatus().getRole()).isEqualTo(Role.UNKNOWN);
        assertThat(a.ha.getStatus().getRoleSince()).isNull();
        assertThat(a.ha.getSessionStatuses()).singleElement().satisfies(session -> assertThat(session.isInSync()).isFalse());
    }

    @Test
    void thePairTakesItsNameFromTheSettingsAndElseFromTheElection() {
        ReplicationFixEngineHa named = new ReplicationFixEngineHa(ReplicationHaSettings.builder().nodeId("a").pairName("gateway").build());
        assertThat(named.getStatus().getPairName()).isEqualTo("gateway");

        aLeadsBFollows();
        assertThat(a.ha.getStatus().getPairName()).as("an election naming nothing").isNull();
        a.election.pairName = "cluster";
        assertThat(a.ha.getStatus().getPairName()).isEqualTo("cluster");
    }
}
