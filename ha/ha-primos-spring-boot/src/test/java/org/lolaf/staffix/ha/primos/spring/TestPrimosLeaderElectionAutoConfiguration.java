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
package org.lolaf.staffix.ha.primos.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lolaf.primos.PrimosNode;
import org.lolaf.primos.spring.PrimosAutoConfiguration;
import org.lolaf.staffix.api.ha.LeaderElectionSettings;
import org.lolaf.staffix.ha.primos.PrimosLeaderElectionSettings;
import org.lolaf.staffix.spring.boot.ManagedFixEngine;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class TestPrimosLeaderElectionAutoConfiguration {

    @TempDir
    Path stateDirectory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PrimosAutoConfiguration.class, PrimosLeaderElectionAutoConfiguration.class));

    private String[] singleNodeCluster() {
        return new String[]{
                "primos.application-name=staffix",
                "primos.node-id=1",
                "primos.bind-port=0",
                "primos.secret=a-test-secret",
                "primos.state-directory=" + stateDirectory,
        };
    }

    @Test
    void electsWithThePrimosStartersNode() {
        runner.withPropertyValues(singleNodeCluster()).withPropertyValues("primos.lifecycle=manual")
                .run(context -> assertThat(context.getBean(PrimosLeaderElectionSettings.class).getNode())
                        .isSameAs(context.getBean(PrimosNode.class)));
    }

    @Test
    void controlsTheApplicationsLeadershipOnlyWhenAllowed() {
        runner.withPropertyValues(singleNodeCluster()).withPropertyValues("primos.lifecycle=manual")
                .run(context -> assertThat(context.getBean(PrimosLeaderElectionSettings.class).isLeadershipControl()).isFalse());
        runner.withPropertyValues(singleNodeCluster()).withPropertyValues("primos.lifecycle=manual",
                        "staffix.ha-primos.leadership-control=true")
                .run(context -> assertThat(context.getBean(PrimosLeaderElectionSettings.class).isLeadershipControl()).isTrue());
    }

    @Test
    void refusesToStartWithoutAPrimosNode() {
        runner.run(context -> assertThat(context).getFailure()
                .rootCause().hasMessageContaining("No PrimosNode bean to elect the leading engine with"));
    }

    @Test
    void backsOffWhenTheApplicationDeclaresItsOwnElection() {
        LeaderElectionSettings own = mock(LeaderElectionSettings.class);
        runner.withBean(LeaderElectionSettings.class, () -> own)
                .run(context -> assertThat(context.getBean(LeaderElectionSettings.class)).isSameAs(own));
    }

    @Test
    void theEngineStopsBeforeThePrimosNode() {
        ManagedFixEngine engine = mock(ManagedFixEngine.class);
        AtomicReference<Boolean> nodeRunningWhenTheEngineStopped = new AtomicReference<>();
        AtomicReference<PrimosNode> node = new AtomicReference<>();
        doAnswer(invocation -> {
            nodeRunningWhenTheEngineStopped.compareAndSet(null, node.get().isRunning());
            return null;
        }).when(engine).close();

        runner.withPropertyValues(singleNodeCluster())
                .withBean(ManagedFixEngine.class, () -> engine)
                .run(context -> {
                    node.set(context.getBean(PrimosNode.class));
                    assertThat(node.get().isRunning()).isTrue();
                });

        assertThat(nodeRunningWhenTheEngineStopped).hasValue(true);
        assertThat(node.get().isRunning()).isFalse();
    }
}
