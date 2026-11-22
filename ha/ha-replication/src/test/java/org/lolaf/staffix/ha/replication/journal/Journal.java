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
import org.lolaf.staffix.ha.replication.leader.Followers;
import org.lolaf.staffix.ha.replication.log.ReplicationLogs;
import org.lolaf.staffix.ha.replication.spi.LeaderStreams;
import org.lolaf.staffix.ha.replication.wire.StreamReplicationMode;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The application the library's tests replicate: append-only journals of text entries, each journal a stream, on the
 * leader. {@link JournalReplica} is its follower side.
 */
public final class Journal implements LeaderStreams {

    /**
     * {@code 'JRNL'}.
     */
    public static final int APPLICATION_ID = 0x4a524e4c;

    @Getter
    private final ReplicationLogs logs;
    @Getter
    private final Followers followers;
    private final Duration logFullTimeout;
    private final List<JournalStream> streams;

    /**
     * @param logFullTimeout how long an append waits for a follower a whole log behind before the log drops it
     */
    public Journal(ReplicationLogs logs, Duration logFullTimeout) {
        this.logs = logs;
        this.logFullTimeout = logFullTimeout;
        this.followers = new Followers();
        this.streams = new CopyOnWriteArrayList<>();
    }

    /**
     * One lane, a follower a whole log behind dropped after 50 ms.
     */
    public static Journal ofCapacity(int logCapacity) {
        return new Journal(new ReplicationLogs(1, logCapacity), Duration.ofMillis(50));
    }

    public synchronized JournalStream add(String name, StreamReplicationMode mode, @Nullable String storageIdentity) {
        JournalStream stream = new JournalStream(streams.size(), name, mode, storageIdentity,
                logs.forStream(streams.size()), followers, logFullTimeout);
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
