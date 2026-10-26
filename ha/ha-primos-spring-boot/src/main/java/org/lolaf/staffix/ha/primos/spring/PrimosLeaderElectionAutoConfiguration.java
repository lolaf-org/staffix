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

import org.lolaf.primos.PrimosNode;
import org.lolaf.primos.spring.PrimosAutoConfiguration;
import org.lolaf.primos.spring.PrimosNodeLifecycle;
import org.lolaf.staffix.api.ha.LeaderElectionSettings;
import org.lolaf.staffix.ha.primos.PrimosLeaderElectionSettings;
import org.lolaf.staffix.ha.spring.ReplicationHaProps;
import org.lolaf.staffix.spring.boot.ManagedFixEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

/**
 * Elects the engine with the node the primos starter runs, so the application's leader-only work and the FIX
 * sessions follow one election. staffix only listens to that node.
 */
@AutoConfiguration(after = PrimosAutoConfiguration.class)
@ConditionalOnClass({PrimosNode.class, PrimosLeaderElectionSettings.class})
@ConditionalOnProperty(prefix = "staffix", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ReplicationHaProps.class)
public class PrimosLeaderElectionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LeaderElectionSettings.class)
    public PrimosLeaderElectionSettings staffixPrimosLeaderElection(ObjectProvider<PrimosNode> node, ReplicationHaProps props) {
        PrimosNode primosNode = node.getIfAvailable();
        if (primosNode == null) {
            throw new IllegalStateException("No PrimosNode bean to elect the leading engine with: configure the primos"
                    + " starter, at least primos.application-name");
        }
        return PrimosLeaderElectionSettings.builder().node(primosNode).leadershipControl(props.isLeadershipControl()).build();
    }

    /**
     * Stops the engine before the primos starter stops its node, which would hand leadership over first: the follower
     * would then take over without the leader's clean hand over, and move its seqnums ahead.
     */
    @Bean
    public SmartLifecycle staffixEngineStopsBeforePrimos(ObjectProvider<ManagedFixEngine> engine) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                running = true;
            }

            @Override
            public void stop() {
                running = false;
                engine.ifAvailable(ManagedFixEngine::close);
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                return PrimosNodeLifecycle.PHASE + 1;
            }
        };
    }
}
