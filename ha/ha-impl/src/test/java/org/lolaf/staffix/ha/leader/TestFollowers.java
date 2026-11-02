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

import org.junit.jupiter.api.Test;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.ha.ReplicationFixEngineHa;
import org.lolaf.staffix.ha.ReplicationHaSettings;
import org.lolaf.staffix.ha.follower.RecordingProbes;
import org.lolaf.staffix.ha.wire.AllocatedBytes;
import org.lolaf.staffix.ha.wire.SessionStatus;
import org.lolaf.staffix.ha.wire.WireFormat;
import org.lolaf.staffix.tests.TestingClock;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestFollowers {

    private static final long EPOCH = 7;
    private static final long NO_TIMEOUT = 0;
    private static final int KEY = 0;

    private final ReplicationFixEngineHa ha = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1 << 16).build());
    private final Followers followers = new Followers();
    private final IdleStrategy idle = new BackoffIdleStrategy();
    private long offset;

    TestFollowers() {
        FixSessionMessagesStore store = ha.replicatingStore(FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of("main", FixRegularVersion.VERSION_44, "SENDER", "TARGET"))
                .fixSessionType(FixSessionType.INITIATOR)
                .haSessionSettings(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.SYNC_APPLIED).build())
                .build(), mock(FixSessionMessagesStore.class), new RecordingProbes());
        store.storeNextIncomingSeqNum(1);
        offset = ha.getReplicationLogs().get(0).getTailOffset();
        store.storeNextIncomingSeqNum(2);
    }

    private FollowerLink inSyncFollower(long appliedOffset) {
        IOSession connection = mock(IOSession.class);
        when(connection.borrow(anyInt())).then(invocation -> ByteBuffer.allocate(invocation.getArgument(0)));
        FollowerLink link = new FollowerLink(connection, ha.getReplicationLogs(), ha.getSessions(), () -> EPOCH, "leader-a",
                Duration.ofSeconds(1), Duration.ZERO, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(), new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingClock())), loop -> {
        });
        link.onHello(EPOCH, WireFormat.VERSION, "follower", EPOCH, 0, 0);
        link.fill(ByteBuffer.allocate(1 << 16));
        link.onSessionStatus(EPOCH, KEY, SessionStatus.REPLICATING);
        link.onAck(EPOCH, appliedOffset, true);
        followers.add(link);
        return link;
    }

    @Test
    void withoutAFollowerInSyncTheWaitEndsAtOnceEvenWithoutATimeout() {
        assertThat(followers.awaitApplied(KEY, offset, NO_TIMEOUT, idle)).isEqualTo(Followers.Ack.NO_FOLLOWER_IN_SYNC);

        inSyncFollower(0).onSessionStatus(EPOCH, KEY, SessionStatus.SNAPSHOT_NEEDED);

        assertThat(followers.awaitApplied(KEY, offset, NO_TIMEOUT, idle)).isEqualTo(Followers.Ack.NO_FOLLOWER_IN_SYNC);
    }

    @Test
    void anEventTheFollowerAppliedNeedsNoWait() {
        inSyncFollower(offset);

        assertThat(followers.awaitApplied(KEY, offset, NO_TIMEOUT, idle)).isEqualTo(Followers.Ack.APPLIED);
    }

    @Test
    void anAckLateByMoreThanTheTimeoutEndsTheWait() {
        inSyncFollower(0);

        long start = System.nanoTime();
        assertThat(followers.awaitApplied(KEY, offset, TimeUnit.MILLISECONDS.toNanos(20), idle)).isEqualTo(Followers.Ack.TIMED_OUT);
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(20));
    }

    @Test
    void aFollowerAWaitTimedOutOnIsNotWaitedForAgainUntilItAppliesWhatItMissed() {
        FollowerLink follower = inSyncFollower(0);
        assertThat(followers.awaitApplied(KEY, offset, TimeUnit.MILLISECONDS.toNanos(20), idle)).isEqualTo(Followers.Ack.TIMED_OUT);

        assertThat(CompletableFuture.supplyAsync(() -> followers.awaitApplied(KEY, offset, NO_TIMEOUT, new BackoffIdleStrategy())))
                .as("no second wait, even without a timeout").succeedsWithin(Duration.ofSeconds(5)).isEqualTo(Followers.Ack.TIMED_OUT);

        follower.onAck(EPOCH, offset, true);

        assertThat(followers.awaitApplied(KEY, offset, NO_TIMEOUT, idle)).isEqualTo(Followers.Ack.APPLIED);
    }

    @Test
    void theWaitEndsWhenTheFollowerAcks() {
        FollowerLink follower = inSyncFollower(0);

        CompletableFuture<Followers.Ack> ack = CompletableFuture.supplyAsync(() -> followers.awaitApplied(KEY, offset, NO_TIMEOUT, new BackoffIdleStrategy()));
        follower.onAck(EPOCH, offset, true);

        assertThat(ack).succeedsWithin(Duration.ofSeconds(5)).isEqualTo(Followers.Ack.APPLIED);
    }

    @Test
    void anAckWakesAWaitParkedLongerThanTheTest() {
        FollowerLink follower = inSyncFollower(0);
        long parkNanos = TimeUnit.SECONDS.toNanos(30);
        IdleStrategy parking = new BackoffIdleStrategy(0, 0, parkNanos, parkNanos);
        followers.addWaiter(parking);
        CompletableFuture<Followers.Ack> ack = new CompletableFuture<>();
        Thread waiter = new Thread(() -> ack.complete(followers.awaitApplied(KEY, offset, NO_TIMEOUT, parking)));
        waiter.start();
        await().until(() -> waiter.getState() == Thread.State.TIMED_WAITING);

        follower.onAck(EPOCH, offset, true);
        followers.wakeWaiters();

        assertThat(ack).succeedsWithin(Duration.ofSeconds(5)).isEqualTo(Followers.Ack.APPLIED);
    }

    @Test
    void aFollowerDisconnectingReleasesTheWait() {
        FollowerLink follower = inSyncFollower(0);

        CompletableFuture<Followers.Ack> ack = CompletableFuture.supplyAsync(() -> followers.awaitApplied(KEY, offset, NO_TIMEOUT, new BackoffIdleStrategy()));
        follower.disconnected();

        assertThat(ack).succeedsWithin(Duration.ofSeconds(5)).isEqualTo(Followers.Ack.NO_FOLLOWER_IN_SYNC);
    }

    @Test
    void aSyncSendWaitsOnlyForTheLinkCarryingItsSession() {
        ReplicationFixEngineHa twoLinks = new ReplicationFixEngineHa(ReplicationHaSettings.builder().logCapacity(1 << 16)
                .replicationLinks(2).build());
        FixSessionMessagesStore main = twoLinks.replicatingStore(syncSession("main"), mock(FixSessionMessagesStore.class),
                new RecordingProbes());
        FixSessionMessagesStore other = twoLinks.replicatingStore(syncSession("other"), mock(FixSessionMessagesStore.class),
                new RecordingProbes());
        main.storeNextIncomingSeqNum(1);
        other.storeNextIncomingSeqNum(1);
        IOSession connection = mock(IOSession.class);
        when(connection.borrow(anyInt())).then(invocation -> ByteBuffer.allocate(invocation.getArgument(0)));
        FollowerLink linkOfMain = new FollowerLink(connection, twoLinks.getReplicationLogs(), twoLinks.getSessions(),
                () -> EPOCH, "leader-a", Duration.ofSeconds(1), Duration.ZERO, 4, Integer.MAX_VALUE, new BackoffIdleStrategy(),
                new ReplicationStats(new SlowFollowers(Duration.ZERO, new TestingClock())), loop -> {
        });
        linkOfMain.onHello(EPOCH, WireFormat.VERSION, "follower", EPOCH, -1, 0);
        linkOfMain.fill(ByteBuffer.allocate(1 << 16));
        linkOfMain.onSessionStatus(EPOCH, 0, SessionStatus.REPLICATING);
        linkOfMain.onAck(EPOCH, twoLinks.getReplicationLogs().get(0).getTailOffset(), true);
        followers.add(linkOfMain);

        assertThat(followers.awaitApplied(0, 0, NO_TIMEOUT, idle)).isEqualTo(Followers.Ack.APPLIED);
        assertThat(followers.awaitApplied(1, 0, NO_TIMEOUT, idle)).as("other's link is not connected")
                .isEqualTo(Followers.Ack.NO_FOLLOWER_IN_SYNC);
    }

    private static FixSessionSettings syncSession(String name) {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(name, FixRegularVersion.VERSION_44, "SENDER", "TARGET"))
                .fixSessionType(FixSessionType.INITIATOR)
                .haSessionSettings(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.SYNC_APPLIED).build())
                .build();
    }

    @Test
    void everyFollowerInSyncMustApplyTheEvent() {
        inSyncFollower(offset);
        inSyncFollower(0);

        assertThat(followers.awaitApplied(KEY, offset, TimeUnit.MILLISECONDS.toNanos(1), idle)).isEqualTo(Followers.Ack.TIMED_OUT);
    }

    @Test
    void waitingAllocatesNothing() {
        inSyncFollower(offset);
        followers.awaitApplied(KEY, offset, NO_TIMEOUT, idle);

        long before = AllocatedBytes.ofCurrentThread();
        for (int i = 0; i < 100_000; i++) {
            followers.awaitApplied(KEY, offset, NO_TIMEOUT, idle);
        }

        assertThat(AllocatedBytes.ofCurrentThread() - before).as("less than a byte per wait").isLessThan(100_000);
    }
}
