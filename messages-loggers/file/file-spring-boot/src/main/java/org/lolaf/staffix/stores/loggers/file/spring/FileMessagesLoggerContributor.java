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
import org.lolaf.staffix.stores.loggers.file.FileLogLineFormat;
import org.lolaf.staffix.stores.loggers.file.LogfmtFileLogLineFormat;
import org.lolaf.staffix.stores.loggers.file.PlainFileLogLineFormat;
import org.springframework.context.ApplicationContext;

import java.io.File;
import java.util.Map;
import java.util.Optional;
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

    private FileLogLineFormat lineFormat(String mapKey, FileLoggerEntryProps p) {
        String prefix = "staffix.messages-loggers-file.instances." + mapKey + ".";
        Optional<FileLogLineFormat> custom = BeanRef.resolveOptional(ctx, p.getLineFormatBean(),
                FileLogLineFormat.class, prefix + "line-format-bean");
        if (custom.isPresent()) {
            if (p.getLineFormat() != null) {
                throw new IllegalArgumentException(prefix + "line-format and " + prefix
                        + "line-format-bean both choose the line format: set one");
            }
            return custom.get();
        }
        if (p.getLineFormat() == FileLoggerEntryProps.LineFormat.LOGFMT) {
            LogfmtFileLogLineFormat.LogfmtFileLogLineFormatBuilder logfmt = LogfmtFileLogLineFormat.builder();
            String delimiter = p.getLogfmt().getFixMessageFieldsDelimiter();
            if (delimiter != null) {
                if (delimiter.length() > 1) {
                    throw new IllegalArgumentException(prefix + "logfmt.fix-message-fields-delimiter must be one"
                            + " character, or empty to keep the SOH, not " + delimiter);
                }
                logfmt.fixMessageFieldsDelimiter(delimiter.isEmpty() ? null : delimiter.charAt(0));
            }
            return logfmt.build();
        }
        PlainFileLogLineFormat.PlainFileLogLineFormatBuilder plain = PlainFileLogLineFormat.builder();
        if (p.getPlain().getLogEventPrefix() != null) plain.logEventPrefix(p.getPlain().getLogEventPrefix());
        if (p.getPlain().getLogOutPrefix() != null) plain.logOutPrefix(p.getPlain().getLogOutPrefix());
        if (p.getPlain().getLogInPrefix() != null) plain.logInPrefix(p.getPlain().getLogInPrefix());
        return plain.build();
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
        b.lineFormat(lineFormat(mapKey, p));
        if (p.getWriteMode() != null) b.writeMode(p.getWriteMode());
        if (p.getLogTimePrecision() != null) b.logTimePrecision(p.getLogTimePrecision());
        if (p.getCompressFileTimeUnit() != null) b.compressFileTimeUnit(p.getCompressFileTimeUnit());
        if (p.getCompressFileValue() != null) b.compressFileValue(p.getCompressFileValue());
        if (p.getMaxCompressedFiles() != null) b.maxCompressedFiles(p.getMaxCompressedFiles());
        if (p.getResourceCheckInterval() != null) b.resourceCheckInterval(p.getResourceCheckInterval());
        if (p.getAlertClearDelay() != null) b.alertClearDelay(p.getAlertClearDelay());
        BeanRef.<BiFunction<FixSessionId, File, File>>resolveOptional(ctx, p.getLogFileNameBean(), BiFunction.class,
                        "staffix.messages-loggers-file.instances." + mapKey + ".log-file-name-bean")
                .ifPresent(b::logFileName);
        return b.build();
    }
}
