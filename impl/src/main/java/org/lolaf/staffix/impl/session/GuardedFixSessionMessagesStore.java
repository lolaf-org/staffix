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

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.RaisedAlertEvaluator;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Wraps a session's message store so a storage failure is reported rather than thrown into the session, and raises
 * the session's {@code messages-store} alert, so every store has one without implementing it.
 *
 * <p>A store that is down is a serious condition but not necessarily a fatal one; ending the session would
 * guarantee the outage becomes an outage for the counterparty too. Its message writes are skipped while its resource
 * is down, so the session is not blocked on each one: they are not stored either way. Sequence number writes always
 * reach the store, which updates its in-memory counters before persisting them, and the session reads those back.
 *
 * <p>It also keeps {@link #find} and the store's lifecycle apart, whatever the store: resends and replication read on
 * threads of their own, and a store stopped under a read may free what the read is using. A find is refused unless the
 * store is started, and a stop waits for the finds in progress, past its deadline if it must.
 */
@Slf4j
public class GuardedFixSessionMessagesStore implements FixMessagesStore.FixSessionMessagesStore {

    static final String PROBE_NAME = "messages-store";
    private static final long FIND_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private final FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore;
    private final FixSessionId fixSessionId;
    private final RaisedAlertEvaluator alertEvaluator;
    private final UnderlyingResourceBreaker underlyingResourceBreaker;
    @Getter
    private final Probe probe;
    private final AtomicInteger findsInProgress;
    private String lastFailure;
    private volatile boolean readable;

    public GuardedFixSessionMessagesStore(FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore, FixSessionId fixSessionId,
                                          FixMessagesStoreSettings settings, UnderlyingResourceChecker checker) {
        this.fixSessionMessagesStore = fixSessionMessagesStore;
        this.fixSessionId = fixSessionId;
        this.alertEvaluator = new RaisedAlertEvaluator();
        this.underlyingResourceBreaker = UnderlyingResourceBreaker.of("Messages store of FIX session " + fixSessionId,
                fixSessionMessagesStore::isUnderlyingStorageResourceAvailable,
                fixSessionMessagesStore::getUnderlyingStorageResourceDescription, settings.getResourceCheckInterval(),
                checker, this::raiseResourceDown);
        this.probe = Probe.builder().name(PROBE_NAME).description("Messages store")
                .evaluator(alertEvaluator.withClearDelay(settings.getAlertClearDelay())).onlyWhileActive(false).build();
        this.findsInProgress = new AtomicInteger();
    }

    @Override
    public boolean isUnderlyingStorageResourceAvailable() {
        return fixSessionMessagesStore.isUnderlyingStorageResourceAvailable();
    }

    @Override
    public String getUnderlyingStorageResourceDescription() {
        return fixSessionMessagesStore.getUnderlyingStorageResourceDescription();
    }

    @Override
    public Optional<String> getStorageIdentity() {
        return fixSessionMessagesStore.getStorageIdentity();
    }

    @Override
    public boolean filter(MessageType messageType, ByteBuffer message) {
        return fixSessionMessagesStore.filter(messageType, message);
    }

    @Override
    public void resetSequenceNumbers() {
        try {
            fixSessionMessagesStore.resetSequenceNumbers();
            succeeded();
        } catch (Exception ex) {
            failed("resetSequenceNumbers()", ex);
        }
    }

    @Override
    public long getIncomingSeqNum() {
        return fixSessionMessagesStore.getIncomingSeqNum();
    }

    @Override
    public long getOutgoingSeqNum() {
        return fixSessionMessagesStore.getOutgoingSeqNum();
    }

    @Override
    public long getNextOutgoingSeqNum() {
        return fixSessionMessagesStore.getNextOutgoingSeqNum();
    }

    @Override
    public void storeNextIncomingSeqNum(long nextIncomingSeqNum) {
        try {
            fixSessionMessagesStore.storeNextIncomingSeqNum(nextIncomingSeqNum);
            succeeded();
        } catch (Exception ex) {
            failed("storeNextIncomingSeqNum()", ex);
        }
    }

    @Override
    public void storeNextOutgoingSeqNum(long nextOutgoingSeqNum) {
        try {
            fixSessionMessagesStore.storeNextOutgoingSeqNum(nextOutgoingSeqNum);
            succeeded();
        } catch (Exception ex) {
            failed("storeNextOutgoingSeqNum()", ex);
        }
    }

    @Override
    public void storeMessageSent(long outgoingSeqNum, ByteBuffer message) {
        if (skipped()) {
            return;
        }
        try {
            fixSessionMessagesStore.storeMessageSent(outgoingSeqNum, message);
            succeeded();
        } catch (Exception ex) {
            failed("storeMessageSent()", ex);
        }
    }

    @Override
    public void find(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) {
        if (!enterFind()) {
            throw new StoreException("Messages store of FIX session " + fixSessionId + " is not started");
        }
        try {
            findUnderlying(startSequenceNumber, stopSequenceNumber, consumer);
        } finally {
            findsInProgress.decrementAndGet();
        }
    }

    private boolean enterFind() {
        if (!readable) {
            return false;
        }
        findsInProgress.incrementAndGet();
        // a stop clears readable before it counts the finds, so either it sees this one or this one sees it
        if (!readable) {
            findsInProgress.decrementAndGet();
            return false;
        }
        return true;
    }

    private void findUnderlying(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) {
        if (skipped()) {
            throw new StoreException("Messages store '" + getUnderlyingStorageResourceDescription() + "' of FIX session "
                    + fixSessionId + " is down");
        }
        try {
            fixSessionMessagesStore.find(startSequenceNumber, stopSequenceNumber, consumer);
            succeeded();
        } catch (StoreException ex) {
            failed("find()", ex);
            throw ex;
        } catch (Exception ex) {
            failed("find()", ex);
            throw new StoreException("Failed to call find() on FixSessionMessagesStore " + fixSessionMessagesStore.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public FixMessagesStore.FixSessionMessagesStore stop(Deadline stopDeadline) throws StartStopException {
        readable = false;
        awaitFindsInProgress(stopDeadline);
        underlyingResourceBreaker.stop();
        return fixSessionMessagesStore.stop(stopDeadline);
    }

    private void awaitFindsInProgress(Deadline stopDeadline) {
        boolean warned = false;
        while (findsInProgress.get() > 0) {
            if (!warned && stopDeadline.isExpired()) {
                log.warn("Stopping the messages store of FIX session {} waits past its deadline for {} find() in progress",
                        fixSessionId, findsInProgress.get());
                warned = true;
            }
            LockSupport.parkNanos(FIND_POLL_NANOS);
        }
    }

    @Override
    public FixMessagesStore.FixSessionMessagesStore start() throws StartStopException {
        FixMessagesStore.FixSessionMessagesStore started = fixSessionMessagesStore.start();
        readable = true;
        return started;
    }

    @Override
    public boolean isStarted() {
        return fixSessionMessagesStore.isStarted();
    }

    private boolean skipped() {
        if (underlyingResourceBreaker.isOpen()) {
            underlyingResourceBreaker.callSkipped();
            return true;
        }
        return false;
    }

    private void succeeded() {
        if (alertEvaluator.isRaised()) {
            synchronized (alertEvaluator) {
                if (alertEvaluator.isRaised()) {
                    alertEvaluator.clear();
                }
            }
        }
    }

    private void failed(String call, Exception ex) {
        log.error("Failed to call {} on FixSessionMessagesStore {} for FIX session {}", call,
                fixSessionMessagesStore.getClass().getSimpleName(), fixSessionId, ex);
        lastFailure = call + " failed: " + ex;
        raise(Alert.warning(lastFailure));
        underlyingResourceBreaker.callFailed();
    }

    private void raiseResourceDown() {
        raise(Alert.major("'" + getUnderlyingStorageResourceDescription() + "' is down, sent messages are not stored; "
                + lastFailure));
    }

    /**
     * The only place an alert is raised, under the lock a successful call clears it with: the breaker raises from its
     * own thread, and a clear checked before that raise must not drop it.
     */
    private void raise(Alert alert) {
        synchronized (alertEvaluator) {
            alertEvaluator.raise(alert);
        }
    }
}
