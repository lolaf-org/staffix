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

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.experimental.SuperBuilder;
import org.lolaf.staffix.api.msg.MessageType;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * The settings every message logger shares - obfuscators, message filter, instance id - so each module declares
 * only what is its own.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class AbstractFixMessageLoggerSettings implements FixMessagesLoggerSettings {

    /**
     * Names this instance, so a session can select it by id when more than one is configured.
     */
    @Builder.Default
    private final String instanceId = DEFAULT_INSTANCE_ID;
    /**
     * Whether messages received are logged.
     */
    @Builder.Default
    private boolean logIncoming = true;
    /**
     * Whether messages sent are logged.
     */
    @Builder.Default
    private boolean logOutgoing = true;
    /**
     * Whether session events - connects, logons, logouts, disconnects - are logged alongside the messages, which is
     * what makes a log readable as a story rather than a stream.
     */
    @Builder.Default
    private boolean logEvents = true;
    @Singular
    /**
     * Applied to every message before it is written. A Logon carries Username(553) and Password(554) in clear, and a
     * log of raw FIX is kept for years.
     */
    private List<LogObfuscator> logObfuscators;
    /**
     * Suppresses what it matches. Defaults to logging everything; the usual use is dropping heartbeats, which
     * otherwise dominate a quiet session's log.
     */
    @Builder.Default
    private BiPredicate<MessageType, FixMessagesLogger.LogEventType> messageFilter = (messageType, logeventType) -> false;
}
