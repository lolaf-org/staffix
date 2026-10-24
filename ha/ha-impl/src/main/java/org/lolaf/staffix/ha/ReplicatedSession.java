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
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.ha.leader.Followers;

import java.time.Duration;

/**
 * A replicated session as the leader knows it: the key its events carry in the log and on the wire.
 */
@Slf4j
@Getter
public final class ReplicatedSession {

    private final int key;
    private final FixSessionId fixSessionId;
    private final FixSessionType fixSessionType;
    private volatile ReplicationMode replicationMode = ReplicationMode.ASYNC;
    private volatile long replicationAckTimeoutNanos;
    private volatile @Nullable String storageIdentity;
    /**
     * Set when an event could not be replicated, so the follower's copy has a gap only a snapshot fills.
     */
    private volatile boolean snapshotNeeded;
    /**
     * {@code SYNC} sends that went out without the standby's ack, on a timeout or with no standby in sync.
     */
    private volatile long unacknowledgedSends;
    private volatile boolean unprotected;

    ReplicatedSession(int key, FixSessionId fixSessionId, FixSessionType fixSessionType) {
        this.key = key;
        this.fixSessionId = fixSessionId;
        this.fixSessionType = fixSessionType;
    }

    void update(ReplicationMode replicationMode, Duration replicationAckTimeout, @Nullable String storageIdentity) {
        this.replicationMode = replicationMode;
        this.replicationAckTimeoutNanos = replicationAckTimeout.toNanos();
        this.storageIdentity = storageIdentity;
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
        unacknowledgedSends++;
        if (!wasUnprotected) {
            unprotected = true;
            log.warn("FIX session {} {} sends without the standby's ack: {}", fixSessionType, fixSessionId, ack);
        }
    }

    void unreplicated(String reason) {
        snapshotNeeded = true;
        log.warn("FIX session {} {} needs a snapshot to its follower: {}", fixSessionType, fixSessionId, reason);
    }
}
