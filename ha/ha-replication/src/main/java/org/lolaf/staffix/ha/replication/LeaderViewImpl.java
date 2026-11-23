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
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.replication.follower.FollowerStreams;
import org.lolaf.staffix.ha.replication.follower.ReplicationClient;

import java.util.function.Supplier;

@RequiredArgsConstructor
final class LeaderViewImpl implements LeaderView {

    private final Supplier<@Nullable ReplicationClient> client;
    private final FollowerStreams followerStreams;

    @Override
    public @Nullable LeaderStatus getStatus() {
        ReplicationClient following = client.get();
        return following == null ? null : following.getStatus();
    }

    @Override
    public boolean isConnected() {
        ReplicationClient following = client.get();
        return following != null && following.isConnected();
    }

    @Override
    public boolean isInSync() {
        ReplicationClient following = client.get();
        return following != null && following.isInSync();
    }

    @Override
    public boolean isEncrypted() {
        ReplicationClient following = client.get();
        return following != null && following.isEncrypted();
    }

    @Override
    public boolean isCopyInSync() {
        return followerStreams.isInSync();
    }

    @Override
    public int getLanes() {
        return followerStreams.getLanes();
    }

    @Override
    public @Nullable ReplicaStream getStream(String streamName) {
        return followerStreams.find(streamName);
    }
}
