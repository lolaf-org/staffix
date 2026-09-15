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
package org.lolaf.staffix.stores.loggers.slf4j;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.lolaf.staffix.api.logging.AbstractFixMessageLoggerSettings;
import org.lolaf.staffix.api.session.FixSessionId;

import java.util.function.Function;

/**
 * The logger name and level the SLF4J message logger writes under.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class Slf4jMessagesLoggerSettings extends AbstractFixMessageLoggerSettings {

    /**
     * The SLF4J pattern a session event is logged with.
     */
    @Builder.Default
    private final String logEventTemplate = "EVENT: {}";
    /**
     * The pattern an outbound message is logged with.
     */
    @Builder.Default
    private final String logOutTemplate = "->: {}";
    /**
     * The pattern an inbound message is logged with.
     */
    @Builder.Default
    private final String logInTemplate = "<-: {}";
    /**
     * Chooses the logger name per session, so one session's traffic can be routed or silenced independently.
     */
    @Builder.Default
    private Function<FixSessionId, String> loggerNameForFixSession = s -> Slf4jMessagesLogger.class.getName();
    /**
     * Whether the session id is put in the MDC, which is what lets a log pattern carry it without it being in
     * every message.
     */
    @Builder.Default
    private boolean useMDC = true;
    /**
     * Replaces the SOH separator in the logged text. Null leaves the raw bytes, which is faithful but unreadable
     * in most log viewers.
     */
    @Builder.Default
    private Character messageFieldsDelimiter = null;

}