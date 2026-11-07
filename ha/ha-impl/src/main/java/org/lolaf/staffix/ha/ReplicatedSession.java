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
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.ha.leader.Followers;

/**
 * A replicated session as the leader knows it: the key its events carry in the log and on the wire.
 */
@Slf4j
@Getter
public final class ReplicatedSession {

    private final int key;
    private final FixSessionId fixSessionId;
    private final FixSessionType fixSessionType;
    /**
     * Replaced whole each time the session is built, so a reader on another thread never sees half of an update.
     */
    private volatile Config config;
    /**
     * {@code SYNC} sends that went out without the follower's ack because it did not come in time.
     */
    private volatile long timedOutSends;
    /**
     * {@code SYNC} sends that went out without the follower's ack because no follower was in sync.
     */
    private volatile long noFollowerSends;
    private volatile long lastTimedOutNanos;
    /**
     * Store events left out of the replication log, too long for it.
     */
    private volatile long unreplicatedEvents;
    private volatile long lastUnreplicatedNanos;
    /**
     * The last {@code SYNC} send went out without the follower's ack: a failover now may lose it.
     */
    private volatile boolean lastSendUnacknowledged;

    ReplicatedSession(int key, FixSessionId fixSessionId, FixSessionType fixSessionType, Config config) {
        this.key = key;
        this.fixSessionId = fixSessionId;
        this.fixSessionType = fixSessionType;
        this.config = config;
    }

    void update(Config config) {
        this.config = config;
    }

    /**
     * False once built with replication mode {@code NONE}, until built with another.
     */
    public boolean isReplicated() {
        return config.getReplicationMode() != ReplicationMode.NONE;
    }

    /**
     * From the session's thread only, after each {@code SYNC} send. Logs the change from acknowledged to not and back,
     * never each send.
     */
    void recordAck(Followers.Ack ack) {
        boolean wasUnacknowledged = lastSendUnacknowledged;
        if (ack == Followers.Ack.ACKED) {
            if (wasUnacknowledged) {
                lastSendUnacknowledged = false;
                log.info("FIX session {} {} sends are acknowledged by the follower again", fixSessionType, fixSessionId);
            }
            return;
        }
        if (ack == Followers.Ack.TIMED_OUT) {
            lastTimedOutNanos = System.nanoTime();
            timedOutSends++;
        } else {
            noFollowerSends++;
        }
        if (!wasUnacknowledged) {
            lastSendUnacknowledged = true;
            log.warn("FIX session {} {} sends without the follower's ack: {}", fixSessionType, fixSessionId, ack);
        }
    }

    /**
     * From the session's thread only.
     */
    void recordUnreplicatedEvent() {
        lastUnreplicatedNanos = System.nanoTime();
        unreplicatedEvents++;
    }

    /**
     * {@code SYNC} sends that went out without the follower's ack, on a timeout or with no follower in sync.
     */
    public long getUnacknowledgedSends() {
        return timedOutSends + noFollowerSends;
    }

    /**
     * What the session was last built with.
     */
    @Value
    public static class Config {

        ReplicationMode replicationMode;
        long replicationAckTimeoutNanos;
        boolean syncAckLatencyEnabled;
        @Nullable String storageIdentity;
        /**
         * The session's own store, which a snapshot reads off the session's thread.
         */
        FixSessionMessagesStore store;

        Config notReplicated() {
            return new Config(ReplicationMode.NONE, replicationAckTimeoutNanos, syncAckLatencyEnabled, storageIdentity, store);
        }

        static Config of(HaSessionSettings settings, FixSessionMessagesStore store) {
            return new Config(settings.getReplicationMode(), settings.getReplicationAckTimeout().toNanos(),
                    settings.isSyncAckLatencyEnabled(),
                    store.getStorageIdentity().orElse(null), store);
        }
    }
}
