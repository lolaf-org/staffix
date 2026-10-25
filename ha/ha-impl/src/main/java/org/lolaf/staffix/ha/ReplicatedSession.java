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
     * {@code SYNC} sends that went out without the standby's ack because it did not come in time.
     */
    private volatile long timedOutSends;
    /**
     * {@code SYNC} sends that went out without the standby's ack because no standby was in sync.
     */
    private volatile long noFollowerSends;
    private volatile long lastTimedOutNanos;
    /**
     * Store events left out of the replication log, too long for it.
     */
    private volatile long unreplicatedEvents;
    private volatile long lastUnreplicatedNanos;
    private volatile boolean unprotected;

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
     * From the session's thread only. Logs the change from protected to not and back, never each send.
     */
    void acknowledged(Followers.Ack ack) {
        boolean wasUnprotected = unprotected;
        if (ack == Followers.Ack.APPLIED) {
            if (wasUnprotected) {
                unprotected = false;
                log.info("FIX session {} {} sends are acknowledged by the standby again", fixSessionType, fixSessionId);
            }
            return;
        }
        if (ack == Followers.Ack.TIMED_OUT) {
            lastTimedOutNanos = System.nanoTime();
            timedOutSends++;
        } else {
            noFollowerSends++;
        }
        if (!wasUnprotected) {
            unprotected = true;
            log.warn("FIX session {} {} sends without the standby's ack: {}", fixSessionType, fixSessionId, ack);
        }
    }

    /**
     * From the session's thread only.
     */
    void unreplicated() {
        lastUnreplicatedNanos = System.nanoTime();
        unreplicatedEvents++;
    }

    /**
     * {@code SYNC} sends that went out without the standby's ack, on a timeout or with no standby in sync.
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
        @Nullable String storageIdentity;
        /**
         * The session's own store, which a snapshot reads off the session's thread.
         */
        FixSessionMessagesStore store;

        static Config of(HaSessionSettings settings, FixSessionMessagesStore store) {
            return new Config(settings.getReplicationMode(), settings.getReplicationAckTimeout().toNanos(),
                    store.getStorageIdentity().orElse(null), store);
        }
    }
}
