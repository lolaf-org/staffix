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
package org.lolaf.staffix.api.logging;

import lombok.Getter;
import org.lolaf.staffix.api.FixAcceptorBuilder;
import org.lolaf.staffix.api.FixInitiatorBuilder;
import org.lolaf.staffix.api.InstanceIdSupplier;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;

import java.nio.ByteBuffer;

/**
 * Records every message a session sends and receives, for audit and for diagnosis.
 *
 * <p>Distinct from application logging: this is the raw FIX, and it is what a counterparty dispute is settled
 * with. {@link #getLogger} is per session and per message type so an implementation can route or suppress
 * without the session layer knowing how.
 */
public interface FixMessagesLogger extends InstanceIdSupplier, Startable<FixMessagesLogger> {

    /**
     * Retrieves a logger for a given session
     *
     * @param fixInstanceId       the fix instance id ({@link FixInitiatorBuilder#getInstanceId()} or {@link FixAcceptorBuilder#getInstanceId()} )
     * @param fixSessionId        the fix protocol session id
     * @param messageTypeRegistry the messages type registry used by the session, useful for making messages filtering
     */
    Logger getLogger(String fixInstanceId, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry);

    @Getter
    enum LogEventType {
        INCOMING_MSG(true, (byte) 0),
        OUTGOING_MSG(true, (byte) 1),
        EVENT(false, (byte) 2),
        EVENT_WITH_PARAMS(false, (byte) 3);

        private final boolean messageEvent;
        private final byte code;

        LogEventType(boolean messageEvent, byte code) {
            this.messageEvent = messageEvent;
            this.code = code;
        }

        public static LogEventType from(byte code) {
            // keep it like that, using Enum.values[] creates an array instance each call
            if (code == INCOMING_MSG.getCode()) {
                return INCOMING_MSG;
            } else if (code == OUTGOING_MSG.getCode()) {
                return OUTGOING_MSG;
            } else if (code == EVENT.getCode()) {
                return EVENT;
            } else if (code == EVENT_WITH_PARAMS.getCode()) {
                return EVENT_WITH_PARAMS;
            }
            throw new IllegalStateException("Unknown code " + code);
        }
    }

    /**
     * Interface to be implemented by your logger to process logging messages in batch in conjunction with an async logger
     */
    interface BatchingLogger extends Logger {

        /**
         * Logs multiple log events to enable batch processing and increase throughput,
         * note that only {@link LogEventType#INCOMING_MSG} and {@link LogEventType#OUTGOING_MSG} will be provided in the logEvents list.
         * Other {@link LogEventType#INCOMING_MSG} and {@link LogEventType#INCOMING_MSG} will be called normally using {@link EventsLogger#logEvent(UTCTime, String, Object...)}
         * and {@link EventsLogger#logEvent(UTCTime, String)} methods calls
         *
         * @param logEvents the collection of log events as an array for optimal memory allocation, and the eventsCount to process as the array will contain null references
         *                  starting from eventsCount+1
         *                  WARNING: the events instances array and their instances CANNOT be passed to another thread unless you call {@link LogEvent#asImmutable()} method
         * @throws LoggingException A logging exception if some error occurred during logging, it's important that implementation does not trap exceptions and rethrow them
         */
        void logEvents(LogEvent[] logEvents, int eventsCount) throws LoggingException;

        interface LogEvent {

            UTCTime getLogTime();

            MessageType getMessageType();

            ByteBuffer getMessage();

            LogEventType getLogEventType();

            LogEvent asImmutable();
        }
    }

    /**
     * A session's logger, called by that session alone.
     *
     * <p><b>Staffix owns the threading, so an implementation needs no locking</b>: a session calls its logger from
     * one thread at a time, and never from two at once. That thread is the IO thread of the session's connection,
     * which is where a message is read, written and logged, and where the engine hands an event raised anywhere
     * else, a scheduled timeout or an application thread calling the session.
     *
     * <p>A session with no connection has no IO thread, and its logger is then written by the engine's thread for
     * the sessions that are down, which the engine hands those events to exactly as it hands the others to an IO
     * thread. The one exception is an engine that has stopped that thread, where what is left of a teardown is
     * written by whoever raised it rather than lost.
     *
     * <p>A logger is per session, so two sessions log in parallel through two instances. Anything an implementation
     * shares between them, a file, a buffer pool, a queue, is its own to protect.
     */
    interface Logger extends Startable<Logger>, EventsLogger {

        /**
         * Indicates if the underlying resource to store the logs is currently available or not, this method should not throw an exception under any circumstances
         */
        default boolean isUnderlyingStorageResourceAvailable() {
            return true;
        }

        /**
         * Returns a description of the logger underlying storage resource
         */
        default String getUnderlyingStorageResourceDescription() {
            return "default";
        }

        /**
         * Logs an incoming FIX message, call to this method is always done in the FIX session IO thread when the message has been received thus not requiring to have a tread safe implementation
         *
         * @param logTime     the time of the log, transform it as {@link UTCTime#asImmutable()} if you need to supply it to another thread or keep an object reference
         * @param messageType the message type
         * @param message     the message byte buffer to log
         * @throws LoggingException A logging exception if some error occurred during logging, it's important that implementation does not trap exceptions and rethrow them
         */
        void logIncoming(UTCTime logTime, MessageType messageType, ByteBuffer message) throws LoggingException;

        /**
         * Logs an outgoing FIX message, call to this method is always done in the FIX session IO thread when the message has been sent thus not requiring to have a tread safe implementation
         *
         * @param logTime     the time of the log, transform it as {@link UTCTime#asImmutable()} if you need to supply it to another thread or keep an object reference
         * @param messageType the message type
         * @param message     the message byte buffer to log
         * @throws LoggingException A logging exception if some error occurred during logging, it's important that implementation does not trap exceptions and rethrow them
         */
        void logOutgoing(UTCTime logTime, MessageType messageType, ByteBuffer message) throws LoggingException;

        /**
         * Indicates if the logger should log outgoing messages
         */
        boolean isLoggingOutgoing();

        /**
         * Indicates if the logger should log incoming messages
         */
        boolean isLoggingIncoming();

    }

    interface EventsLogger {
        /**
         * Logs an event happening on the session, call to this method is always done in the FIX session IO thread thus not requiring to have a tread safe implementation
         *
         * @param eventTime the time of the event, transform it as {@link UTCTime#asImmutable()} if you need to supply it to another thread or keep an object reference
         * @param event     the event to log
         * @throws LoggingException A logging exception if some error occurred during logging, it's important that implementation does not trap exceptions and rethrow them
         */
        void logEvent(UTCTime eventTime, String event) throws LoggingException;

        /**
         * Logs an event happening on the session, using {@link String#format(String, Object...)}, call to this method is always done in the FIX session IO thread thus not requiring to have a tread safe implementation
         *
         * @param eventTime the time of the event, transform it as {@link UTCTime#asImmutable()} if you need to supply it to another thread or keep an object reference
         * @param event     the event to log
         * @param params    the event log params
         * @throws LoggingException A logging exception if some error occurred during logging, it's important that implementation does not trap exceptions and rethrow them
         */
        default void logEvent(UTCTime eventTime, String event, Object... params) throws LoggingException {
            logEvent(eventTime, String.format(event, params));
        }

        /**
         * Indicates if the logger should log a session events
         */
        boolean isLoggingEvents();
    }

    class LoggingException extends Exception {

    }
}