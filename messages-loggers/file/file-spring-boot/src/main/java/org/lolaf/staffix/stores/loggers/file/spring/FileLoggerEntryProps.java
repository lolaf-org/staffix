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
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * One configured instance of the file message logger: its instance id and the settings a session naming that id gets.
 */
@Data
@ConfigurationPropertiesSource
public class FileLoggerEntryProps {

    /**
     * Whether received messages are logged.
     */
    private Boolean logIncoming;
    /**
     * Whether sent messages are logged.
     */
    private Boolean logOutgoing;
    /**
     * Whether session events, such as logons and disconnections, are logged.
     */
    private Boolean logEvents;

    /**
     * The layout of each line: plain, the default, the time, a direction prefix and the message; or logfmt, the time,
     * the session, the direction and the message type as logfmt pairs a log shipper reads, then the message.
     */
    private LineFormat lineFormat;
    /**
     * Settings of the plain line format.
     */
    @NestedConfigurationProperty
    private PlainProps plain = new PlainProps();
    /**
     * Settings of the logfmt line format.
     */
    @NestedConfigurationProperty
    private LogfmtProps logfmt = new LogfmtProps();
    /**
     * Spring bean name of a FileLogLineFormat of your own, in place of line-format.
     */
    private String lineFormatBean;
    /**
     * The mode the log file is opened with, as RandomAccessFile spells it: rw by default.
     */
    private String writeMode;
    /**
     * Where the log files are written. Required.
     */
    private String logDirectory;
    /**
     * Precision of the timestamp on each line. Microseconds by default.
     */
    private TimeUnit logTimePrecision;
    /**
     * Unit of the compression period, compress-file-value. Hours by default.
     */
    private TimeUnit compressFileTimeUnit;
    /**
     * Compression period, counted in compress-file-time-unit: HOURS and 2 compress the log every two hours.
     */
    private Integer compressFileValue;
    /**
     * How many compressed log files are kept. 0, the default, keeps them all.
     */
    private Integer maxCompressedFiles;

    /**
     * Spring bean name of a BiFunction from FixSessionId and the log directory to the session's log File. The
     * default names the file after the session's qualified name, {@code group.name}.
     */
    private String logFileNameBean;
    /**
     * How often a logger whose resource is down is checked for its return, its calls being skipped meanwhile. Zero
     * disables the check, so every call reaches the resource.
     */
    private Duration resourceCheckInterval;
    /**
     * How long logger calls must keep succeeding before the session's messages-logger alert clears.
     */
    private Duration alertClearDelay;

    /**
     * The line formats the properties select.
     */
    public enum LineFormat {
        PLAIN,
        LOGFMT
    }

    /**
     * Settings of the plain line format.
     */
    @Data
    public static class PlainProps {
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
    }

    /**
     * Settings of the logfmt line format.
     */
    @Data
    public static class LogfmtProps {
        /**
         * Replaces the SOH between the message's fields: | by default. Empty keeps the SOH, which costs nothing per
         * message.
         */
        private String fixMessageFieldsDelimiter;
    }
}
