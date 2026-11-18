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

import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * The followers connected to the leader, and what a {@code SYNC} send waits on: their acks.
 */
public final class Followers {

    public enum Ack {
        ACKED,
        TIMED_OUT,
        NO_FOLLOWER_IN_SYNC
    }

    private static final FollowerConnection[] NONE = new FollowerConnection[0];
    private static final IdleStrategy[] NO_WAITERS = new IdleStrategy[0];

    private volatile FollowerConnection[] connections = NONE;
    private volatile List<FollowerConnection> connectionList = List.of();
    private volatile IdleStrategy[] waiters = NO_WAITERS;
    private final AtomicInteger waiting = new AtomicInteger();

    synchronized void add(FollowerConnection connection) {
        FollowerConnection[] added = Arrays.copyOf(connections, connections.length + 1);
        added[connections.length] = connection;
        setConnections(added);
    }

    synchronized void remove(FollowerConnection connection) {
        setConnections(Arrays.stream(connections).filter(other -> other != connection).toArray(FollowerConnection[]::new));
        wakeWaiters();
    }

    private void setConnections(FollowerConnection[] changed) {
        connections = changed;
        connectionList = List.of(changed);
    }

    /**
     * The strategy a session's {@code SYNC} sends wait on, woken by {@link #wakeWaiters()}.
     */
    public synchronized void addWaiter(IdleStrategy idle) {
        IdleStrategy[] added = Arrays.copyOf(waiters, waiters.length + 1);
        added[waiters.length] = idle;
        waiters = added;
    }

    public synchronized void removeWaiter(IdleStrategy idle) {
        waiters = Arrays.stream(waiters).filter(other -> other != idle).toArray(IdleStrategy[]::new);
    }

    /**
     * After an ack, or a follower gone: wakes the {@code SYNC} sends parked waiting, only when one waits.
     */
    void wakeWaiters() {
        if (waiting.get() > 0) {
            for (IdleStrategy waiter : waiters) {
                waiter.wakeup();
            }
        }
    }

    /**
     * After a write to the log: wakes the senders parked waiting for one.
     */
    public void wakeSenders() {
        for (FollowerConnection connection : connections) {
            connection.wakeSender();
        }
    }

    public List<FollowerConnection> connections() {
        return connectionList;
    }

    /**
     * Whether a follower holds every replicated session: each of its lanes streaming and in sync, one lane in sync
     * not being enough. Any thread.
     *
     * @param sessionCount the sessions the leader replicates
     */
    public boolean isAnyFollowerInSync(int sessionCount, int lanes) {
        return countFollowers(lanes, connection -> connection.isLaneInSync(sessionCount)) > 0;
    }

    /**
     * The followers every lane of which has a connection matching, a follower with one lane down or behind not
     * counted. Any thread.
     */
    public int countFollowers(int lanes, Predicate<FollowerConnection> matching) {
        FollowerConnection[] current = connections;
        int count = 0;
        for (int i = 0; i < current.length; i++) {
            String followerId = current[i].getFollowerId();
            if (followerId != null && isFirstOf(current, i, followerId) && matchesEveryLane(current, followerId, lanes, matching)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isFirstOf(FollowerConnection[] connections, int index, String followerId) {
        for (int i = 0; i < index; i++) {
            if (followerId.equals(connections[i].getFollowerId())) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesEveryLane(FollowerConnection[] connections, String followerId, int lanes,
                                            Predicate<FollowerConnection> matching) {
        for (int lane = 0; lane < lanes; lane++) {
            if (!matchesLane(connections, followerId, lane, matching)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesLane(FollowerConnection[] connections, String followerId, int lane,
                                       Predicate<FollowerConnection> matching) {
        for (FollowerConnection connection : connections) {
            if (connection.getLane() == lane && followerId.equals(connection.getFollowerId()) && matching.test(connection)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the followers' stores have yet to store, as of their last acks, over every lane. A closed connection is
     * left out: it stays listed until its CLOSE is flushed, possibly beside its follower's new one.
     */
    public long getPendingWrites() {
        long pendingWrites = 0;
        for (FollowerConnection connection : connections) {
            if (connection.isConnected()) {
                pendingWrites += connection.getFollowerPendingWrites();
            }
        }
        return pendingWrites;
    }

    /**
     * Announces the session again to every follower, its replication mode having changed to or from {@code NONE}.
     */
    public void reannounce(int sessionKey) {
        for (FollowerConnection connection : connections) {
            connection.reannounce(sessionKey);
        }
    }

    /**
     * Sends the session again by snapshot to every follower holding a copy of it.
     */
    public void resync(int sessionKey) {
        for (FollowerConnection connection : connections) {
            connection.resync(sessionKey);
        }
    }


    /**
     * Waits until every follower in sync for the session received or applied the event at {@code offset}, as its mode
     * asks. A follower falling out of sync stops being waited for, and with none left the wait ends at once. A follower
     * a wait timed out on is not waited for again until it applies what it missed, so a hung follower costs one
     * timeout, not one per send. Allocates nothing.
     *
     * @param mode         {@code SYNC_RECEIVED} or {@code SYNC_APPLIED}
     * @param timeoutNanos 0 to wait as long as a follower is in sync
     */
    public Ack awaitAck(int sessionKey, long offset, ReplicationMode mode, long timeoutNanos, IdleStrategy idle) {
        boolean received = mode == ReplicationMode.SYNC_RECEIVED;
        long startNanos = timeoutNanos == 0 ? 0 : System.nanoTime();
        Ack ack = ackOf(sessionKey, offset, received, timeoutNanos, startNanos);
        if (ack != null) {
            return ack;
        }
        idle.reset();
        waiting.incrementAndGet();
        try {
            while ((ack = ackOf(sessionKey, offset, received, timeoutNanos, startNanos)) == null) {
                idle.idle();
            }
            return ack;
        } finally {
            waiting.decrementAndGet();
            idle.reset();
        }
    }

    /**
     * @return null while it waits on
     */
    private @Nullable Ack ackOf(int sessionKey, long offset, boolean received, long timeoutNanos, long startNanos) {
        boolean anyInSync = false;
        boolean anyWaitedFor = false;
        boolean acked = true;
        for (FollowerConnection connection : connections) {
            if (connection.isSessionInSync(sessionKey)) {
                anyInSync = true;
                if (!connection.isAckOverdue()) {
                    anyWaitedFor = true;
                    acked &= ackedOffset(connection, received) >= offset;
                }
            }
        }
        if (!anyInSync) {
            return Ack.NO_FOLLOWER_IN_SYNC;
        }
        if (!anyWaitedFor) {
            return Ack.TIMED_OUT;
        }
        if (acked) {
            return Ack.ACKED;
        }
        if (timeoutNanos != 0 && System.nanoTime() - startNanos >= timeoutNanos) {
            for (FollowerConnection connection : connections) {
                if (connection.isSessionInSync(sessionKey) && ackedOffset(connection, received) < offset) {
                    connection.ackOverdue(offset);
                }
            }
            return Ack.TIMED_OUT;
        }
        return null;
    }

    /**
     * A read with no {@code SYNC_RECEIVED} event sends no {@code RECEIVED}, so the received offset can trail the applied.
     */
    private static long ackedOffset(FollowerConnection connection, boolean received) {
        long applied = connection.getAppliedOffset();
        return received ? Math.max(connection.getReceivedOffset(), applied) : applied;
    }
}
