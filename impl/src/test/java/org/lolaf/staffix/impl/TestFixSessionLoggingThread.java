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
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.impl.session.FixSessionImpl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What {@link org.lolaf.staffix.api.logging.FixMessagesLogger.Logger} promises: a session calls its logger from one
 * thread at a time, the IO thread of its connection, so an implementation needs no locking. A logger cannot be
 * asked which thread it should expect, so these assert on the thread that actually called it.
 */
class TestFixSessionLoggingThread extends AbstractFixTests {

    private static final int SENDER_THREADS = 3;
    private static final int CONNECTION_CYCLES = 3;
    private static final Duration SENDERS_STOP_TIMEOUT = Duration.ofSeconds(10);

    private final List<RuntimeException> senderFailures = new CopyOnWriteArrayList<>();

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
    void testAnEventRaisedOnADisconnectedSessionIsLoggedByTheEnginesOfflineThread() {
        // a session with no connection has no IO thread, but it does have an owner, so the logger stays on one
        // thread rather than being written by whichever caller raised the event
        logonClient();
        FixSessionImpl fixSession = getFixSessionImpl(ConnectorType.INITIATOR);
        fixSession.logoutPermanently("stopping the session for the test");
        await().untilAsserted(() -> assertThat(fixSession.isConnected()).isFalse());

        initiatorLogger.clear();
        fixSession.logEvent("raised while the session is down");

        await().untilAsserted(() -> assertThat(initiatorLogger.getEvents()).contains("raised while the session is down"));
        assertThat(initiatorLogger.getCallingThreads())
                .isNotEmpty()
                .allMatch(threadName -> threadName.startsWith("staffix-offline-sessions-"));
    }

    /**
     * The seams above prove the hop exists; this drives the session hard enough for the threads to collide if any
     * path to the logger misses it. Application threads send throughout, and the connection is cut and brought
     * back underneath them, so each cycle has messages held and replayed, messages numbered while the session is
     * down, and a teardown racing sends already on their way.
     */
    @Test
    void testNoTwoThreadsAreEverInsideTheLoggerAtOnce() throws InterruptedException {
        logonClient();
        AtomicBoolean sending = new AtomicBoolean(true);
        List<Thread> senders = startSenders(sending);

        for (int cycle = 0; cycle < CONNECTION_CYCLES; cycle++) {
            fixInitiatorSession.disconnect("cycling the connection");
            await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isFalse());
            fixInitiatorSession.logon();
            await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());
        }

        sending.set(false);
        for (Thread sender : senders) {
            sender.join(SENDERS_STOP_TIMEOUT.toMillis());
        }

        assertThat(senderFailures).as("the senders must have run, or nothing was driven at all").isEmpty();
        assertThat(initiatorLogger.getOutgoingMessages().size())
                .as("the cycles must have had traffic under them")
                .isGreaterThan(SENDER_THREADS * CONNECTION_CYCLES);
        assertThat(initiatorLogger.getConcurrentCalls()).isEmpty();
        assertThat(acceptorLogger.getConcurrentCalls()).isEmpty();
    }

    private List<Thread> startSenders(AtomicBoolean sending) {
        List<Thread> senders = new ArrayList<>();
        for (int i = 0; i < SENDER_THREADS; i++) {
            int senderId = i;
            Thread sender = new Thread(() -> {
                try {
                    for (int message = 0; sending.get(); message++) {
                        EmailEncoder encoder = fixInitiatorSession.newEncoder(EmailEncoder.class);
                        encoder.begin().setSubject("sender-" + senderId + "-" + message);
                        fixInitiatorSession.send(encoder, null);
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    }
                } catch (RuntimeException failure) {
                    senderFailures.add(failure);
                }
            }, "test-sender-" + senderId);
            sender.setDaemon(true);
            senders.add(sender);
            sender.start();
        }
        return senders;
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
