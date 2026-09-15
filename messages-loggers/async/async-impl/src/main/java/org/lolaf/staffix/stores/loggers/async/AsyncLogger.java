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
package org.lolaf.staffix.stores.loggers.async;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.stores.core.async.*;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

@Slf4j
class AsyncLogger extends Startable.SimpleStartable<FixMessagesLogger.Logger> implements FixMessagesLogger.Logger {

    private final FixMessagesLogger.Logger wrappedLogger;
    private final FixMessagesLogger.BatchingLogger batchingWrappedLogger;
    private final FixSessionId fixSessionId;
    private final AsyncMessagesLoggerSettings asyncMessagesLoggerSettings;
    private final AsyncStoreThreads<AsyncLogEvent> asyncLoggersThreads;
    private final Consumer<AsyncLogEvent> eventsConsumer;
    private final Consumer<AsyncLogEvent[]> batchingEventsConsumer;
    private final BiPredicate<MessageType, FixMessagesLogger.LogEventType> wrappedLoggerMessageFilter;
    private final FixMessagesLogger.BatchingLogger.LogEvent[] batchedMessages;
    private final AsyncLogEventSerde asyncLogEventSerde;
    private final PersistentQueue<AsyncLogEvent> messagesQueue;
    private final UnderlyingResourceWatchContext<AsyncLogEvent> underlyingResourceWatchContext;
    private final StoreUnderlyingResourceStateListener storeUnderlyingResourceStateListener;

    AsyncLogger(FixMessagesLogger.Logger wrappedLogger, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry,
                AsyncMessagesLoggerSettings asyncMessagesLoggerSettings, AsyncStoreThreads<AsyncLogEvent> asyncLoggersThreads,
                AsyncEventInstanceProvider<AsyncLogEvent> asyncEventInstanceProvider) {
        this.wrappedLogger = wrappedLogger;
        this.batchingWrappedLogger = wrappedLogger instanceof FixMessagesLogger.BatchingLogger ? (FixMessagesLogger.BatchingLogger) wrappedLogger : null;
        this.fixSessionId = fixSessionId;
        this.asyncMessagesLoggerSettings = asyncMessagesLoggerSettings;
        this.asyncLoggersThreads = asyncLoggersThreads;
        this.eventsConsumer = this::onAsyncLogEvent;
        this.batchingEventsConsumer = this::onAsyncLogEvents;
        this.wrappedLoggerMessageFilter = asyncMessagesLoggerSettings.getWrappedLoggerSettings().getMessageFilter();
        this.batchedMessages = isBatchingEnabled()
                ? new FixMessagesLogger.BatchingLogger.LogEvent[asyncMessagesLoggerSettings.getAsyncStoreSettings().getEventsBatching()]
                : null;
        this.asyncLogEventSerde = new AsyncLogEventSerde(messageTypeRegistry, asyncEventInstanceProvider);
        this.messagesQueue = new PersistentQueue<>(fixSessionId);
        this.storeUnderlyingResourceStateListener = new StoreUnderlyingResourceStateListener.FailSafeStoreUnderlyingResourceStateListener(
                asyncMessagesLoggerSettings.getAsyncStoreSettings().getStoreUnderlyingResourceStateListener());
        this.underlyingResourceWatchContext = new UnderlyingResourceWatchContext<>(this::onAsyncLogEvents,
                this::onAsyncLogEvent, wrappedLogger::isUnderlyingStorageResourceAvailable, wrappedLogger.getClass().getSimpleName(),
                asyncMessagesLoggerSettings.getUnderlyingLoggerResourceWatchTaskCheckDelay(), this::unregisterEventsHandlingFromAsyncLoggersThreads,
                this::registerEventsHandlingFromAsyncLoggersThreads, storeUnderlyingResourceStateListener, fixSessionId);
    }

    @Override
    public boolean isLoggingEvents() {
        return wrappedLogger.isLoggingEvents();
    }

    @Override
    public boolean isLoggingOutgoing() {
        return wrappedLogger.isLoggingOutgoing();
    }

    @Override
    public boolean isLoggingIncoming() {
        return wrappedLogger.isLoggingIncoming();
    }

    @Override
    protected void startMe() throws StartStopException {
        if (isBatchingEnabled() && batchingWrappedLogger == null) {
            throw new IllegalArgumentException("AsyncLogger messages batching enabled but target logger "
                    + wrappedLogger.getClass().getName() + " does not implement interface " + FixMessagesLogger.BatchingLogger.class.getName());
        }
        messagesQueue.start(asyncMessagesLoggerSettings.getAsyncStoreSettings(), "logs");
        wrappedLogger.start();
        if (wrappedLogger.isUnderlyingStorageResourceAvailable()) {
            storeUnderlyingResourceStateListener.onStoreStateUp(fixSessionId, wrappedLogger.getUnderlyingStorageResourceDescription());
            registerEventsHandlingFromAsyncLoggersThreads();
            flushMessageOnStartupIfNeeded();
        } else {
            startUnderlyingResourceWatchdog(null, null);
        }
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        if (!stopDeadline.isImmediate() && !messagesQueue.isEmpty()) {
            log.info("Waiting up to {} to flush {} logs for FIX session {}", stopDeadline.getRemainingTime(), messagesQueue.getSize(), fixSessionId);
            if (!stopDeadline.waitAsLongAs(() -> !messagesQueue.isEmpty())) {
                log.warn("Unable to flush AsyncLogger queue within given deadline for FIX session {}", fixSessionId);
            }
        }
        if (underlyingResourceWatchContext.isStarted()) {
            log.warn("AsyncLogger shutdown with an logger underlying resource state active watchdog for FIX session {}", fixSessionId);
            underlyingResourceWatchContext.stop(stopDeadline);
        }
        unregisterEventsHandlingFromAsyncLoggersThreads();

        wrappedLogger.stop(stopDeadline);
        messagesQueue.stop();
        log.info("Async logger store for FIX session {} stopped", fixSessionId);
    }

    private void unregisterEventsHandlingFromAsyncLoggersThreads() {
        if (isBatchingEnabled()) {
            asyncLoggersThreads.unregisterBatching(batchingEventsConsumer);
        } else {
            asyncLoggersThreads.unregister(eventsConsumer);
        }
    }

    private void flushMessageOnStartupIfNeeded() {
        Duration flushMessageDuration = asyncMessagesLoggerSettings.getFlushPendingMessagesOnStartupDelay();
        if (flushMessageDuration != null && !messagesQueue.isEmpty()) {
            log.info("Waiting up to {} to flush {} logs for FIX session {}", flushMessageDuration, messagesQueue.getSize(), fixSessionId);
            if (Deadline.of(flushMessageDuration).waitAsLongAs(() -> !messagesQueue.isEmpty())) {
                log.info("All logs flushed for FIX session {}", fixSessionId);
            } else {
                log.info("Unable to flush all logs for FIX session {} within {}, remaining {}", fixSessionId, flushMessageDuration, messagesQueue.getSize());
            }
        }
    }

    private void startUnderlyingResourceWatchdog(AsyncLogEvent[] asyncLogEventsToReplay, AsyncLogEvent asyncLogEventToReplay) {
        if (underlyingResourceWatchContext.isStarted()) {
            log.error("AsyncLogger logger for FIX session {} underlying resource state watchdog is already existing abnormal situation", fixSessionId);
            return;
        }
        log.info("Starting AsyncLogger logger underlying resource state watchdog for FIX session {}", fixSessionId);
        underlyingResourceWatchContext.startWatchDog(asyncLogEventsToReplay, asyncLogEventToReplay, wrappedLogger.getUnderlyingStorageResourceDescription());
    }

    private void registerEventsHandlingFromAsyncLoggersThreads() {
        if (isBatchingEnabled()) {
            asyncLoggersThreads.registerBatching(fixSessionId, messagesQueue, batchingEventsConsumer, asyncLogEventSerde, AsyncLogEvent.class);
        } else {
            asyncLoggersThreads.register(fixSessionId, messagesQueue, eventsConsumer, asyncLogEventSerde);
        }
    }

    private boolean isBatchingEnabled() {
        return asyncMessagesLoggerSettings.getAsyncStoreSettings().getEventsBatching() > 0;
    }

    boolean hasInactiveUnderlyingResourceWatchDog() {
        return !underlyingResourceWatchContext.isStarted();
    }

    private void onAsyncLogEvents(AsyncLogEvent[] asyncLogEvents) {
        try {
            int eventsCount = 0;
            for (AsyncLogEvent e : asyncLogEvents) {
                if (e == null) {
                    break;
                }
                switch (e.getLogEventType()) {
                    case INCOMING_MSG:
                    case OUTGOING_MSG:
                        batchedMessages[eventsCount++] = e;
                        break;
                    case EVENT:
                        wrappedLogger.logEvent(e.getLogTime(), e.getEvent());
                        break;
                    case EVENT_WITH_PARAMS:
                        wrappedLogger.logEvent(e.getLogTime(), e.getEvent(), e.getEventParams());
                        break;
                }
            }
            if (eventsCount > 0) {
                batchingWrappedLogger.logEvents(batchedMessages, eventsCount);
            }
        } catch (Exception ex) {
            if (!batchingWrappedLogger.isUnderlyingStorageResourceAvailable() && hasInactiveUnderlyingResourceWatchDog()) {
                log.error("Failed to process log message batching for FIX session {}", fixSessionId, ex);
                startUnderlyingResourceWatchdog(asyncLogEvents, null);
            } else {
                log.error("Failed to process log message batching for FIX session {}, messages are lost", fixSessionId, ex);
            }
        } finally {
            for (AsyncLogEvent e : asyncLogEvents) {
                if (e == null) {
                    break;
                }
                e.release();
            }
        }
    }

    private void onAsyncLogEvent(AsyncLogEvent asyncLogEvent) {
        try {
            switch (asyncLogEvent.getLogEventType()) {
                case INCOMING_MSG:
                    wrappedLogger.logIncoming(asyncLogEvent.getLogTime(), asyncLogEvent.getMessageType(), asyncLogEvent.getMessage());
                    break;
                case OUTGOING_MSG:
                    wrappedLogger.logOutgoing(asyncLogEvent.getLogTime(), asyncLogEvent.getMessageType(), asyncLogEvent.getMessage());
                    break;
                case EVENT:
                    wrappedLogger.logEvent(asyncLogEvent.getLogTime(), asyncLogEvent.getEvent());
                    break;
                case EVENT_WITH_PARAMS:
                    wrappedLogger.logEvent(asyncLogEvent.getLogTime(), asyncLogEvent.getEvent(), asyncLogEvent.getEventParams());
                    break;
            }
        } catch (Exception ex) {
            if (!wrappedLogger.isUnderlyingStorageResourceAvailable() && hasInactiveUnderlyingResourceWatchDog()) {
                log.error("Failed to process log message for FIX session {}", fixSessionId, ex);
                startUnderlyingResourceWatchdog(null, asyncLogEvent);
            } else {
                log.error("Failed to process log message for FIX session {}, message is lost", fixSessionId, ex);
            }
        } finally {
            asyncLogEvent.release();
        }
    }

    @Override
    public void logIncoming(UTCTime logTime, MessageType messageType, ByteBuffer message) {
        if (wrappedLoggerMessageFilter.test(messageType, FixMessagesLogger.LogEventType.INCOMING_MSG)) {
            return;
        }
        messagesQueue.offer(asyncLogEventSerde, asyncLogEventSerde.logIncoming(logTime, messageType, message));
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
    public void logOutgoing(UTCTime logTime, MessageType messageType, ByteBuffer message) {
        if (wrappedLoggerMessageFilter.test(messageType, FixMessagesLogger.LogEventType.OUTGOING_MSG)) {
            return;
        }
        messagesQueue.offer(asyncLogEventSerde, asyncLogEventSerde.logOutgoing(logTime, messageType, message));
    }

    @Override
    public void logEvent(UTCTime eventTime, String event) {
        messagesQueue.offer(asyncLogEventSerde, asyncLogEventSerde.event(eventTime, event));
    }

    @Override
    public void logEvent(UTCTime eventTime, String event, Object... params) {
        messagesQueue.offer(asyncLogEventSerde, asyncLogEventSerde.event(eventTime, event, params));
    }
}