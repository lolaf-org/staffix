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

import org.lolaf.ringos.Deadline;

import java.net.InetSocketAddress;
import java.util.function.LongSupplier;

/**
 * One node of a replicated application: it leads, streaming its streams' records to its followers, or follows,
 * applying its leader's into its sinks. Which, and when, is for the application's leader election to say: the role
 * methods are called from one thread, the rest from any.
 */
public interface ReplicationNode {

    static ReplicationNodeBuilder builder() {
        return new ReplicationNodeBuilder();
    }

    /**
     * Stands down, stops the sinks of what the node held as a follower, so the application can take over its streams,
     * then accepts followers on the bind address.
     *
     * @param epoch    the leadership this node holds, sent with every frame so its followers refuse a deposed leader
     * @param deadline for standing down and stopping the sinks
     */
    void lead(long epoch, Deadline deadline);

    /**
     * Stands down, then connects to the leader, resuming from what the node holds of its streams.
     *
     * @param epoch the leadership the node knows of, read on every connection
     */
    void follow(InetSocketAddress leaderAddress, LongSupplier epoch, Deadline deadline);

    /**
     * Tells the followers this leader stops cleanly, once they hold everything it wrote, so the next leader takes
     * over each stream where it was left. Followers connecting from then on are refused. The application stops
     * writing first.
     *
     * @return whether a follower was told; false when not leading
     */
    boolean handOver(Deadline deadline);

    /**
     * Stops leading or following, keeping what the node holds as a follower.
     */
    void standDown(Deadline deadline);

    /**
     * Stands down and stops the sinks.
     */
    void close(Deadline deadline);

    boolean isLeading();

    boolean isFollowing();

    /**
     * Opens the stream's writer, for one thread at a time. Its records reach the followers while the node leads.
     */
    StreamWriter writer(int streamKey);

    /**
     * Announces the stream again to every follower, after its replication mode or storage identity changed.
     */
    void reannounce(int streamKey);

    /**
     * Sends the stream again by snapshot to every follower holding a copy of it, which starts the copy over.
     */
    void resync(int streamKey);

    FollowersView followers();

    LeaderView leader();

    /**
     * The leader's lanes, each a log and a connection per follower, so a snapshot or a slow follower on one lane
     * holds back only its streams. A stream belongs to lane {@code streamKey % lanes}.
     */
    int getLanes();

    /**
     * The bytes written to every lane's log since the node was built.
     */
    long getLogBytesWritten();

    /**
     * The cipher, its key size and, for a stream cipher, its MAC, as the encryption settings ask: what peers agree on.
     */
    String describeCipher();

    /**
     * The cipher allocates on every frame, inside the JCE: replication adds garbage collections.
     */
    boolean cipherAllocatesOnEveryFrame();
}
