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

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.staffix.ha.replication.ReplicationNode;
import org.lolaf.staffix.ha.replication.StreamWriter;
import org.lolaf.staffix.ha.replication.leader.Followers;
import org.lolaf.staffix.ha.replication.leader.StreamLogWriter;
import org.lolaf.staffix.ha.replication.log.ReplicationLogs;
import org.lolaf.staffix.ha.replication.spi.LeaderStreams;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;

/**
 * The application the library's tests replicate: append-only journals of text entries, each journal a stream, on the
 * leader. {@link JournalReplica} is its follower side. It writes straight to the logs, for the tests of the leader's
 * parts, or through the node {@link #writeThrough written through}.
 */
public final class Journal implements LeaderStreams {

    /**
     * {@code 'JRNL'}.
     */
    public static final int APPLICATION_ID = 0x4a524e4c;

    /**
     * Null for a journal written through a node.
     */
    @Getter
    private final @Nullable ReplicationLogs logs;
    /**
     * Null for a journal written through a node.
     */
    @Getter
    private final @Nullable Followers followers;
    private final List<JournalStream> streams;
    private volatile IntFunction<StreamWriter> writers;

    /**
     * @param logFullTimeout how long an append waits for a follower a whole log behind before the log drops it
     */
    public Journal(ReplicationLogs logs, Duration logFullTimeout) {
        Followers direct = new Followers();
        this.logs = logs;
        this.followers = direct;
        this.streams = new CopyOnWriteArrayList<>();
        this.writers = key -> new StreamLogWriter(key, logs.forStream(key), direct, logFullTimeout,
                new BackoffIdleStrategy());
    }

    /**
     * Written through the node {@link #writeThrough} names, before any stream is added.
     */
    public Journal() {
        this.logs = null;
        this.followers = null;
        this.streams = new CopyOnWriteArrayList<>();
        this.writers = key -> {
            throw new IllegalStateException("No node to write through yet");
        };
    }

    /**
     * The node replicating this journal, built with it.
     */
    public void writeThrough(ReplicationNode node) {
        writers = node::writer;
    }

    /**
     * One lane, a follower a whole log behind dropped after 50 ms.
     */
    public static Journal ofCapacity(int logCapacity) {
        return new Journal(new ReplicationLogs(1, logCapacity), Duration.ofMillis(50));
    }

    public synchronized JournalStream add(String name, StreamReplicationMode mode, @Nullable String storageIdentity) {
        JournalStream stream = new JournalStream(streams.size(), name, mode, storageIdentity,
                writers.apply(streams.size()));
        streams.add(stream);
        return stream;
    }

    /**
     * Stored at {@code "leader-storage"}.
     */
    public JournalStream add(String name, StreamReplicationMode mode) {
        return add(name, mode, "leader-storage");
    }

    @Override
    public int getApplicationId() {
        return APPLICATION_ID;
    }

    @Override
    public int count() {
        return streams.size();
    }

    @Override
    public JournalStream get(int key) {
        return streams.get(key);
    }
}
