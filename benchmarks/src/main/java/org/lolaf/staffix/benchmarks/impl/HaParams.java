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
package org.lolaf.staffix.benchmarks.impl;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;

/**
 * The HA benchmarks' parameter values, named so that a result's label reads on its own: JMH records a parameter's
 * value without its name, and {@code true 1 MEMORY} does not say which parameter is which.
 */
@UtilityClass
public class HaParams {

    @Getter
    @RequiredArgsConstructor
    public enum LeaderStore {
        LEADER_MEMORY(FollowerStorage.Kind.MEMORY),
        LEADER_FILE(FollowerStorage.Kind.FILE);

        private final FollowerStorage.Kind kind;
    }

    @Getter
    @RequiredArgsConstructor
    public enum FollowerStore {
        FOLLOWER_MEMORY(FollowerStorage.Kind.MEMORY),
        FOLLOWER_FILE(FollowerStorage.Kind.FILE);

        private final FollowerStorage.Kind kind;
    }

    @Getter
    @RequiredArgsConstructor
    public enum FileWrites {
        FILE_SYNC_WRITES(true),
        FILE_PAGE_CACHE_WRITES(false);

        private final boolean sync;
    }

    @Getter
    @RequiredArgsConstructor
    public enum BusySession {
        NO_BUSY_SESSION(false),
        BUSY_SESSION(true);

        private final boolean enabled;
    }

    @Getter
    @RequiredArgsConstructor
    public enum BusyResync {
        NO_BUSY_RESYNC(false),
        BUSY_RESYNC(true);

        private final boolean enabled;
    }

    @Getter
    @RequiredArgsConstructor
    public enum Monitoring {
        NO_MONITORING(false),
        MONITORING(true);

        private final boolean enabled;
    }

    @Getter
    @RequiredArgsConstructor
    public enum LeaderIoThreads {
        LEADER_IO_THREADS_1(1),
        LEADER_IO_THREADS_2(2),
        LEADER_IO_THREADS_4(4);

        private final int count;
    }

    @Getter
    @RequiredArgsConstructor
    public enum CounterpartyIoThreads {
        COUNTERPARTY_IO_THREADS_1(1),
        COUNTERPARTY_IO_THREADS_2(2),
        COUNTERPARTY_IO_THREADS_4(4);

        private final int count;
    }

    @Getter
    @RequiredArgsConstructor
    public enum ReplicationLanes {
        REPLICATION_LANES_1(1),
        REPLICATION_LANES_2(2),
        REPLICATION_LANES_4(4),
        REPLICATION_LANES_8(8);

        private final int count;
    }

    @Getter
    @RequiredArgsConstructor
    public enum FollowerStoreWriterThreads {
        FOLLOWER_STORE_WRITER_THREADS_0(0),
        FOLLOWER_STORE_WRITER_THREADS_1(1),
        FOLLOWER_STORE_WRITER_THREADS_2(2),
        FOLLOWER_STORE_WRITER_THREADS_4(4);

        private final int count;
    }

    @Getter
    @RequiredArgsConstructor
    public enum Sessions {
        SESSIONS_1(1),
        SESSIONS_4(4),
        SESSIONS_16(16),
        SESSIONS_64(64);

        private final int count;
    }

    @Getter
    @RequiredArgsConstructor
    public enum Rate {
        RATE_10000_PER_S(10_000),
        RATE_50000_PER_S(50_000),
        RATE_100000_PER_S(100_000),
        RATE_200000_PER_S(200_000),
        RATE_400000_PER_S(400_000);

        private final int perSecond;
    }

    @Getter
    @RequiredArgsConstructor
    public enum SendingTime {
        SENDING_5_S(5),
        SENDING_10_S(10),
        SENDING_30_S(30);

        private final int seconds;
    }
}
