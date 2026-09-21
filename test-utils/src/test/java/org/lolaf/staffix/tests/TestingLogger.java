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
package org.lolaf.staffix.tests;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.time.UTCTime;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

@Getter
@RequiredArgsConstructor
public class TestingLogger extends Startable.VoidStartable<FixMessagesLogger.Logger> implements FixMessagesLogger.Logger {

    private static final boolean LOG_MESSAGES = Boolean.parseBoolean(System.getProperty("staffix.tests.log.messages", "true"));

    private final String prefix;
    private final List<String> incomingMessages = new CopyOnWriteArrayList<>();
    private final List<String> outgoingMessages = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<String> callingThreads = new CopyOnWriteArrayList<>();
    private final List<String> concurrentCalls = new CopyOnWriteArrayList<>();
    private final AtomicReference<Thread> threadInside = new AtomicReference<>();

    private static @NonNull LocalDateTime getLocalDateTime(UTCTime logTime) {
        return LocalDateTime.ofInstant(logTime.asInstant(), ZoneId.systemDefault()).truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * Runs one logger call, recording it if another thread was inside this logger at the same moment. Only the
     * thread that took the marker clears it, so a thread that found one already there leaves it alone.
     */
    private void recordingConcurrentCalls(String call, Runnable loggerCall) {
        Thread current = Thread.currentThread();
        Thread alreadyInside = threadInside.compareAndExchange(null, current);
        if (alreadyInside != null) {
            concurrentCalls.add(prefix + " " + call + " on " + current.getName()
                    + " while " + alreadyInside.getName() + " was inside the logger");
        }
        try {
            loggerCall.run();
        } finally {
            threadInside.compareAndSet(current, null);
        }
    }

    @Override
    public void logIncoming(UTCTime logTime, MessageType messageType, ByteBuffer message) {
        recordingConcurrentCalls("IN", () -> logIncomingMessage(logTime, message));
    }

    private void logIncomingMessage(UTCTime logTime, ByteBuffer message) {
        byte[] messageContent = new byte[message.remaining()];
        message.get(messageContent);
        String msg = new String(messageContent);
        incomingMessages.add(msg);
        if (LOG_MESSAGES) {
            System.out.println(getLocalDateTime(logTime) + " " + prefix + " IN : " + msg);
        }
    }

    @Override
    public void logOutgoing(UTCTime logTime, MessageType messageType, ByteBuffer message) {
        recordingConcurrentCalls("OUT", () -> logOutgoingMessage(logTime, message));
    }

    private void logOutgoingMessage(UTCTime logTime, ByteBuffer message) {
        byte[] messageContent = new byte[message.remaining()];
        message.get(messageContent);
        String msg = new String(messageContent);
        outgoingMessages.add(msg);
        if (LOG_MESSAGES) {
            System.out.println(getLocalDateTime(logTime) + " " + prefix + " OUT: " + msg);
        }
    }

    public void clear() {
        incomingMessages.clear();
        outgoingMessages.clear();
        events.clear();
        callingThreads.clear();
        concurrentCalls.clear();
    }

    @Override
    public boolean isLoggingEvents() {
        return true;
    }

    @Override
    public boolean isLoggingIncoming() {
        return true;
    }

    @Override
    public boolean isLoggingOutgoing() {
        return true;
    }

    @Override
    public void logEvent(UTCTime eventTime, String event) {
        recordingConcurrentCalls("EVENT", () -> logEventMessage(eventTime, event));
    }

    private void logEventMessage(UTCTime eventTime, String event) {
        String msg = String.format(event);
        callingThreads.add(Thread.currentThread().getName());
        events.add(msg);
        if (LOG_MESSAGES) {
            System.out.println(getLocalDateTime(eventTime) + " " + prefix + " EVENT: " + msg);
        }
    }
}