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

import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.spring.boot.spi.BeanRef;
import org.lolaf.staffix.spring.boot.spi.FixMessagesLoggerSettingsContributor;
import org.lolaf.staffix.stores.loggers.slf4j.Slf4jMessagesLoggerSettings;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import java.util.function.Function;

/**
 * Contributes the SLF4J message logger's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class Slf4jMessagesLoggerContributor implements FixMessagesLoggerSettingsContributor {

    private final Slf4jMessagesLoggerProps props;
    private final ApplicationContext ctx;

    public Slf4jMessagesLoggerContributor(Slf4jMessagesLoggerProps props, ApplicationContext ctx) {
        this.props = props;
        this.ctx = ctx;
    }

    @Override
    public void contribute(Map<String, FixMessagesLoggerSettings> registry) {
        props.getInstances().forEach((key, p) -> {
            if (registry.putIfAbsent(key, build(key, p)) != null) {
                throw new IllegalStateException("staffix.messages-loggers-slf4j.instances." + key
                        + " collides with another logger contributor for the same key");
            }
        });
    }

    private FixMessagesLoggerSettings build(String mapKey, Slf4jLoggerEntryProps p) {
        Slf4jMessagesLoggerSettings.Slf4jMessagesLoggerSettingsBuilder<?, ?> b = Slf4jMessagesLoggerSettings.builder()
                .instanceId(mapKey);
        if (p.getLogIncoming() != null) b.logIncoming(p.getLogIncoming());
        if (p.getLogOutgoing() != null) b.logOutgoing(p.getLogOutgoing());
        if (p.getLogEvents() != null) b.logEvents(p.getLogEvents());
        if (p.getLogEventTemplate() != null) b.logEventTemplate(p.getLogEventTemplate());
        if (p.getLogOutTemplate() != null) b.logOutTemplate(p.getLogOutTemplate());
        if (p.getLogInTemplate() != null) b.logInTemplate(p.getLogInTemplate());
        if (p.getUseMDC() != null) b.useMDC(p.getUseMDC());
        if (p.getMessageFieldsDelimiter() != null) b.messageFieldsDelimiter(p.getMessageFieldsDelimiter());
        BeanRef.<Function<FixSessionId, String>>resolveOptional(ctx, p.getLoggerNameForFixSessionBean(), Function.class,
                        "staffix.messages-loggers-slf4j.instances." + mapKey + ".logger-name-for-fix-session-bean")
                .ifPresent(b::loggerNameForFixSession);
        return b.build();
    }
}
