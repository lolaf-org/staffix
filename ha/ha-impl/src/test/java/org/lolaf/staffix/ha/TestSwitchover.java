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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.ha.LeaderElection.Role;
import org.lolaf.staffix.api.ha.SwitchoverOutcome;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@ParameterizedClass
@ValueSource(booleans = {false, true})
class TestSwitchover extends HaPairFixture {

    /**
     * Long enough to log out and hand over, short for a test waiting it out to be refused.
     */
    private static final Duration GIVING_UP_SOON = Duration.ofMillis(300);

    TestSwitchover(boolean storeWriterThreads) {
        super(storeWriterThreads);
    }

    @Test
    void aSwitchoverLogsTheSessionsOutAndTheFollowerTakesThemOverWithoutMovingThemAhead() {
        aLeadsBFollows();
        await().until(() -> a.engine.meters.read("ha.followers.in.sync") == 1);
        a.store.storeNextIncomingSeqNum(7);
        a.election.transfersWith(() -> {
            a.election.elect(Role.FOLLOWER, 4, "b");
            b.election.elect(Role.LEADER, 4, "b");
        });

        assertThat(a.ha.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);

        assertThat(a.engine.activity).contains("deactivate with logout");
        await().until(() -> b.engine.activity.contains("activate, stores open 0"));
        assertThat(b.engine.store(MAIN).writes).as("every write reached b before the transfer").contains("incoming 7")
                .doesNotContain("outgoing 10001");
        await().until(() -> a.ha.getStatus().getRole() == Role.FOLLOWER);
    }

    @Test
    void aFollowerReconnectingBeforeTheLeaderStopsListeningKeepsItsHandOver() {
        aLeadsBFollows();
        await().until(() -> a.engine.meters.read("ha.followers.in.sync") == 1);

        assertThat(a.ha.getServer().handOver(Deadline.of(Duration.ofSeconds(2)))).isTrue();

        await().until(() -> isHandedOver(b));
        await().alias("b reconnects every 50 ms").during(Duration.ofMillis(300)).until(() -> isHandedOver(b));
    }

    private static boolean isHandedOver(Node node) {
        return node.ha.getSessionStatuses().stream().allMatch(HaSessionStatus::isHandedOver);
    }

    @Test
    void aSwitchoverWhoseTransferNeverLandsResumesAndALaterFailoverStillMovesTheSessionsAhead() {
        a = switchingOverWithin("a", GIVING_UP_SOON);
        aLeadsBFollows();
        await().until(() -> a.engine.meters.read("ha.followers.in.sync") == 1);
        a.election.transfersWith(() -> {
        });

        assertThat(a.ha.switchover()).isEqualTo(SwitchoverOutcome.NOT_TRANSFERRED);

        assertThat(a.engine.activity).endsWith("deactivate with logout", "activate, stores open 0");
        await().until(() -> listening(addressA));
        await().until(() -> a.engine.meters.read("ha.followers.in.sync") == 1);
        a.election.elect(Role.FOLLOWER, 4, "b");
        b.election.elect(Role.LEADER, 4, "b");
        await().until(() -> b.engine.activity.contains("activate, stores open 0"));
        assertThat(b.engine.store(MAIN).writes).as("b reconnected since the hand over, which no longer holds")
                .endsWith("outgoing 10001");
    }

    @Test
    void aSwitchoverWaitsForAFollowerInSyncAndChangesNothingWithoutOne() {
        a = switchingOverWithin("a", GIVING_UP_SOON);
        a.election.elect(Role.LEADER, 3, "a");
        a.start();
        await().until(() -> a.engine.activity.contains("activate, stores open 0"));
        a.election.transfersWith(() -> {
        });

        assertThat(a.ha.switchover()).isEqualTo(SwitchoverOutcome.NO_FOLLOWER_IN_SYNC);
        assertThat(a.engine.activity).containsExactly("activate, stores open 0");
    }

    private void bTakesOverOnTransfer() {
        a.election.transfersWith(() -> {
            a.election.elect(Role.FOLLOWER, 4, "b");
            b.election.elect(Role.LEADER, 4, "b");
        });
    }

    private void bHasLeftToStore(long pendingWrites) {
        await().until(() -> a.engine.meters.read("ha.followers.in.sync") == 1);
        b.engine.store(MAIN).pendingWrites = pendingWrites;
        await().until(() -> a.ha.getFollowers().getPendingWrites() == pendingWrites);
    }

    private void bStoresAllAfter(Duration delay) {
        Thread storing = new Thread(() -> {
            await().pollDelay(delay).until(() -> true);
            b.engine.store(MAIN).pendingWrites = 0;
        });
        storing.setDaemon(true);
        storing.start();
    }

    @Test
    void aSwitchoverRefusesAFollowerWithTooMuchLeftToStoreAndChangesNothing() {
        a = switchingOverWithin("a", GIVING_UP_SOON);
        aLeadsBFollows();
        bTakesOverOnTransfer();
        bHasLeftToStore(1_001);

        assertThat(a.ha.switchover()).isEqualTo(SwitchoverOutcome.FOLLOWER_STORE_BEHIND);

        assertThat(a.engine.activity).doesNotContain("deactivate with logout");
        assertThat(a.ha.getStatus().getRole()).isEqualTo(Role.LEADER);
    }

    @Test
    void aSwitchoverGoesAheadWithAsMuchLeftToStoreAsItAllows() {
        aLeadsBFollows();
        bTakesOverOnTransfer();
        bHasLeftToStore(1_000);

        assertThat(a.ha.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);
    }

    @Test
    void aSwitchoverWaitsForTheFollowerToStoreItsBacklog() {
        aLeadsBFollows();
        bTakesOverOnTransfer();
        bHasLeftToStore(5_000);
        bStoresAllAfter(Duration.ofMillis(300));

        assertThat(a.ha.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);

        assertThat(a.engine.activity).contains("deactivate with logout");
    }

    @Test
    void aBacklogStoredLateLeavesTheSwitchoverItsWholeTimeout() {
        a = switchingOverWithin("a", Duration.ofMillis(800));
        aLeadsBFollows();
        a.election.transfersWith(() -> {
            Thread landing = new Thread(() -> {
                await().pollDelay(Duration.ofMillis(400)).until(() -> true);
                a.election.elect(Role.FOLLOWER, 4, "b");
                b.election.elect(Role.LEADER, 4, "b");
            });
            landing.setDaemon(true);
            landing.start();
        });
        bHasLeftToStore(5_000);
        // waiting for it and the landing together take longer than the timeout
        bStoresAllAfter(Duration.ofMillis(450));

        assertThat(a.ha.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);
    }

    @Test
    void aLeaderWithdrawingFromElectionsSwitchesOverFirst() {
        aLeadsBFollows();
        await().until(() -> a.engine.meters.read("ha.followers.in.sync") == 1);
        a.election.electableSupported(true);
        bTakesOverOnTransfer();

        a.ha.setElectable(false);

        assertThat(a.engine.activity).contains("deactivate with logout");
        assertThat(a.ha.getStatus().isElectable()).isFalse();
        await().until(() -> b.engine.activity.contains("activate, stores open 0"));
        assertThat(b.engine.store(MAIN).writes).as("handed over, not failed over").doesNotContain("outgoing 10001");
    }

    @Test
    void aLeaderThatCannotSwitchOverStaysElectable() {
        a = switchingOverWithin("a", GIVING_UP_SOON);
        aLeadsBFollows();
        a.election.electableSupported(true);
        bTakesOverOnTransfer();
        bHasLeftToStore(1_001);

        assertThatThrownBy(() -> a.ha.setElectable(false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FOLLOWER_STORE_BEHIND");

        assertThat(a.ha.getStatus().isElectable()).isTrue();
        assertThat(a.ha.getStatus().getRole()).isEqualTo(Role.LEADER);
    }

    @Test
    void aFollowerWithdrawsFromElectionsWithoutASwitchover() {
        aLeadsBFollows();
        b.election.electableSupported(true);
        bTakesOverOnTransfer();

        b.ha.setElectable(false);

        assertThat(b.ha.getStatus().isElectable()).isFalse();
        assertThat(a.engine.activity).doesNotContain("deactivate with logout");
    }

    @Test
    void aZeroSwitchoverTimeoutRefusesAFollowerNotReadyAtOnce() {
        a = notWaitingToSwitchOver("a");
        aLeadsBFollows();
        bTakesOverOnTransfer();
        bHasLeftToStore(1_001);
        long start = System.nanoTime();

        assertThat(a.ha.switchover()).isEqualTo(SwitchoverOutcome.FOLLOWER_STORE_BEHIND);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(a.engine.activity).doesNotContain("deactivate with logout");
    }

    @Test
    void aZeroSwitchoverTimeoutStillSwitchesOverToAFollowerReadyNow() {
        a = notWaitingToSwitchOver("a");
        aLeadsBFollows();
        bTakesOverOnTransfer();
        await().until(() -> a.engine.meters.read("ha.followers.in.sync") == 1);

        assertThat(a.ha.switchover()).isEqualTo(SwitchoverOutcome.TRANSFERRED);
    }

    @Test
    void onlyALeaderWithAnElectionThatCanTransferSwitchesOver() {
        aLeadsBFollows();

        assertThatThrownBy(() -> a.ha.switchover()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot transfer leadership");
        assertThatThrownBy(() -> b.ha.switchover()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only the leader switches over; node b is FOLLOWER");
    }
}
