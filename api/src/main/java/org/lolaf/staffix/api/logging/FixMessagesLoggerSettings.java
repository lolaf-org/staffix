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
import org.lolaf.staffix.api.metadata.ComponentKind;
import org.lolaf.staffix.api.metadata.SelectedBy;
import org.lolaf.staffix.api.msg.MessageType;

import java.time.Duration;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Settings for a message logger, resolved through the {@link org.lolaf.staffix.api.Factory} SPI.
 *
 * <p>{@link #getLogObfuscators()} matters more than it looks: a Logon carries Username(553) and Password(554)
 * in clear, and a log of raw FIX would otherwise keep them.
 */
@ComponentKind("messages-logger")
@SelectedBy(setting = "fixMessageLoggerInstanceId", whenUnnamed = SelectedBy.WhenUnnamed.DEFAULT_INSTANCE,
        whenUnknown = SelectedBy.WhenUnknown.REFUSED_ONCE_ANY_REGISTERED)
public interface FixMessagesLoggerSettings extends InstanceProvider<FixMessagesLogger> {

    Duration DEFAULT_RESOURCE_CHECK_INTERVAL = Duration.ofSeconds(5);
    Duration DEFAULT_ALERT_CLEAR_DELAY = Duration.ofSeconds(30);

    /**
     * The obfuscators applied to each logged message. They run on the session's thread unless the logger is
     * asynchronous, so each one adds to the message path.
     */
    List<LogObfuscator> getLogObfuscators();

    /**
     * Returns true for a message that is not logged.
     */
    BiPredicate<MessageType, FixMessagesLogger.LogEventType> getMessageFilter();

    /**
     * Whether received messages are logged.
     */
    default boolean isLogIncoming() {
        return true;
    }

    /**
     * Whether sent messages are logged.
     */
    default boolean isLogOutgoing() {
        return true;
    }

    /**
     * Whether session events, such as logons and disconnections, are logged.
     */
    default boolean isLogEvents() {
        return true;
    }

    /**
     * How often a logger whose calls fail is checked for its resource coming back. While it is down the engine skips
     * the logger's calls, rather than block the session on each one. Zero sends every call to the logger.
     */
    default Duration getResourceCheckInterval() {
        return DEFAULT_RESOURCE_CHECK_INTERVAL;
    }

    /**
     * How long the logger must keep succeeding before the session's logger alert clears, so a resource dropping in
     * and out keeps one alert. Zero clears it on the first success.
     */
    default Duration getAlertClearDelay() {
        return DEFAULT_ALERT_CLEAR_DELAY;
    }

    @Override
    @SuppressWarnings("unchecked")
    default FixMessagesLogger instance() {
        return (FixMessagesLogger) InstanceProvider.getSpiInstance(this, FixMessagesLoggerFactory.class);
    }

    /**
     * The service provider that builds a message logger from its settings class; see {@link Factory}.
     *
     * @param <S> the settings class it serves
     */
    interface FixMessagesLoggerFactory<S> extends Factory<FixMessagesLogger, S> {

    }
}