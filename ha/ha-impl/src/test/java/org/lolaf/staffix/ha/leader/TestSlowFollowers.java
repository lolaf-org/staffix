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
        slowFollowers.dropped("b", 0);

        assertThat(slowFollowers.heldOffUntil("b", 0)).contains(START.plusSeconds(1));
        clock.advance(Duration.ofSeconds(1));
        assertThat(slowFollowers.heldOffUntil("b", 0)).isEmpty();
        assertThat(slowFollowers.heldOffUntil("c", 0)).as("never dropped").isEmpty();
    }

    @Test
    void eachDropDoublesTheHoldOffUpToAMinute() {
        long[] expectedSeconds = {1, 2, 4, 8, 16, 32, 60, 60};
        for (long seconds : expectedSeconds) {
            Instant droppedAt = now();
            slowFollowers.dropped("b", 0);
            assertThat(slowFollowers.heldOffUntil("b", 0)).contains(droppedAt.plusSeconds(seconds));
            clock.advance(Duration.ofSeconds(seconds));
        }
    }

    @Test
    void theHoldOffStartsOverOnceTheFollowerStayedInSyncAMinute() {
        slowFollowers.dropped("b", 0);
        clock.advance(Duration.ofSeconds(1));
        slowFollowers.dropped("b", 0);
        clock.advance(Duration.ofSeconds(2));
        slowFollowers.backInSync("b", 0);
        clock.advance(Duration.ofMinutes(1));

        slowFollowers.dropped("b", 0);

        assertThat(slowFollowers.heldOffUntil("b", 0)).contains(now().plusSeconds(1));
    }

    @Test
    void aFollowerDroppedAgainWithinAMinuteInSyncKeepsDoubling() {
        slowFollowers.dropped("b", 0);
        clock.advance(Duration.ofSeconds(1));
        slowFollowers.backInSync("b", 0);
        clock.advance(Duration.ofSeconds(59));

        slowFollowers.dropped("b", 0);

        assertThat(slowFollowers.heldOffUntil("b", 0)).contains(now().plusSeconds(2));
    }

    @Test
    void onlyTheFollowersNotBackInSyncAreListed() {
        slowFollowers.dropped("c", 0);
        slowFollowers.dropped("b", 0);
        slowFollowers.backInSync("c", 0);
        clock.advance(Duration.ofSeconds(1));

        assertThat(slowFollowers.notBackInSync()).singleElement().satisfies(dropped -> {
            assertThat(dropped.getNodeId()).isEqualTo("b");
            assertThat(dropped.getDroppedAt()).isEqualTo(START);
            assertThat(dropped.getHeldOffUntil()).as("may connect now").isNull();
        });
        assertThat(slowFollowers.isNotBackInSync("b", 0)).isTrue();
        assertThat(slowFollowers.isNotBackInSync("c", 0)).isFalse();
    }

    @Test
    void eachLinkOfAFollowerIsHeldOffOnItsOwn() {
        slowFollowers.dropped("b", 1);

        assertThat(slowFollowers.heldOffUntil("b", 1)).contains(START.plusSeconds(1));
        assertThat(slowFollowers.heldOffUntil("b", 0)).as("another link").isEmpty();
        assertThat(slowFollowers.notBackInSync()).singleElement().satisfies(dropped -> {
            assertThat(dropped.getNodeId()).isEqualTo("b");
            assertThat(dropped.getLink()).isEqualTo(1);
        });
    }

    @Test
    void aZeroHoldOffNeverHoldsAFollowerOff() {
        SlowFollowers neverHolding = new SlowFollowers(Duration.ZERO, clock);

        neverHolding.dropped("b", 0);
        neverHolding.dropped("b", 0);

        assertThat(neverHolding.heldOffUntil("b", 0)).isEmpty();
        assertThat(neverHolding.notBackInSync()).extracting(HaStatus.DroppedFollower::getNodeId).containsExactly("b");
    }
}
