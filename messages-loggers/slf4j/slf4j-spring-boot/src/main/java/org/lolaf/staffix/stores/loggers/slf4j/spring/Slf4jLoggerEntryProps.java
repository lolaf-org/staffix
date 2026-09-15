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
package org.lolaf.staffix.stores.loggers.slf4j.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

/**
 * One configured instance of the SLF4J message logger: its instance id and the settings a session naming that id gets.
 */
@Data
@ConfigurationPropertiesSource
public class Slf4jLoggerEntryProps {
    /**
     * Whether messages received are logged.
     */
    private Boolean logIncoming;
    /**
     * Whether messages sent are logged.
     */
    private Boolean logOutgoing;
    /**
     * Whether session events - connects, logons, logouts, disconnects - are logged alongside the messages,
     * which is what makes a log readable as a story rather than a stream.
     */
    private Boolean logEvents;

    /**
     * The SLF4J pattern a session event is logged with.
     */
    private String logEventTemplate;
    /**
     * The pattern an outbound message is logged with.
     */
    private String logOutTemplate;
    /**
     * The pattern an inbound message is logged with.
     */
    private String logInTemplate;
    /**
     * Whether the session id is put in the MDC, which is what lets a log pattern carry it without it being in
     * every message.
     */
    private Boolean useMDC;
    /**
     * Replaces the SOH separator in the logged text. Null leaves the raw bytes, which is faithful but
     * unreadable in most log viewers.
     */
    private Character messageFieldsDelimiter;

    /**
     * Spring bean name of {@code Function<FixSessionId, String>} that derives the SLF4J logger name per session.
     */
    private String loggerNameForFixSessionBean;
}
