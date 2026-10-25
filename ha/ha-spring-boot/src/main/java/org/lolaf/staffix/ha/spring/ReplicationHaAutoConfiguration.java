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

import org.lolaf.staffix.api.ha.LeaderElectionSettings;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for store replication, active when this module is on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(ReplicationHaSettings.class)
@ConditionalOnProperty(prefix = "staffix", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ReplicationHaProps.class)
public class ReplicationHaAutoConfiguration {

    @Bean
    public ReplicationHaContributor staffixReplicationHaContributor(ReplicationHaProps props,
                                                                    ObjectProvider<LeaderElectionSettings> leaderElection) {
        LeaderElectionSettings election = leaderElection.getIfAvailable();
        if (election == null) {
            throw new IllegalStateException("Store replication needs a LeaderElectionSettings bean to tell which node"
                    + " runs the sessions: add staffix-ha-primos-spring-boot, or declare one");
        }
        return new ReplicationHaContributor(props, election);
    }
}
