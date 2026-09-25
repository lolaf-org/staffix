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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.stores.core.async.*;

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
    private final UnderlyingResourceWatchContext<AsyncStoreEvent> underlyingResourceWatchContext;
    private final FixMessagesStore.BatchingFixSessionMessagesStore.SentFixMessage[] batchedSentFixMessages;
    private final StoreUnderlyingResourceStateListener storeUnderlyingResourceStateListener;
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
        this.batchedSentFixMessages = isBatchingEnabled()
                ? new FixMessagesStore.BatchingFixSessionMessagesStore.SentFixMessage[asyncMessagesStoreSettings.getAsyncStoreSettings().getEventsBatching()]
                : null;
        this.storeUnderlyingResourceStateListener = new StoreUnderlyingResourceStateListener.FailSafeStoreUnderlyingResourceStateListener(
                asyncMessagesStoreSettings.getAsyncStoreSettings().getStoreUnderlyingResourceStateListener());
        this.underlyingResourceWatchContext = new UnderlyingResourceWatchContext<>(this::onAsyncStoreEvents,
                this::onAsyncStoreEvent, wrappedStore::isUnderlyingStorageResourceAvailable, wrappedStore.getClass().getSimpleName(),
                asyncMessagesStoreSettings.getUnderlyingStoreResourceWatchTaskCheckDelay(), this::unregisterEventsHandlingFromAsyncLoggersThreads,
                this::registerEventsHandlingFromAsyncLoggersThreads, storeUnderlyingResourceStateListener, fixSessionId);
        outgoingSequenceNumber = new AtomicLong(1);
        incomingSequenceNumber = new AtomicLong(1);
        processedEvents = new AtomicLong();
    }

    private void onAsyncStoreEvent(AsyncStoreEvent event) {
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
        } catch (Exception ex) {
            if (!wrappedStore.isUnderlyingStorageResourceAvailable() && hasInactiveUnderlyingResourceWatchDog()) {
                log.error("Failed to process message storage for FIX session {}", fixSessionId, ex);
                startUnderlyingResourceWatchdog(null, event);
            } else {
                processedEvents.incrementAndGet();
                log.error("Failed to process message storage for FIX session {}, message is lost", fixSessionId, ex);
            }
        } finally {
            event.release();
        }
    }

    private void onAsyncStoreEvents(AsyncStoreEvent[] asyncStoreEvents) {
        boolean batchProcessed = true;
        try {
            int eventsCount = 0;
            long storeOutgoingSeqNum = -1;
            long storeIncomingSeqNum = -1;
            for (AsyncStoreEvent e : asyncStoreEvents) {
                if (e == null) {
                    break;
                }
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
        } catch (Exception ex) {
            if (!batchingWrappedStore.isUnderlyingStorageResourceAvailable() && hasInactiveUnderlyingResourceWatchDog()) {
                log.error("Failed to process log message batching for FIX session {}", fixSessionId, ex);
                startUnderlyingResourceWatchdog(asyncStoreEvents, null);
                batchProcessed = false;
            } else {
                log.error("Failed to process log message batching for FIX session {}, messages are lost", fixSessionId, ex);
            }
        } finally {
            int batchSize = 0;
            for (AsyncStoreEvent e : asyncStoreEvents) {
                if (e == null) {
                    break;
                }
                batchSize++;
                e.release();
            }
            if (batchProcessed) {
                processedEvents.addAndGet(batchSize);
            }
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
            storeUnderlyingResourceStateListener.onStoreStateUp(fixSessionId, wrappedStore.getUnderlyingStorageResourceDescription());
            registerEventsHandlingFromAsyncLoggersThreads();
            if (asyncMessagesStoreSettings.getFlushPendingMessagesOnStartupDelay() != null) {
                flushMessagesIfNeeded(Deadline.of(asyncMessagesStoreSettings.getFlushPendingMessagesOnStartupDelay()));
            }
            incomingSequenceNumber.set(wrappedStore.getIncomingSeqNum());
            outgoingSequenceNumber.set(wrappedStore.getOutgoingSeqNum());
        } else {
            throw new StartStopException("Underlying messages storage resource '" + wrappedStore.getUnderlyingStorageResourceDescription() + "' is not available for FIX session " + fixSessionId);
        }
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        flushMessagesIfNeeded(stopDeadline);
        if (underlyingResourceWatchContext.isStarted()) {
            log.warn("AsyncMessageStore shutdown with an underlying resource state active watchdog for FIX session {}", fixSessionId);
            underlyingResourceWatchContext.stop(stopDeadline);
        }
        unregisterEventsHandlingFromAsyncLoggersThreads();

        wrappedStore.stop(stopDeadline);
        messagesQueue.stop();
        log.info("Async messages store for FIX session {} stopped", fixSessionId);
    }

    private void unregisterEventsHandlingFromAsyncLoggersThreads() {
        if (isBatchingEnabled()) {
            asyncStoreThreads.unregisterBatching(batchingEventsConsumer);
        } else {
            asyncStoreThreads.unregister(eventsConsumer);
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
            asyncStoreThreads.registerBatching(fixSessionId, messagesQueue, batchingEventsConsumer, asyncStoreEventSerde, AsyncStoreEvent.class);
        } else {
            asyncStoreThreads.register(fixSessionId, messagesQueue, eventsConsumer, asyncStoreEventSerde);
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
    public long getNextOutgoingSeqNum() throws StoreException {
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

    private long pendingWrites() {
        return messagesQueue.offersCount() - processedEvents.get();
    }
}
