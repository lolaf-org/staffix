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

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.stores.core.async.*;
import org.lolaf.staffix.stores.core.async.QueueSizeEvaluator;

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
    @Getter
    private final Probe queueSizeProbe;
    private final UnderlyingResourceWatchContext<AsyncLogEvent> underlyingResourceWatchContext;

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
        this.queueSizeProbe = QueueSizeEvaluator.probe("async-logger-queue", "Messages waiting in the async logger queue to be logged",
                messagesQueue, asyncMessagesLoggerSettings.getAsyncStoreSettings().getQueueSizeThresholdsSettings());
        this.underlyingResourceWatchContext = new UnderlyingResourceWatchContext<>(this::replayAsyncLogEvents,
                this::writeEvent, wrappedLogger::isUnderlyingStorageResourceAvailable, wrappedLogger.getClass().getSimpleName(),
                asyncMessagesLoggerSettings.getUnderlyingLoggerResourceWatchTaskCheckDelay(), () -> unregisterEventsHandlingFromAsyncLoggersThreads(Deadline.immediate()),
                this::onUnderlyingResourceBackUp,
                "async-logger-resource", "Availability of the logger the async logger writes to");
    }

    Probe getResourceDownProbe() {
        return underlyingResourceWatchContext.getResourceDownProbe();
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
        // unregistering first lets a write that fails meanwhile start its watchdog before it is stopped below
        if (!unregisterEventsHandlingFromAsyncLoggersThreads(stopDeadline)) {
            log.warn("Async logger for FIX session {} stops while its reader thread is still writing to it, a write it fails will be lost", fixSessionId);
        }
        if (underlyingResourceWatchContext.isStarted()) {
            log.warn("AsyncLogger shutdown with an logger underlying resource state active watchdog for FIX session {}", fixSessionId);
            underlyingResourceWatchContext.stop(stopDeadline);
        }

        wrappedLogger.stop(stopDeadline);
        messagesQueue.stop();
        log.info("Async logger store for FIX session {} stopped", fixSessionId);
    }

    private boolean unregisterEventsHandlingFromAsyncLoggersThreads(Deadline deadline) {
        return isBatchingEnabled()
                ? asyncLoggersThreads.unregisterBatching(batchingEventsConsumer, deadline)
                : asyncLoggersThreads.unregister(eventsConsumer, deadline);
    }

    private void onUnderlyingResourceBackUp() {
        if (isStarted()) {
            registerEventsHandlingFromAsyncLoggersThreads();
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
            asyncLoggersThreads.registerBatching(messagesQueue, batchingEventsConsumer, asyncLogEventSerde, AsyncLogEvent.class);
        } else {
            asyncLoggersThreads.register(messagesQueue, eventsConsumer, asyncLogEventSerde);
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
            if (!writeEvents(asyncLogEvents)) {
                if (isStarted() && hasInactiveUnderlyingResourceWatchDog()) {
                    startUnderlyingResourceWatchdog(asyncLogEvents, null);
                } else {
                    log.error("Unable to keep messages for a replay for FIX session {}, stopped or already replaying: messages are lost", fixSessionId);
                }
            }
        } finally {
            release(asyncLogEvents);
        }
    }

    private boolean replayAsyncLogEvents(AsyncLogEvent[] asyncLogEvents) {
        if (!writeEvents(asyncLogEvents)) {
            return false;
        }
        release(asyncLogEvents);
        return true;
    }

    private boolean writeEvents(AsyncLogEvent[] asyncLogEvents) {
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
            if (!batchingWrappedLogger.isUnderlyingStorageResourceAvailable()) {
                log.error("Failed to process log message batching for FIX session {}", fixSessionId, ex);
                return false;
            }
            log.error("Failed to process log message batching for FIX session {}, messages are lost", fixSessionId, ex);
        }
        return true;
    }

    private static void release(AsyncLogEvent[] asyncLogEvents) {
        for (AsyncLogEvent e : asyncLogEvents) {
            if (e == null) {
                break;
            }
            e.release();
        }
    }

    private void onAsyncLogEvent(AsyncLogEvent asyncLogEvent) {
        try {
            if (!writeEvent(asyncLogEvent)) {
                if (isStarted() && hasInactiveUnderlyingResourceWatchDog()) {
                    startUnderlyingResourceWatchdog(null, asyncLogEvent);
                } else {
                    log.error("Unable to keep message for a replay for FIX session {}, stopped or already replaying: message is lost", fixSessionId);
                }
            }
        } finally {
            asyncLogEvent.release();
        }
    }

    private boolean writeEvent(AsyncLogEvent asyncLogEvent) {
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
            if (!wrappedLogger.isUnderlyingStorageResourceAvailable()) {
                log.error("Failed to process log message for FIX session {}", fixSessionId, ex);
                return false;
            }
            log.error("Failed to process log message for FIX session {}, message is lost", fixSessionId, ex);
        }
        return true;
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