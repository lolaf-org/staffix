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
package org.lolaf.staffix.stores.loggers.file.spring;

import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.spring.boot.spi.BeanRef;
import org.lolaf.staffix.spring.boot.spi.FixMessagesLoggerSettingsContributor;
import org.lolaf.staffix.stores.loggers.file.FileMessagesLoggerSettings;
import org.springframework.context.ApplicationContext;

import java.io.File;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Contributes the file message logger's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class FileMessagesLoggerContributor implements FixMessagesLoggerSettingsContributor {

    private final FileMessagesLoggerProps props;
    private final ApplicationContext ctx;

    public FileMessagesLoggerContributor(FileMessagesLoggerProps props, ApplicationContext ctx) {
        this.props = props;
        this.ctx = ctx;
    }

    @Override
    public void contribute(Map<String, FixMessagesLoggerSettings> registry) {
        props.getInstances().forEach((key, p) -> {
            if (registry.putIfAbsent(key, build(key, p)) != null) {
                throw new IllegalStateException("staffix.messages-loggers-file.instances." + key
                        + " collides with another logger contributor for the same key");
            }
        });
    }

    private FixMessagesLoggerSettings build(String mapKey, FileLoggerEntryProps p) {
        if (p.getLogDirectory() == null) {
            throw new IllegalArgumentException("staffix.messages-loggers-file.instances." + mapKey + ".log-directory is required");
        }
        FileMessagesLoggerSettings.FileMessagesLoggerSettingsBuilder<?, ?> b = FileMessagesLoggerSettings.builder()
                .instanceId(mapKey)
                .logDirectory(p.getLogDirectory());
        if (p.getLogIncoming() != null) b.logIncoming(p.getLogIncoming());
        if (p.getLogOutgoing() != null) b.logOutgoing(p.getLogOutgoing());
        if (p.getLogEvents() != null) b.logEvents(p.getLogEvents());
        if (p.getLogEventPrefix() != null) b.logEventPrefix(p.getLogEventPrefix());
        if (p.getLogOutPrefix() != null) b.logOutPrefix(p.getLogOutPrefix());
        if (p.getLogInPrefix() != null) b.logInPrefix(p.getLogInPrefix());
        if (p.getWriteMode() != null) b.writeMode(p.getWriteMode());
        if (p.getLogTimePrecision() != null) b.logTimePrecision(p.getLogTimePrecision());
        if (p.getCompressFileTimeUnit() != null) b.compressFileTimeUnit(p.getCompressFileTimeUnit());
        if (p.getCompressFileValue() != null) b.compressFileValue(p.getCompressFileValue());
        if (p.getMaxCompressedFiles() != null) b.maxCompressedFiles(p.getMaxCompressedFiles());
        BeanRef.<BiFunction<FixSessionId, File, File>>resolveOptional(ctx, p.getLogFileNameBean(), BiFunction.class,
                        "staffix.messages-loggers-file.instances." + mapKey + ".log-file-name-bean")
                .ifPresent(b::logFileName);
        return b.build();
    }
}
