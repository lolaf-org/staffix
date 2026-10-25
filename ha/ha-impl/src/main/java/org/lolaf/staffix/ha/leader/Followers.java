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
package org.lolaf.staffix.ha.leader;

import org.lolaf.ringos.idling.IdleStrategy;

import java.util.Arrays;
import java.util.List;

/**
 * The followers connected to the leader, and what a {@code SYNC} send waits on: their acks.
 */
public final class Followers {

    public enum Ack {
        APPLIED,
        TIMED_OUT,
        NO_FOLLOWER_IN_SYNC
    }

    private static final FollowerLink[] NONE = new FollowerLink[0];

    private volatile FollowerLink[] links = NONE;

    synchronized void add(FollowerLink link) {
        FollowerLink[] added = Arrays.copyOf(links, links.length + 1);
        added[links.length] = link;
        links = added;
    }

    synchronized void remove(FollowerLink link) {
        links = Arrays.stream(links).filter(other -> other != link).toArray(FollowerLink[]::new);
    }

    public List<FollowerLink> all() {
        return List.of(links);
    }

    /**
     * See {@link FollowerLink#resnapshot(int)}.
     */
    public void resnapshot(int sessionKey) {
        for (FollowerLink link : links) {
            link.resnapshot(sessionKey);
        }
    }

    /**
     * Waits until every follower in sync for the session applied the event at {@code offset}. A follower falling out
     * of sync stops being waited for, and with none left the wait ends at once. Allocates nothing.
     *
     * @param timeoutNanos 0 to wait as long as a follower is in sync
     */
    public Ack awaitApplied(int sessionKey, long offset, long timeoutNanos, IdleStrategy idle) {
        long startNanos = timeoutNanos == 0 ? 0 : System.nanoTime();
        idle.reset();
        while (true) {
            boolean anyInSync = false;
            boolean applied = true;
            for (FollowerLink link : links) {
                if (link.isInSync(sessionKey)) {
                    anyInSync = true;
                    applied &= link.getAppliedOffset() >= offset;
                }
            }
            if (!anyInSync) {
                return Ack.NO_FOLLOWER_IN_SYNC;
            }
            if (applied) {
                return Ack.APPLIED;
            }
            if (timeoutNanos != 0 && System.nanoTime() - startNanos >= timeoutNanos) {
                return Ack.TIMED_OUT;
            }
            idle.idle();
        }
    }
}
