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

import org.lolaf.replicos.follower.FollowerStreams;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.lolaf.replicos.LeaderStatus;
import org.lolaf.replicos.LeaderView;
import org.lolaf.replicos.ReplicaStream;

import java.util.Map;

/**
 * What a test's own {@link FollowerStreams} hold, as a node following no leader shows it.
 */
@RequiredArgsConstructor
final class FollowerStreamsView implements LeaderView {

    private final FollowerStreams streams;

    @Override
    public @Nullable LeaderStatus getStatus() {
        return null;
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public boolean isInSync() {
        return false;
    }

    @Override
    public boolean isEncrypted() {
        return false;
    }

    @Override
    public boolean isCopyInSync() {
        return streams.isInSync();
    }

    @Override
    public int getLanes() {
        return streams.getLanes();
    }

    @Override
    public @Nullable ReplicaStream getStream(String streamName) {
        return streams.find(streamName);
    }

    @Override
    public long getReconnects() {
        return streams.getStats().getReconnects();
    }

    @Override
    public long getBytesReceived() {
        return streams.getStats().getBytesReceived();
    }

    @Override
    public long getRecordsReceived() {
        return streams.getStats().getRecordsReceived();
    }

    @Override
    public long getSnapshots() {
        return streams.getStats().getSnapshots();
    }

    @Override
    public long getSnapshotRecords() {
        return streams.getStats().getSnapshotRecords();
    }

    @Override
    public Map<String, Long> getCloses() {
        return streams.getStats().getCloses().toMap();
    }
}
