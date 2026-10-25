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
package org.lolaf.staffix.ha.it;

import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.LeaderElection;
import org.lolaf.staffix.api.ha.LeaderElectionSettings;

import java.util.Optional;

/**
 * An election whose outcome is fixed by its settings.
 */
final class StaticLeaderElection implements LeaderElection {

    private final Settings settings;
    private volatile boolean started;

    private StaticLeaderElection(Settings settings) {
        this.settings = settings;
    }

    @Override
    public Role getRole() {
        return settings.getRole();
    }

    @Override
    public long getEpoch() {
        return 1;
    }

    @Override
    public Optional<String> getLeaderId() {
        return Optional.of(settings.getLeaderId());
    }

    @Override
    public boolean isLeadershipValid() {
        return true;
    }

    @Override
    public void addListener(Listener listener) {
        // nothing ever changes
    }

    @Override
    public LeaderElection start() {
        started = true;
        return this;
    }

    @Override
    public LeaderElection stop(Deadline stopDeadline) {
        started = false;
        return this;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Getter
    @SuperBuilder
    static class Settings extends LeaderElectionSettings {

        private final Role role;
        private final String leaderId;
    }

    public static class Factory implements LeaderElectionFactory<Settings> {

        @Override
        public Class<Settings> getSettingsClass() {
            return Settings.class;
        }

        @Override
        public LeaderElection newInstance(Settings settings) {
            return new StaticLeaderElection(settings);
        }
    }
}
