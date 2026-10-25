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

/**
 * Wraps a session's message store so a storage failure is reported rather than thrown into the session, and raises
 * the session's {@code messages-store} alert, so every store has one without implementing it.
 *
 * <p>A store that is down is a serious condition but not necessarily a fatal one; ending the session would
 * guarantee the outage becomes an outage for the counterparty too. Its message writes are skipped while its resource
 * is down, so the session is not blocked on each one: they are not stored either way. Sequence number writes always
 * reach the store, which updates its in-memory counters before persisting them, and the session reads those back.
 */
@Slf4j
public class FailSafeFixSessionMessagesStore implements FixMessagesStore.FixSessionMessagesStore {

    static final String PROBE_NAME = "messages-store";

    private final FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore;
    private final FixSessionId fixSessionId;
    private final RaisedAlertEvaluator alertEvaluator;
    private final UnderlyingResourceBreaker breaker;
    @Getter
    private final Probe probe;
    private final Object alertLock;
    private volatile boolean failing;
    private volatile String lastFailure;

    public FailSafeFixSessionMessagesStore(FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore, FixSessionId fixSessionId,
                                           FixMessagesStoreSettings settings, UnderlyingResourceChecker checker) {
        this.fixSessionMessagesStore = fixSessionMessagesStore;
        this.fixSessionId = fixSessionId;
        this.alertEvaluator = new RaisedAlertEvaluator();
        this.breaker = UnderlyingResourceBreaker.of("Messages store of FIX session " + fixSessionId,
                fixSessionMessagesStore::isUnderlyingStorageResourceAvailable,
                fixSessionMessagesStore::getUnderlyingStorageResourceDescription, settings.getResourceCheckInterval(),
                checker, this::raiseResourceDown);
        this.probe = Probe.builder().name(PROBE_NAME).description("Messages store")
                .evaluator(alertEvaluator.withClearDelay(settings.getAlertClearDelay())).onlyWhileActive(false).build();
        this.alertLock = new Object();
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
        breaker.stop();
        return fixSessionMessagesStore.stop(stopDeadline);
    }

    @Override
    public FixMessagesStore.FixSessionMessagesStore start() throws StartStopException {
        return fixSessionMessagesStore.start();
    }

    @Override
    public boolean isStarted() {
        return fixSessionMessagesStore.isStarted();
    }

    private boolean skipped() {
        if (breaker.isOpen()) {
            breaker.callSkipped();
            return true;
        }
        return false;
    }

    private void succeeded() {
        if (failing) {
            synchronized (alertLock) {
                if (failing) {
                    failing = false;
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
        breaker.callFailed();
    }

    private void raiseResourceDown() {
        raise(Alert.major("'" + getUnderlyingStorageResourceDescription() + "' is down, sent messages are not stored; "
                + lastFailure));
    }

    /**
     * The only place an alert is raised, so that {@code failing} always says whether one is: the breaker raises from
     * its own thread while a successful call may be clearing.
     */
    private void raise(Alert alert) {
        synchronized (alertLock) {
            failing = true;
            alertEvaluator.raise(alert);
        }
    }
}
