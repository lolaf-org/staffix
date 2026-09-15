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
import org.lolaf.staffix.api.executor.MessageExecutor;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The message executors a session hands out, from the session's side: they belong to it, and they go when it does.
 */
class TestFixSessionMessageExecutors extends AbstractFixTests {

    @Test
    void twoSessionsNeverShareAnExecutorForTheSameRoutingKey() {
        logonClient();

        MessageExecutor<?, ?, ?, ?> initiatorExecutor = fixInitiatorSession.getMessageExecutor(RoutingNamespace.class, () -> 0);
        MessageExecutor<?, ?, ?, ?> acceptorExecutor = fixAcceptorSession.getMessageExecutor(RoutingNamespace.class, () -> 0);

        assertThat(initiatorExecutor).isNotNull();
        assertThat(acceptorExecutor).isNotNull().isNotSameAs(initiatorExecutor);
        assertThat(fixInitiatorSession.getMessageExecutor(RoutingNamespace.class, () -> 0))
                .as("the same routing key on the same session stays the same executor")
                .isSameAs(initiatorExecutor);
    }

    /**
     * The reason a session owns them: an executor thread runs application code that can reach the session, so it has to
     * be gone before the session closes its store and its logger - which is why {@code releaseResources} releases them
     * first, wherever it is called from.
     */
    @Test
    void stoppingASessionReleasesItsExecutors() {
        logonClient();
        MessageExecutor<String, Void, Void, Void> executor = fixInitiatorSession.getMessageExecutor(RoutingNamespace.class, () -> 0);
        executor.execute((message, p1, p2, p3) -> {
        }, "message", null, null, null);

        fixInitiator.stop();

        assertThatThrownBy(() -> executor.execute((message, p1, p2, p3) -> {
        }, "message", null, null, null))
                .as("an executor of a stopped session refuses work rather than queueing it on a thread that is going")
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The case only a session-level release covers: one session goes - its settings are removed from the store - while
     * its acceptor keeps running, so the connector's executors runtime is never stopped and nothing else would let go
     * of what that session held. Fails if {@code releaseResources} stops releasing them.
     */
    @Test
    void removingASessionReleasesItsExecutorsWhileItsAcceptorKeepsRunning() {
        logonClient();
        MessageExecutor<String, Void, Void, Void> executor = fixAcceptorSession.getMessageExecutor(RoutingNamespace.class, () -> 0);
        executor.execute((message, p1, p2, p3) -> {
        }, "message", null, null, null);

        FixSessionsSettingsStore store = acceptorFixEngine.getFixSessionsSettingsStores().get(0);
        store.remove(store.find(fixAcceptorSession.getFixSessionId(), FixSession.FixSessionType.ACCEPTOR).orElseThrow());

        await().untilAsserted(() -> assertThatThrownBy(() -> executor.execute((message, p1, p2, p3) -> {
        }, "message", null, null, null)).isInstanceOf(IllegalStateException.class));
        assertThat(fixAcceptor.isStarted()).as("the acceptor itself is untouched, only that session went").isTrue();
    }

    private static final class RoutingNamespace {
    }
}
