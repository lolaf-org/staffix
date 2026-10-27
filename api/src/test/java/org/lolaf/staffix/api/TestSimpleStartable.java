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
package org.lolaf.staffix.api;

import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable.State;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSimpleStartable {

    private static final class Recording extends Startable.SimpleStartable<Recording> {

        final List<String> seen = new ArrayList<>();
        RuntimeException failStart;
        RuntimeException failStop;

        @Override
        protected void startMe() {
            seen.add("startMe " + getState() + " started=" + isStarted());
            if (failStart != null) {
                throw failStart;
            }
        }

        @Override
        protected void stopMe(Deadline stopDeadline) {
            seen.add("stopMe " + getState() + " started=" + isStarted());
            if (failStop != null) {
                throw failStop;
            }
        }
    }

    private final Recording component = new Recording();

    @Test
    void isStartedOnlyOnceStartMeReturnedAndNoLongerOnceStopping() {
        assertThat(component.getState()).isEqualTo(State.STOPPED);

        component.start();
        assertThat(component.getState()).isEqualTo(State.STARTED);
        assertThat(component.isStarted()).isTrue();

        component.stop(Deadline.unlimited());
        assertThat(component.getState()).isEqualTo(State.STOPPED);
        assertThat(component.seen).containsExactly("startMe STARTING started=false", "stopMe STOPPING started=false");
    }

    @Test
    void aSecondStartOrStopDoesNothing() {
        component.stop(Deadline.unlimited());
        component.start();
        component.start();
        component.stop(Deadline.unlimited());
        component.stop(Deadline.unlimited());

        assertThat(component.seen).hasSize(2);
    }

    @Test
    void aFailedStartLeavesItStoppedSoItCanBeRetried() {
        component.failStart = new IllegalStateException("no disk");

        assertThatThrownBy(component::start).hasMessage("no disk");
        assertThat(component.getState()).isEqualTo(State.STOPPED);

        component.failStart = null;
        component.start();
        assertThat(component.getState()).isEqualTo(State.STARTED);
    }

    @Test
    void aFailedStopStillEndsStopped() {
        component.start();
        component.failStop = new IllegalStateException("flush failed");

        assertThatThrownBy(() -> component.stop(Deadline.unlimited())).hasMessage("flush failed");
        assertThat(component.getState()).isEqualTo(State.STOPPED);
    }
}
