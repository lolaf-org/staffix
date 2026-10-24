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

import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.LeaderElection;
import org.lolaf.staffix.api.ha.LeaderElectionSettings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An election the test decides.
 */
public final class FakeLeaderElection implements LeaderElection {

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Role role = Role.UNKNOWN;
    private volatile long epoch;
    private volatile String leaderId;
    private volatile boolean leadershipValid = true;
    private volatile boolean started;

    public void elect(Role role, long epoch, String leaderId) {
        Role previous = this.role;
        this.role = role;
        this.epoch = epoch;
        this.leaderId = leaderId;
        listeners.forEach(listener -> listener.onRoleChanged(previous, role, epoch));
    }

    public void leadershipValid(boolean leadershipValid) {
        this.leadershipValid = leadershipValid;
    }

    @Override
    public Role getRole() {
        return role;
    }

    @Override
    public long getEpoch() {
        return epoch;
    }

    @Override
    public Optional<String> getLeaderId() {
        return Optional.ofNullable(leaderId);
    }

    @Override
    public boolean isLeadershipValid() {
        return leadershipValid;
    }

    @Override
    public void addListener(Listener listener) {
        listeners.add(listener);
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
    public static class Settings extends LeaderElectionSettings {

        private final FakeLeaderElection election;
    }

    public static class Factory implements LeaderElectionFactory<Settings> {

        @Override
        public Class<Settings> getSettingsClass() {
            return Settings.class;
        }

        @Override
        public LeaderElection newInstance(Settings settings) {
            return settings.getElection();
        }
    }
}
