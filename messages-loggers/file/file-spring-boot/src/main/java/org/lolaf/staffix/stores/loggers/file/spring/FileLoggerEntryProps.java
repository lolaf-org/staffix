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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

import java.util.concurrent.TimeUnit;

/**
 * One configured instance of the file message logger: its instance id and the settings a session naming that id gets.
 */
@Data
@ConfigurationPropertiesSource
public class FileLoggerEntryProps {

    /**
     * Log incoming messages
     */
    private Boolean logIncoming;
    /**
     * Log outgoing messages
     */
    private Boolean logOutgoing;
    /**
     * Log events
     */
    private Boolean logEvents;

    /**
     * Marks a session event line in the log.
     */
    private String logEventPrefix;
    /**
     * Marks an outbound message line.
     */
    private String logOutPrefix;
    /**
     * Marks an inbound message line.
     */
    private String logInPrefix;
    /**
     * The mode the log file is opened with, as {@link java.io.RandomAccessFile} spells it.
     */
    private String writeMode;
    /**
     * Where the log files are written. Required.
     */
    private String logDirectory;
    /**
     * Log time precision
     */
    private TimeUnit logTimePrecision;
    /**
     * TimeUnit to compress file,
     */
    private TimeUnit compressFileTimeUnit;
    /**
     * Value to trigger a file compression, I.E compressFileUnit = TimeUnit.HOURS and compressFileValue = 2
     * will compress file every 2 hours
     */
    private Integer compressFileValue;
    /**
     * Maximum number of compressed logs files to keep, 0 for unlimited count
     */
    private Integer maxCompressedFiles;

    /**
     * Spring bean name of {@code BiFunction<FixSessionId, File, File>} that derives the per-session log file name.
     */
    private String logFileNameBean;
}
