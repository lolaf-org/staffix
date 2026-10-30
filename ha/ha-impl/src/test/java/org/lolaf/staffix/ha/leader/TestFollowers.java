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

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
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
                .haSessionSettings(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.SYNC).build())
                .build(), mock(FixSessionMessagesStore.class), new RecordingProbes());
        store.storeNextIncomingSeqNum(1);
        offset = ha.getReplicationLog().getTailOffset();
        store.storeNextIncomingSeqNum(2);
    }

    private FollowerLink inSyncFollower(long appliedOffset) {
        IOSession connection = mock(IOSession.class);
        when(connection.borrow(anyInt())).then(invocation -> ByteBuffer.allocate(invocation.getArgument(0)));
        FollowerLink link = new FollowerLink(connection, ha.getReplicationLog(), ha.getSessions(), () -> EPOCH, "leader-a",
                Duration.ofSeconds(1), 100, 4, new ReplicationStats(), loop -> {
        });
        link.onHello(EPOCH, WireFormat.VERSION, "follower", EPOCH, 0);
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
    void theWaitEndsWhenTheFollowerAcks() {
        FollowerLink follower = inSyncFollower(0);

        CompletableFuture<Followers.Ack> ack = CompletableFuture.supplyAsync(() -> followers.awaitApplied(KEY, offset, NO_TIMEOUT, new BackoffIdleStrategy()));
        follower.onAck(EPOCH, offset, true);

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
