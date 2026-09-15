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
package org.lolaf.staffix.stores.loggers.file;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.lolaf.staffix.api.logging.AbstractFixMessageLoggerSettings;
import org.lolaf.staffix.api.session.FixSessionId;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Where the file message logger writes, how it rolls, and the prefixes marking a message's direction.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class FileMessagesLoggerSettings extends AbstractFixMessageLoggerSettings {

    /**
     * Marks a session event line in the log.
     */
    @Builder.Default
    private final String logEventPrefix = " EVENT: ";
    /**
     * Marks an outbound message line.
     */
    @Builder.Default
    private final String logOutPrefix = " ->: ";
    /**
     * Marks an inbound message line.
     */
    @Builder.Default
    private final String logInPrefix = " <-: ";
    /**
     * The mode the log file is opened with, as {@link java.io.RandomAccessFile} spells it.
     */
    @Builder.Default
    private final String writeMode = "rw";
    /**
     * Where the log files are written. Required.
     */
    private String logDirectory;
    /**
     * Names a session's log file. The default is derived from the session id, which keeps one file per session.
     */
    @Builder.Default
    private BiFunction<FixSessionId, File, File> logFileName = getLogFileNameFunction();
    /**
     * Log time precision
     */
    @Builder.Default
    private TimeUnit logTimePrecision = TimeUnit.MICROSECONDS;
    /**
     * TimeUnit to compress file,
     */
    @Builder.Default
    private TimeUnit compressFileTimeUnit = TimeUnit.HOURS;
    /**
     * Value to trigger a file compression, I.E compressFileUnit = TimeUnit.HOURS and compressFileValue = 2 will compress file every 2 hours
     */
    @Builder.Default
    private int compressFileValue = 1;

    /**
     * Maximum number of compressed logs files to keep, 0 for unlimited count
     */
    @Builder.Default
    private int maxCompressedFiles = 0;


    private static BiFunction<FixSessionId, File, File> getLogFileNameFunction() {
        return (fixSessionId, logsDirectory) -> new File(logsDirectory, fixSessionId.forFileName(".log"));
    }
}