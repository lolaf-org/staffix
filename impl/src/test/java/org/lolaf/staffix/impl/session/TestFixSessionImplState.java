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
package org.lolaf.staffix.impl.session;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.session.FixSessionState;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestFixSessionImplState {

    private FixSessionImplState newState() {
        return new FixSessionImplState(mock(FixSessionImpl.class), false, FixSessionState.LOGGED_IN,
                mock(FixSessionScheduleManager.class));
    }

    @Test
    void testTaskRegisteredForLogoutRunsOnceLogoutIsProcessed() {
        FixSessionImplState state = newState();
        AtomicInteger runs = new AtomicInteger();

        state.runOnceLogoutProcessed(runs::incrementAndGet);

        assertThat(runs).hasValue(0);
        state.onLogoutProcessed(true);
        assertThat(runs).hasValue(1);
    }

    @Test
    void testTaskRegisteredForLogoutRunsOnDroppedConnectionToo() {
        // a logout that ends with the connection going away rather than with the peer's acknowledgement leaves the
        // session just as logged out, so whatever was waiting on it still has to happen
        FixSessionImplState state = newState();
        AtomicInteger runs = new AtomicInteger();

        state.runOnceLogoutProcessed(runs::incrementAndGet);
        state.onLogoutProcessed(false);

        assertThat(runs).hasValue(1);
    }

    @Test
    void testTaskRegisteredForLogoutIsAOneShot() {
        FixSessionImplState state = newState();
        AtomicInteger runs = new AtomicInteger();

        state.runOnceLogoutProcessed(runs::incrementAndGet);
        state.onLogoutProcessed(true);
        state.onLogoutProcessed(true);

        assertThat(runs).as("the next logout is not the one the task was registered for").hasValue(1);
    }

    @Test
    void testTaskRegisteredForLogoutIsDroppedByANewConnection() {
        // the logout it was waiting for never completed: the task belongs to the session that ended, and running it
        // against the connection that follows would apply it to a session that never asked for it
        FixSessionImplState state = newState();
        AtomicInteger runs = new AtomicInteger();

        state.runOnceLogoutProcessed(runs::incrementAndGet);
        state.onConnection();
        state.onLogoutProcessed(true);

        assertThat(runs).hasValue(0);
    }

    @Test
    void testSequenceResetOnNextLogonIsConsumedOnce() {
        FixSessionImplState state = newState();

        assertThat(state.consumeSequenceResetOnNextLogon()).isFalse();

        state.armSequenceResetOnNextLogon();

        assertThat(state.consumeSequenceResetOnNextLogon()).isTrue();
        assertThat(state.consumeSequenceResetOnNextLogon()).isFalse();
    }
}
