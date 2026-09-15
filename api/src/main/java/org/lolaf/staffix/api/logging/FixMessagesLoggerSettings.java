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

import org.lolaf.staffix.api.Factory;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.msg.MessageType;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * Settings for a message logger, resolved through the {@link org.lolaf.staffix.api.Factory} SPI.
 *
 * <p>{@link #getLogObfuscators()} matters more than it looks: a Logon carries Username(553) and Password(554)
 * in clear, and a log of raw FIX would otherwise keep them.
 */
public interface FixMessagesLoggerSettings extends InstanceProvider<FixMessagesLogger> {

    /**
     * List of log obfuscators to use with the logger, watch out as this has an impact on performance if not using an asynchronous logger
     */
    List<LogObfuscator> getLogObfuscators();

    /**
     * Predicate to filter messages that does not needs to be logging
     */
    BiPredicate<MessageType, FixMessagesLogger.LogEventType> getMessageFilter();

    default boolean isLogIncoming() {
        return true;
    }

    default boolean isLogOutgoing() {
        return true;
    }

    default boolean isLogEvents() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    default FixMessagesLogger instance() {
        return (FixMessagesLogger) InstanceProvider.getSpiInstance(this, FixMessagesLoggerFactory.class);
    }

    interface FixMessagesLoggerFactory<S> extends Factory<FixMessagesLogger, S> {

    }
}