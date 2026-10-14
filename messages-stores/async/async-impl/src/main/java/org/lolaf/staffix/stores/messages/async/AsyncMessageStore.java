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
package org.lolaf.staffix.stores.messages.async;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.stores.core.async.*;
import org.lolaf.staffix.stores.core.async.QueueSizeEvaluator;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
class AsyncMessageStore extends Startable.SimpleStartable<FixMessagesStore.FixSessionMessagesStore> implements FixMessagesStore.FixSessionMessagesStore {

    private final FixMessagesStore.FixSessionMessagesStore wrappedStore;
    private final FixMessagesStore.BatchingFixSessionMessagesStore batchingWrappedStore;
    private final FixSessionId fixSessionId;
    private final AsyncMessagesStoreSettings asyncMessagesStoreSettings;
    private final AsyncStoreThreads<AsyncStoreEvent> asyncStoreThreads;
    private final Consumer<AsyncStoreEvent> eventsConsumer;
    private final Consumer<AsyncStoreEvent[]> batchingEventsConsumer;
    private final AsyncStoreEventSerde asyncStoreEventSerde;
    private final PersistentQueue<AsyncStoreEvent> messagesQueue;
    @Getter
    private final Probe queueSizeProbe;
    private final UnderlyingResourceWatchContext<AsyncStoreEvent> underlyingResourceWatchContext;
    private final FixMessagesStore.BatchingFixSessionMessagesStore.SentFixMessage[] batchedSentFixMessages;
    private final AtomicLong outgoingSequenceNumber;
    private final AtomicLong incomingSequenceNumber;
    private final AtomicLong processedEvents;

    AsyncMessageStore(FixMessagesStore.FixSessionMessagesStore wrappedStore, FixSessionId fixSessionId,
                      AsyncMessagesStoreSettings asyncMessagesStoreSettings, AsyncStoreThreads<AsyncStoreEvent> asyncStoreThreads,
                      AsyncEventInstanceProvider<AsyncStoreEvent> asyncEventInstanceProvider) {
        this.wrappedStore = wrappedStore;
        this.batchingWrappedStore = wrappedStore instanceof FixMessagesStore.BatchingFixSessionMessagesStore
                ? (FixMessagesStore.BatchingFixSessionMessagesStore) wrappedStore : null;
        this.fixSessionId = fixSessionId;
        this.asyncMessagesStoreSettings = asyncMessagesStoreSettings;
        this.asyncStoreThreads = asyncStoreThreads;
        this.eventsConsumer = this::onAsyncStoreEvent;
        this.batchingEventsConsumer = this::onAsyncStoreEvents;
        this.asyncStoreEventSerde = new AsyncStoreEventSerde(asyncEventInstanceProvider);
        this.messagesQueue = new PersistentQueue<>(fixSessionId);
        this.queueSizeProbe = QueueSizeEvaluator.probe("async-store-queue", "Messages waiting in the async store queue to be persisted",
                messagesQueue, asyncMessagesStoreSettings.getAsyncStoreSettings().getQueueSizeThresholdsSettings());
        this.batchedSentFixMessages = isBatchingEnabled()
                ? new FixMessagesStore.BatchingFixSessionMessagesStore.SentFixMessage[asyncMessagesStoreSettings.getAsyncStoreSettings().getEventsBatching()]
                : null;
        this.underlyingResourceWatchContext = new UnderlyingResourceWatchContext<>(this::replayAsyncStoreEvents,
                this::replayAsyncStoreEvent, wrappedStore::isUnderlyingStorageResourceAvailable, wrappedStore.getClass().getSimpleName(),
                asyncMessagesStoreSettings.getUnderlyingStoreResourceWatchTaskCheckDelay(), () -> unregisterEventsHandlingFromAsyncLoggersThreads(Deadline.immediate()),
                this::onUnderlyingResourceBackUp,
                "async-store-resource", "Availability of the store the async store persists to");
        outgoingSequenceNumber = new AtomicLong(1);
        incomingSequenceNumber = new AtomicLong(1);
        processedEvents = new AtomicLong();
    }

    Probe getResourceDownProbe() {
        return underlyingResourceWatchContext.getResourceDownProbe();
    }

    private void onAsyncStoreEvent(AsyncStoreEvent event) {
        try {
            if (!writeEvent(event)) {
                keepForReplay(null, event, 1);
            }
        } finally {
            event.release();
        }
    }

    private void onAsyncStoreEvents(AsyncStoreEvent[] asyncStoreEvents) {
        try {
            if (!writeEvents(asyncStoreEvents)) {
                keepForReplay(asyncStoreEvents, null, batchSize(asyncStoreEvents));
            }
        } finally {
            release(asyncStoreEvents);
        }
    }

    /**
     * Even when the resource reports up: a write can fail on a connection that died in an outage just ended.
     */
    private void keepForReplay(AsyncStoreEvent[] asyncStoreEvents, AsyncStoreEvent asyncStoreEvent, int eventsCount) {
        if (isStarted() && hasInactiveUnderlyingResourceWatchDog()) {
            startUnderlyingResourceWatchdog(asyncStoreEvents, asyncStoreEvent);
        } else {
            lost(eventsCount, "stopped or already replaying");
        }
    }

    private boolean replayAsyncStoreEvent(AsyncStoreEvent event) {
        return writeEvent(event) || dropUnlessResourceDown(1);
    }

    private boolean replayAsyncStoreEvents(AsyncStoreEvent[] asyncStoreEvents) {
        if (writeEvents(asyncStoreEvents) || dropUnlessResourceDown(batchSize(asyncStoreEvents))) {
            release(asyncStoreEvents);
            return true;
        }
        return false;
    }

    private boolean dropUnlessResourceDown(int eventsCount) {
        if (!wrappedStore.isUnderlyingStorageResourceAvailable()) {
            return false;
        }
        lost(eventsCount, "the replay failed with the resource up");
        return true;
    }

    private void lost(int eventsCount, String reason) {
        processedEvents.addAndGet(eventsCount);
        log.error("{} messages lost for FIX session {}, {}", eventsCount, fixSessionId, reason);
    }

    private boolean writeEvent(AsyncStoreEvent event) {
        try {
            switch (event.getStoreEvent()) {
                case STORE_MESSAGE:
                    wrappedStore.storeMessageSent(event.getSequenceNumber(), event.getMessage());
                    break;
                case STORE_INCOMING_SEQ_NUM:
                    wrappedStore.storeNextIncomingSeqNum(event.getSequenceNumber());
                    break;
                case STORE_OUTGOING_SEQ_NUM:
                    wrappedStore.storeNextOutgoingSeqNum(event.getSequenceNumber());
                    break;
                case RESET_SEQ_NUM:
                    wrappedStore.resetSequenceNumbers();
                    break;
            }
            processedEvents.incrementAndGet();
            return true;
        } catch (Exception ex) {
            log.error("Failed to store a message for FIX session {}", fixSessionId, ex);
            return false;
        }
    }

    private boolean writeEvents(AsyncStoreEvent[] asyncStoreEvents) {
        try {
            int batchSize = 0;
            int eventsCount = 0;
            long storeOutgoingSeqNum = -1;
            long storeIncomingSeqNum = -1;
            for (AsyncStoreEvent e : asyncStoreEvents) {
                if (e == null) {
                    break;
                }
                batchSize++;
                switch (e.getStoreEvent()) {
                    case STORE_MESSAGE:
                        batchedSentFixMessages[eventsCount++] = e;
                        storeOutgoingSeqNum = e.getSequenceNumber() + 1;
                        break;
                    case RESET_SEQ_NUM:
                        wrappedStore.resetSequenceNumbers();
                        break;
                    case STORE_INCOMING_SEQ_NUM:
                        storeIncomingSeqNum = e.getSequenceNumber();
                        break;
                    case STORE_OUTGOING_SEQ_NUM:
                        storeOutgoingSeqNum = e.getSequenceNumber();
                        break;
                }
            }
            if (eventsCount > 0) {
                batchingWrappedStore.storeSentFixMessages(batchedSentFixMessages, eventsCount);
            }
            if (storeOutgoingSeqNum != -1) {
                batchingWrappedStore.storeNextOutgoingSeqNum(storeOutgoingSeqNum);
            }
            if (storeIncomingSeqNum != -1) {
                batchingWrappedStore.storeNextIncomingSeqNum(storeIncomingSeqNum);
            }
            processedEvents.addAndGet(batchSize);
            return true;
        } catch (Exception ex) {
            log.error("Failed to store a batch of messages for FIX session {}", fixSessionId, ex);
            return false;
        }
    }

    private static int batchSize(AsyncStoreEvent[] asyncStoreEvents) {
        int batchSize = 0;
        while (batchSize < asyncStoreEvents.length && asyncStoreEvents[batchSize] != null) {
            batchSize++;
        }
        return batchSize;
    }

    private static void release(AsyncStoreEvent[] asyncStoreEvents) {
        for (AsyncStoreEvent e : asyncStoreEvents) {
            if (e == null) {
                break;
            }
            e.release();
        }
    }

    @Override
    protected void startMe() throws StartStopException {
        if (isBatchingEnabled() && batchingWrappedStore == null) {
            throw new IllegalArgumentException("AsyncMessageStore messages batching enabled but target store "
                    + wrappedStore.getClass().getName() + " does not implement interface " + FixMessagesStore.BatchingFixSessionMessagesStore.class.getName());
        }
        // the queue restarts its offers count, from what a previous run left unprocessed
        processedEvents.set(0);
        messagesQueue.start(asyncMessagesStoreSettings.getAsyncStoreSettings(), "fix-messages");
        if (wrappedStore.isUnderlyingStorageResourceAvailable()) {
            wrappedStore.start();
            registerEventsHandlingFromAsyncLoggersThreads();
            if (asyncMessagesStoreSettings.getFlushPendingMessagesOnStartupDelay() != null) {
                flushMessagesIfNeeded(Deadline.of(asyncMessagesStoreSettings.getFlushPendingMessagesOnStartupDelay()));
            }
            failStartIfWritesArePending();
            incomingSequenceNumber.set(wrappedStore.getIncomingSeqNum());
            outgoingSequenceNumber.set(wrappedStore.getOutgoingSeqNum());
        } else {
            throw new StartStopException("Underlying messages storage resource '" + wrappedStore.getUnderlyingStorageResourceDescription() + "' is not available for FIX session " + fixSessionId);
        }
    }

    private void failStartIfWritesArePending() throws StartStopException {
        long pendingWrites = pendingWrites();
        if (pendingWrites > 0) {
            stopMe(Deadline.immediate());
            throw new StartStopException("Async messages store for FIX session " + fixSessionId + " still has " + pendingWrites
                    + " writes pending from a previous run, refusing to start with stale sequence numbers");
        }
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        flushMessagesIfNeeded(stopDeadline);
        // unregistering first lets a write that fails meanwhile start its watchdog before it is stopped below
        if (!unregisterEventsHandlingFromAsyncLoggersThreads(stopDeadline)) {
            log.warn("Async messages store for FIX session {} stops while its reader thread is still writing to it, a write it fails will be lost", fixSessionId);
        }
        if (underlyingResourceWatchContext.isStarted()) {
            log.warn("AsyncMessageStore shutdown with an underlying resource state active watchdog for FIX session {}", fixSessionId);
            underlyingResourceWatchContext.stop(stopDeadline);
        }

        wrappedStore.stop(stopDeadline);
        messagesQueue.stop();
        log.info("Async messages store for FIX session {} stopped", fixSessionId);
    }

    private boolean unregisterEventsHandlingFromAsyncLoggersThreads(Deadline deadline) {
        return isBatchingEnabled()
                ? asyncStoreThreads.unregisterBatching(batchingEventsConsumer, deadline)
                : asyncStoreThreads.unregister(eventsConsumer, deadline);
    }

    private void onUnderlyingResourceBackUp() {
        if (isStarted()) {
            registerEventsHandlingFromAsyncLoggersThreads();
        }
    }

    private void flushMessagesIfNeeded(Deadline flushDeadline) {
        if (!flushDeadline.isImmediate() && pendingWrites() > 0) {
            Duration deadlineDuration = flushDeadline.getRemainingTime();
            log.info("Waiting up to {} to flush {} messages for FIX session {}", deadlineDuration, pendingWrites(), fixSessionId);
            if (flushDeadline.waitAsLongAs(() -> pendingWrites() > 0)) {
                log.info("All messages flushed for FIX session {}", fixSessionId);
            } else {
                log.warn("Unable to flush all messages for FIX session {} within {}, remaining {}", fixSessionId, deadlineDuration, pendingWrites());
            }
        }
    }

    private void startUnderlyingResourceWatchdog(AsyncStoreEvent[] asyncEventsToReplay, AsyncStoreEvent asyncEventToReplay) {
        if (underlyingResourceWatchContext.isStarted()) {
            log.error("AsyncMessageStore for FIX session {} underlying resource state watchdog is already existing abnormal situation", fixSessionId);
            return;
        }
        log.info("Starting AsyncMessageStore underlying resource state watchdog for FIX session {}", fixSessionId);
        underlyingResourceWatchContext.startWatchDog(asyncEventsToReplay, asyncEventToReplay, wrappedStore.getUnderlyingStorageResourceDescription());
    }

    private void registerEventsHandlingFromAsyncLoggersThreads() {
        if (isBatchingEnabled()) {
            asyncStoreThreads.registerBatching(messagesQueue, batchingEventsConsumer, asyncStoreEventSerde, AsyncStoreEvent.class);
        } else {
            asyncStoreThreads.register(messagesQueue, eventsConsumer, asyncStoreEventSerde);
        }
    }

    private boolean isBatchingEnabled() {
        return asyncMessagesStoreSettings.getAsyncStoreSettings().getEventsBatching() > 0;
    }

    boolean hasInactiveUnderlyingResourceWatchDog() {
        return !underlyingResourceWatchContext.isStarted();
    }

    long getQueueOffersCount() {
        return messagesQueue.offersCount();
    }

    long getQueuePollsCount() {
        return messagesQueue.pollsCount();
    }

    boolean hasEmptyQueue() {
        return messagesQueue.isEmpty();
    }

    @Override
    public boolean filter(MessageType messageType, ByteBuffer message) {
        return wrappedStore.filter(messageType, message);
    }

    @Override
    public void resetSequenceNumbers() {
        outgoingSequenceNumber.set(1);
        incomingSequenceNumber.set(1);
        messagesQueue.offer(asyncStoreEventSerde, asyncStoreEventSerde.resetSeqNum());
    }

    @Override
    public long getIncomingSeqNum() {
        // work in memory as we may have delay to store storeNextIncomingSeqNum, which may lead to wrong false sequences detection
        return incomingSequenceNumber.get();
    }

    @Override
    public long getOutgoingSeqNum() {
        // work in memory as we may have delay to store storeOutgoingSeqNum, which may lead to wrong false sequences detection
        return outgoingSequenceNumber.get();
    }

    @Override
    public long getNextOutgoingSeqNum() {
        return outgoingSequenceNumber.getAndIncrement();
    }

    @Override
    public void storeNextIncomingSeqNum(long nextIncomingSeqNum) {
        this.incomingSequenceNumber.set(nextIncomingSeqNum);
        messagesQueue.offer(asyncStoreEventSerde, asyncStoreEventSerde.storeSeqNum(FixMessagesStore.StoreEventType.STORE_INCOMING_SEQ_NUM, nextIncomingSeqNum));
    }

    @Override
    public void storeNextOutgoingSeqNum(long nextOutgoingSeqNum) {
        outgoingSequenceNumber.set(nextOutgoingSeqNum);
        messagesQueue.offer(asyncStoreEventSerde, asyncStoreEventSerde.storeSeqNum(FixMessagesStore.StoreEventType.STORE_OUTGOING_SEQ_NUM, nextOutgoingSeqNum));
    }

    @Override
    public void storeMessageSent(long outgoingSeqNum, ByteBuffer message) {
        outgoingSequenceNumber.set(outgoingSeqNum + 1);
        messagesQueue.offer(asyncStoreEventSerde, asyncStoreEventSerde.storeMessage(outgoingSeqNum, message));
    }

    /**
     * Throws a {@link StoreException} rather than read a wrapped store that is missing writes: a resend answered from
     * it would gap fill the missing messages, and the peer would never see them.
     */
    @Override
    public void find(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) {
        awaitPendingWritesBeforeFind();
        wrappedStore.find(startSequenceNumber, stopSequenceNumber, consumer);
    }

    private void awaitPendingWritesBeforeFind() {
        failFindIfUnderlyingStoreIsDown();
        Duration timeout = asyncMessagesStoreSettings.getFindWaitForEmptyQueueTimeout();
        if (timeout == null || pendingWrites() == 0) {
            return;
        }
        boolean written = Deadline.of(timeout).waitAsLongAs(() -> pendingWrites() > 0 && hasInactiveUnderlyingResourceWatchDog());
        failFindIfUnderlyingStoreIsDown();
        if (!written) {
            throw new StoreException("Async messages store for FIX session " + fixSessionId + " still has " + pendingWrites()
                    + " writes pending after waiting " + timeout + ", refusing to read an incomplete store");
        }
    }

    private void failFindIfUnderlyingStoreIsDown() {
        if (!hasInactiveUnderlyingResourceWatchDog()) {
            throw new StoreException("Underlying messages storage resource '" + wrappedStore.getUnderlyingStorageResourceDescription()
                    + "' is down for FIX session " + fixSessionId + ", refusing to read an incomplete store");
        }
    }

    long pendingWrites() {
        return messagesQueue.offersCount() - processedEvents.get();
    }
}
