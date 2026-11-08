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
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.ha.follower.RecordingEngine;
import org.lolaf.staffix.ha.follower.RecordingEngine.RecordingStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestPromotionSeqNumJumps {

    private static final FixSessionSettings LATE = RecordingEngine.session("late", FixSessionType.ACCEPTOR);

    private final PromotionSeqNumJumps jumps = new PromotionSeqNumJumps(100);
    private final RecordingStore store = new RecordingStore();

    @Test
    void aSessionStartedWhileNotLeadingIsNotMovedAhead() {
        jumps.jumpOnStart(LATE, store);

        assertThat(store.writes).isEmpty();
    }

    @Test
    void aSessionStartedAfterThePromotionIsMovedAheadOnce() {
        jumps.promoted(List.of(LATE));

        jumps.jumpOnStart(LATE, store);
        jumps.jumpOnStart(LATE, store);

        assertThat(store.writes).containsExactly("outgoing 101");
    }

    @Test
    void aSessionThatJumpedWithTheOthersIsNotMovedAheadAgain() {
        jumps.promoted(List.of(LATE));
        jumps.jumped(LATE);

        jumps.jumpOnStart(LATE, store);

        assertThat(store.writes).isEmpty();
    }

    @Test
    void aNewPromotionMovesTheSessionAheadAgain() {
        jumps.promoted(List.of(LATE));
        jumps.jumpOnStart(LATE, store);
        jumps.demoted();
        jumps.jumpOnStart(LATE, store);

        jumps.promoted(List.of(LATE));
        jumps.jumpOnStart(LATE, store);

        assertThat(store.writes).containsExactly("outgoing 101", "outgoing 101");
    }

    @Test
    void aSessionCreatedUnderThisLeadershipStartsAtItsFirstSeqNum() {
        jumps.promoted(List.of());

        jumps.jumpOnStart(LATE, store);
        jumps.jumpOnStart(LATE, store);

        assertThat(store.writes).isEmpty();
    }

    @Test
    void aSessionNotConfiguredAtThePromotionOverAStoreAlreadyUsedIsMovedAhead() {
        RecordingStore used = new RecordingStore() {
            @Override
            public long getOutgoingSeqNum() {
                return 50;
            }
        };
        jumps.promoted(List.of());

        jumps.jumpOnStart(LATE, used);

        assertThat(used.writes).containsExactly("outgoing 150");
    }

    @Test
    void aSessionCreatedUnderAnEarlierLeadershipIsMovedAheadAtTheNext() {
        jumps.promoted(List.of());
        jumps.jumpOnStart(LATE, store);
        jumps.demoted();

        jumps.promoted(List.of(LATE));
        jumps.jumpOnStart(LATE, store);

        assertThat(store.writes).containsExactly("outgoing 101");
    }

    @Test
    void aSessionMarkedWhileNotLeadingStillJumpsAtTheNextPromotion() {
        jumps.jumped(LATE);
        jumps.promoted(List.of(LATE));

        jumps.jumpOnStart(LATE, store);

        assertThat(store.writes).containsExactly("outgoing 101");
    }
}
