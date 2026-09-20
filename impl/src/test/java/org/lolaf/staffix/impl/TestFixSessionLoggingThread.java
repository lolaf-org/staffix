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
package org.lolaf.staffix.impl;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.impl.session.FixSessionImpl;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What {@link org.lolaf.staffix.api.logging.FixMessagesLogger.Logger} promises: a session calls its logger from one
 * thread at a time, the IO thread of its connection, so an implementation needs no locking. A logger cannot be
 * asked which thread it should expect, so these assert on the thread that actually called it.
 */
class TestFixSessionLoggingThread extends AbstractFixTests {

    @Test
    void testAnEventRaisedOffTheIOThreadIsLoggedByIt() {
        logonClient();
        FixSessionImpl fixSession = getFixSessionImpl(ConnectorType.INITIATOR);
        String ioThreadName = ioThreadNameOf(fixSession);
        assertThat(Thread.currentThread().getName())
                .as("the test has to be on another thread for this to prove anything")
                .isNotEqualTo(ioThreadName);

        initiatorLogger.clear();
        fixSession.logEvent("raised from %s", "a test thread");

        await().untilAsserted(() -> assertThat(initiatorLogger.getEvents()).contains("raised from a test thread"));
        assertThat(initiatorLogger.getCallingThreads())
                .as("a connected session logs from its IO thread, whoever raised the event")
                .containsOnly(ioThreadName);
    }

    @Test
    void testAnEventRaisedOnADisconnectedSessionIsLoggedByItsCaller() {
        // the documented exception: with no connection there is no IO thread to hand the event to, and nothing else
        // is logging, that being what having no connection means
        logonClient();
        FixSessionImpl fixSession = getFixSessionImpl(ConnectorType.INITIATOR);
        fixSession.logoutPermanently("stopping the session for the test");
        await().untilAsserted(() -> assertThat(fixSession.isConnected()).isFalse());

        initiatorLogger.clear();
        fixSession.logEvent("raised while the session is down");

        assertThat(initiatorLogger.getEvents()).contains("raised while the session is down");
        assertThat(initiatorLogger.getCallingThreads()).contains(Thread.currentThread().getName());
    }

    /**
     * Asks the session to run a task, which only its IO thread does, and reports the thread that ran it.
     */
    private String ioThreadNameOf(FixSessionImpl fixSession) {
        AtomicReference<String> ioThreadName = new AtomicReference<>();
        fixSession.processTask(() -> ioThreadName.set(Thread.currentThread().getName()));
        await().untilAsserted(() -> assertThat(ioThreadName.get()).isNotNull());
        return ioThreadName.get();
    }
}
