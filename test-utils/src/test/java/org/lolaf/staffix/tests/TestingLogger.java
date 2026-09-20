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

@Getter
@RequiredArgsConstructor
public class TestingLogger extends Startable.VoidStartable<FixMessagesLogger.Logger> implements FixMessagesLogger.Logger {

    private static final boolean LOG_MESSAGES = Boolean.parseBoolean(System.getProperty("staffix.tests.log.messages", "true"));

    private final String prefix;
    private final List<String> incomingMessages = new CopyOnWriteArrayList<>();
    private final List<String> outgoingMessages = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<String> callingThreads = new CopyOnWriteArrayList<>();

    private static @NonNull LocalDateTime getLocalDateTime(UTCTime logTime) {
        return LocalDateTime.ofInstant(logTime.asInstant(), ZoneId.systemDefault()).truncatedTo(ChronoUnit.MILLIS);
    }

    @Override
    public void logIncoming(UTCTime logTime, MessageType messageType, ByteBuffer message) {
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
        String msg = String.format(event);
        callingThreads.add(Thread.currentThread().getName());
        events.add(msg);
        if (LOG_MESSAGES) {
            System.out.println(getLocalDateTime(eventTime) + " " + prefix + " EVENT: " + msg);
        }
    }
}