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
package org.lolaf.staffix.ha.replication.journal;

import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.replication.spi.ReplicaSinks;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The follower side of {@link Journal}: the journals this node is configured with, each bound into its own
 * {@link JournalCopy}.
 */
public final class JournalReplica implements ReplicaSinks {

    private final Map<String, JournalCopy> copies;

    public JournalReplica() {
        this.copies = new ConcurrentHashMap<>();
    }

    /**
     * Stored at {@code "follower-storage"}.
     */
    public JournalCopy configure(String name, StreamReplicationMode mode) {
        return configure(name, mode, "follower-storage");
    }

    /**
     * Replaces the configuration of a journal configured already, keeping its copy.
     *
     * @param storageIdentity null for storage that cannot be identified
     */
    public JournalCopy configure(String name, StreamReplicationMode mode, @Nullable String storageIdentity) {
        return copies.compute(name, (key, copy) -> {
            JournalCopy configured = copy == null ? new JournalCopy() : copy;
            configured.configure(mode, storageIdentity);
            return configured;
        });
    }

    public @Nullable JournalCopy copy(String name) {
        return copies.get(name);
    }

    @Override
    public int getApplicationId() {
        return Journal.APPLICATION_ID;
    }

    @Override
    public Binding open(String streamName, StreamReplicationMode leaderMode, @Nullable String leaderStorageIdentity) {
        JournalCopy copy = copies.get(streamName);
        if (copy == null) {
            return Binding.refused(StreamAnswer.UNKNOWN_STREAM);
        }
        if (copy.getMode() != leaderMode) {
            return Binding.refused(StreamAnswer.MODE_MISMATCH);
        }
        String identity = copy.getStorageIdentity();
        if (identity == null || leaderStorageIdentity == null) {
            return Binding.refused(StreamAnswer.UNKNOWN_STORAGE);
        }
        if (identity.equals(leaderStorageIdentity)) {
            return Binding.refused(StreamAnswer.SHARED_STORAGE);
        }
        return Binding.replicated(copy);
    }

    @Override
    public @Nullable StreamReplicationMode modeHere(String streamName) {
        JournalCopy copy = copies.get(streamName);
        return copy == null ? null : copy.getMode();
    }
}
