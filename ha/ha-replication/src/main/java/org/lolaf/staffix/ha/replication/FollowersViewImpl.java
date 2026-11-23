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
package org.lolaf.staffix.ha.replication;

import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.ha.replication.leader.FollowerConnection;
import org.lolaf.staffix.ha.replication.leader.Followers;
import org.lolaf.staffix.ha.replication.leader.ReplicationStats;
import org.lolaf.staffix.ha.replication.log.ReplicationLogs;
import org.lolaf.staffix.ha.replication.spi.LeaderStreams;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
final class FollowersViewImpl implements FollowersView {

    private final Followers followers;
    private final ReplicationStats stats;
    private final ReplicationLogs logs;
    private final LeaderStreams streams;

    @Override
    public List<FollowerLaneStatus> getLaneStatuses() {
        List<FollowerConnection> connections = followers.connections();
        int streamCount = streams.count();
        List<FollowerLaneStatus> statuses = new ArrayList<>(connections.size());
        for (FollowerConnection connection : connections) {
            statuses.add(connection.getStatus(streamCount));
        }
        return statuses;
    }

    @Override
    public List<DroppedFollower> getDroppedFollowers() {
        return stats.getSlowFollowers().notBackInSync();
    }

    @Override
    public int countConnected() {
        return followers.countFollowers(logs.count(), FollowerConnection::isConnected);
    }

    @Override
    public int countInSync() {
        int streamCount = streams.count();
        return followers.countFollowers(logs.count(), connection -> connection.isLaneInSync(streamCount));
    }

    @Override
    public boolean isAnyInSync() {
        return followers.isAnyFollowerInSync(streams.count(), logs.count());
    }

    @Override
    public long getPendingWrites() {
        return followers.getPendingWrites();
    }

    @Override
    public long getMaxUnsentBytes() {
        long most = 0;
        for (FollowerConnection connection : followers.connections()) {
            most = Math.max(most, connection.getUnsentBytes());
        }
        return most;
    }

    @Override
    public long getMaxLagNanos() {
        long now = System.nanoTime();
        long most = 0;
        for (FollowerConnection connection : followers.connections()) {
            most = Math.max(most, connection.getLagNanos(now));
        }
        return most;
    }

    @Override
    public boolean isStreamInSync(int streamKey) {
        for (FollowerConnection connection : followers.connections()) {
            if (connection.isStreamInSync(streamKey)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<String, StreamAnswer> getStreamRefusals(int streamKey) {
        Map<String, StreamAnswer> refusals = Map.of();
        for (FollowerConnection connection : followers.connections()) {
            StreamAnswer answer = connection.getStreamAnswer(streamKey);
            String followerId = connection.getFollowerId();
            if (answer != null && answer.isRefusal() && followerId != null) {
                if (refusals.isEmpty()) {
                    refusals = new HashMap<>();
                }
                refusals.put(followerId, answer);
            }
        }
        return refusals;
    }

    @Override
    public long getDroppedTooSlow() {
        return stats.getDroppedTooSlow();
    }

    @Override
    public long getSnapshots() {
        return stats.getSnapshots();
    }

    @Override
    public long getSnapshotRecords() {
        return stats.getSnapshotMessages();
    }

    @Override
    public long getSendWaits() {
        return stats.getSendWaits();
    }

    @Override
    public long getSendBuffersAllocated() {
        return stats.getSendBuffersAllocated();
    }
}
