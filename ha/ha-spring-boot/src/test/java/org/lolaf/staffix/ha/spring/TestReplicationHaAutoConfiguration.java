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
package org.lolaf.staffix.ha.spring;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.ha.HaSettings;
import org.lolaf.staffix.api.ha.LeaderElectionSettings;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TestReplicationHaAutoConfiguration {

    private final LeaderElectionSettings election = mock(LeaderElectionSettings.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ReplicationHaAutoConfiguration.class));

    @Test
    void bindsTheSettingsWithTheContextsLeaderElection() {
        runner.withBean(LeaderElectionSettings.class, () -> election)
                .withPropertyValues("staffix.ha-replication.node-id=1",
                        "staffix.ha-replication.peers.1=localhost:17101",
                        "staffix.ha-replication.peers.2=127.0.0.1:17102",
                        "staffix.ha-replication.log-capacity=1048576",
                        "staffix.ha-replication.hand-over-timeout=2s",
                        "staffix.ha-replication.monitoring-instance-id=metrics",
                        "staffix.ha-replication.alerts-publisher-instance-id=alerts")
                .run(context -> {
                    ReplicationHaSettings settings = contribute(context.getBean(ReplicationHaContributor.class));

                    assertThat(settings.getLeaderElection()).isSameAs(election);
                    assertThat(settings.getNodeId()).isEqualTo("1");
                    assertThat(settings.getPeers()).containsOnlyKeys("1", "2");
                    assertThat(settings.getPeers().get("2")).isEqualTo(new InetSocketAddress("127.0.0.1", 17102));
                    assertThat(settings.getLogCapacity()).isEqualTo(1 << 20);
                    assertThat(settings.getHandOverTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(settings.getMonitoringInstanceId()).isEqualTo("metrics");
                    assertThat(settings.getAlertsPublisherInstanceId()).isEqualTo("alerts");
                    assertThat(settings.getSnapshotMaxMessages()).isEqualTo(ReplicationHaSettings.builder().build().getSnapshotMaxMessages());
                });
    }

    @Test
    void refusesToStartWithoutALeaderElection() {
        runner.withPropertyValues("staffix.ha-replication.node-id=1")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("needs a LeaderElectionSettings bean"));
    }

    @Test
    void refusesAPeerThatIsNotHostAndPort() {
        runner.withBean(LeaderElectionSettings.class, () -> election)
                .withPropertyValues("staffix.ha-replication.node-id=1", "staffix.ha-replication.peers.2=localhost")
                .run(context -> assertThatThrownBy(() -> contribute(context.getBean(ReplicationHaContributor.class)))
                        .hasMessageContaining("staffix.ha-replication.peers.2='localhost' is not host:port"));
    }

    @Test
    void refusesAMissingNodeId() {
        runner.withBean(LeaderElectionSettings.class, () -> election)
                .run(context -> assertThatThrownBy(() -> contribute(context.getBean(ReplicationHaContributor.class)))
                        .hasMessageContaining("staffix.ha-replication.node-id is required"));
    }

    private static ReplicationHaSettings contribute(ReplicationHaContributor contributor) {
        List<HaSettings> contributed = new ArrayList<>();
        contributor.contribute(contributed::add);
        assertThat(contributed).hasSize(1);
        return (ReplicationHaSettings) contributed.get(0);
    }
}
