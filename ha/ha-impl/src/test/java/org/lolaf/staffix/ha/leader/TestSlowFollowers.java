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
import org.lolaf.staffix.api.ha.HaStatus;
import org.lolaf.staffix.tests.TestingClock;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TestSlowFollowers {

    private static final Instant START = Instant.parse("2026-11-01T10:00:00Z");

    private final TestingClock clock = new TestingClock();
    private final SlowFollowers slowFollowers = new SlowFollowers(Duration.ofSeconds(1), clock);

    TestSlowFollowers() {
        clock.toAdvanceableTime(START);
    }

    private Instant now() {
        return clock.now().asInstant();
    }

    @Test
    void aDroppedFollowerIsHeldOffForTheInitialHoldOff() {
        slowFollowers.dropped("b");

        assertThat(slowFollowers.heldOffUntil("b")).contains(START.plusSeconds(1));
        clock.advance(Duration.ofSeconds(1));
        assertThat(slowFollowers.heldOffUntil("b")).isEmpty();
        assertThat(slowFollowers.heldOffUntil("c")).as("never dropped").isEmpty();
    }

    @Test
    void eachDropDoublesTheHoldOffUpToAMinute() {
        long[] expectedSeconds = {1, 2, 4, 8, 16, 32, 60, 60};
        for (long seconds : expectedSeconds) {
            Instant droppedAt = now();
            slowFollowers.dropped("b");
            assertThat(slowFollowers.heldOffUntil("b")).contains(droppedAt.plusSeconds(seconds));
            clock.advance(Duration.ofSeconds(seconds));
        }
    }

    @Test
    void theHoldOffStartsOverOnceTheFollowerStayedInSyncAMinute() {
        slowFollowers.dropped("b");
        clock.advance(Duration.ofSeconds(1));
        slowFollowers.dropped("b");
        clock.advance(Duration.ofSeconds(2));
        slowFollowers.backInSync("b");
        clock.advance(Duration.ofMinutes(1));

        slowFollowers.dropped("b");

        assertThat(slowFollowers.heldOffUntil("b")).contains(now().plusSeconds(1));
    }

    @Test
    void aFollowerDroppedAgainWithinAMinuteInSyncKeepsDoubling() {
        slowFollowers.dropped("b");
        clock.advance(Duration.ofSeconds(1));
        slowFollowers.backInSync("b");
        clock.advance(Duration.ofSeconds(59));

        slowFollowers.dropped("b");

        assertThat(slowFollowers.heldOffUntil("b")).contains(now().plusSeconds(2));
    }

    @Test
    void onlyTheFollowersNotBackInSyncAreListed() {
        slowFollowers.dropped("c");
        slowFollowers.dropped("b");
        slowFollowers.backInSync("c");
        clock.advance(Duration.ofSeconds(1));

        assertThat(slowFollowers.notBackInSync()).singleElement().satisfies(dropped -> {
            assertThat(dropped.getNodeId()).isEqualTo("b");
            assertThat(dropped.getDroppedAt()).isEqualTo(START);
            assertThat(dropped.getHeldOffUntil()).as("may connect now").isNull();
        });
        assertThat(slowFollowers.isNotBackInSync("b")).isTrue();
        assertThat(slowFollowers.isNotBackInSync("c")).isFalse();
    }

    @Test
    void aZeroHoldOffNeverHoldsAFollowerOff() {
        SlowFollowers neverHolding = new SlowFollowers(Duration.ZERO, clock);

        neverHolding.dropped("b");
        neverHolding.dropped("b");

        assertThat(neverHolding.heldOffUntil("b")).isEmpty();
        assertThat(neverHolding.notBackInSync()).extracting(HaStatus.DroppedFollower::getNodeId).containsExactly("b");
    }
}
