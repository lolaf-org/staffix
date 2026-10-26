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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One node's view of a {@link Ballot} several nodes share: the test elects a node, as an election after a crash
 * would, or the leader transfers leadership to another node on request.
 */
final class SharedLeaderElection implements LeaderElection {

    private final Ballot ballot;
    private final String nodeId;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean started;

    private SharedLeaderElection(Settings settings) {
        this.ballot = settings.getBallot();
        this.nodeId = settings.getNodeId();
        ballot.views.add(this);
    }

    @Override
    public Role getRole() {
        return ballot.leaderId.equals(nodeId) ? Role.LEADER : Role.FOLLOWER;
    }

    @Override
    public long getEpoch() {
        return ballot.epoch;
    }

    @Override
    public Optional<String> getLeaderId() {
        return Optional.of(ballot.leaderId);
    }

    @Override
    public boolean isLeadershipValid() {
        return true;
    }

    @Override
    public boolean isTransferLeadershipSupported() {
        return true;
    }

    /**
     * Elects the first other node, as a transfer that lands.
     */
    @Override
    public boolean transferLeadership() {
        if (getRole() != Role.LEADER) {
            return false;
        }
        ballot.views.stream().map(view -> view.nodeId).filter(other -> !other.equals(nodeId)).findFirst()
                .ifPresent(ballot::elect);
        return true;
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

    /**
     * Who leads, at which epoch, for every node sharing it.
     */
    static final class Ballot {

        private final List<SharedLeaderElection> views = new CopyOnWriteArrayList<>();
        private volatile String leaderId;
        private volatile long epoch = 1;

        Ballot(String leaderId) {
            this.leaderId = leaderId;
        }

        synchronized void elect(String nodeId) {
            epoch++;
            leaderId = nodeId;
            for (SharedLeaderElection view : views) {
                Role role = view.getRole();
                view.listeners.forEach(listener -> listener.onRoleChanged(role, role, epoch));
            }
        }
    }

    @Getter
    @SuperBuilder
    static class Settings extends LeaderElectionSettings {

        private final Ballot ballot;
        private final String nodeId;
    }

    public static class Factory implements LeaderElectionFactory<Settings> {

        @Override
        public Class<Settings> getSettingsClass() {
            return Settings.class;
        }

        @Override
        public LeaderElection newInstance(Settings settings) {
            return new SharedLeaderElection(settings);
        }
    }
}
