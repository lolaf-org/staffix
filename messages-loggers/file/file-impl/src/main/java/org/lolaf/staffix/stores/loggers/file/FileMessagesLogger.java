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

import org.lolaf.staffix.api.serde.SerDe;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.serde.UtcDateTimeSerde;
import org.lolaf.staffix.stores.loggers.core.AbstractLogger;
import org.lolaf.staffix.stores.loggers.core.MessagesCoreBatchingLogger;

import java.io.*;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Writes every message to a file of its own, independent of the application's logging framework.
 *
 * <p>Batched: a log write per message would put a syscall on the message path.
 */
@Slf4j
public class FileMessagesLogger extends MessagesCoreBatchingLogger {

    private static final String BASE_DATE_FORMAT = "yyyyMMdd";
    private final FileMessagesLoggerSettings settings;

    protected FileMessagesLogger(FileMessagesLoggerSettings settings) {
        super(settings);
        this.settings = settings;
    }

    @Override
    protected void startMe() throws StartStopException {
        // nothing to do
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        // nothing to do
    }

    @Override
    public BatchingLogger instanciateLogger(String fixInstanceId, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry) {
        return new LoggerImpl(settings, fixSessionId);
    }

    static final class LoggerImpl extends AbstractLogger implements BatchingLogger {

        private final byte[] logEventPrefix;
        private final byte[] logOutPrefix;
        private final byte[] logInPrefix;
        private final byte newLine;
        private final FileMessagesLoggerSettings settings;
        private final File logsDir;
        private final LogFileName logFileNameFormatter;
        private final TimeUnit logTimePrecision;
        private File logFile;
        private FileChannel fileChannel;
        private RandomAccessFile logFileAccess;
        @Getter(AccessLevel.PACKAGE)
        private UTCTime nextFileCompressionTime;
        private ByteBuffer intermediateIncomingBuffer;
        private ByteBuffer intermediateOutgoingBuffer;
        private ByteBuffer intermediateEventBuffer;


        private LoggerImpl(FileMessagesLoggerSettings settings, FixSessionId fixSessionId) {
            super(settings, fixSessionId);
            this.settings = settings;
            this.logTimePrecision = settings.getLogTimePrecision();
            this.logEventPrefix = settings.getLogEventPrefix().getBytes(SerDe.CHARSET);
            this.logInPrefix = settings.getLogInPrefix().getBytes(SerDe.CHARSET);
            this.logOutPrefix = settings.getLogOutPrefix().getBytes(SerDe.CHARSET);
            this.newLine = '\n';
            this.logsDir = new File(settings.getLogDirectory());
            this.logFileNameFormatter = LogFileName.from(settings.getCompressFileTimeUnit());
            this.intermediateIncomingBuffer = ByteBuffer.allocate(2 * 1024);
            this.intermediateOutgoingBuffer = ByteBuffer.allocate(2 * 1024);
            this.intermediateEventBuffer = ByteBuffer.allocate(2 * 1024);
        }

        private void compress(File toCompress, File orginalFile) throws IOException {
            log.info("Compressing FIX log file {}", toCompress);
            if (settings.getMaxCompressedFiles() > 0) {
                File logsDir = orginalFile.getParentFile();
                File[] gzLogFiles = logsDir.listFiles(pathname -> pathname.getName().startsWith(orginalFile.getName() + "_") && pathname.getName().endsWith(".gz"));
                if (gzLogFiles != null) {
                    int filesToDelete = gzLogFiles.length - settings.getMaxCompressedFiles() + 1;
                    if (filesToDelete > 0) {
                        Arrays.sort(gzLogFiles, Comparator.comparingLong(File::lastModified));
                        for (int i = 0; i < filesToDelete; i++) {
                            if (!gzLogFiles[i].delete()) {
                                log.warn("Cannot delete GZ log file {}", gzLogFiles[i]);
                            }
                        }
                    }
                }
            }
            File target = new File(orginalFile.getParentFile(), logFileNameFormatter.getFormattedFileName(orginalFile));
            FileInputStream fis = new FileInputStream(toCompress);
            FileOutputStream fos = new FileOutputStream(target);
            GZIPOutputStream gzipOS = new GZIPOutputStream(fos);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                gzipOS.write(buffer, 0, len);
            }
            gzipOS.finish();
            gzipOS.close();
            fos.close();
            fis.close();
            if (!toCompress.delete()) {
                log.warn("Cannot delete log file after its compression {}", toCompress);
            }
            log.info("Compressing FIX log file {} done", target);
        }

        @Override
        protected void startMe() throws StartStopException {
            if (!logsDir.exists() && !logsDir.mkdirs()) {
                throw new IllegalStateException("Cannot create directory " + settings.getLogDirectory());
            }
            openFileChannel();
            nextFileCompressionTime = computeNextFileCompressionTime();
        }

        @Override
        protected void stopMe(Deadline stopDeadline) throws StartStopException {
            try {
                closeFileChannel();
            } catch (IOException e) {
                log.error("Failed to close log file", e);
            }
        }

        private void openFileChannel() {
            logFile = settings.getLogFileName().apply(getFixSessionId(), logsDir);
            try {
                logFileAccess = new RandomAccessFile(logFile, settings.getWriteMode());
                logFileAccess.seek(logFileAccess.length());
                fileChannel = logFileAccess.getChannel();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create log file", e);
            }
        }

        public UTCTime computeNextFileCompressionTime() {
            LocalDateTime date = LocalDateTime.now(ZoneId.of("UTC"));
            switch (settings.getCompressFileTimeUnit()) {
                case MILLISECONDS:
                    date = date.truncatedTo(ChronoUnit.MILLIS);
                    date = date.plusNanos(TimeUnit.MILLISECONDS.toNanos(settings.getCompressFileValue()));
                    break;
                case SECONDS:
                    date = date.truncatedTo(ChronoUnit.SECONDS);
                    date = date.plusSeconds(settings.getCompressFileValue());
                    break;
                case MINUTES:
                    date = date.truncatedTo(ChronoUnit.MINUTES);
                    date = date.plusMinutes(settings.getCompressFileValue());
                    break;
                case HOURS:
                    date = date.truncatedTo(ChronoUnit.HOURS);
                    date = date.plusHours(settings.getCompressFileValue());
                    break;
                case DAYS:
                    date = date.truncatedTo(ChronoUnit.DAYS);
                    date = date.plusDays(settings.getCompressFileValue());
                    break;
                default:
                    throw new IllegalStateException("Compression time unit " + settings.getCompressFileTimeUnit() + " is not supported");
            }
            return UTCTime.ImmutableTimeImpl.from(date.toInstant(ZoneOffset.UTC));
        }

        private void closeFileChannel() throws IOException {
            fileChannel.force(true);
            fileChannel.close();
            logFileAccess.close();
            logFileAccess = null;
            fileChannel = null;
        }

        @Override
        public void logEvents(LogEvent[] logEvents, int eventsCount) {
            for (int i = 0; i < eventsCount; i++) {
                LogEvent logEvent = logEvents[i];
                if (logEvent.getLogEventType().equals(LogEventType.INCOMING_MSG)) {
                    logIncoming(logEvent.getLogTime(), logEvent.getMessageType(), logEvent.getMessage());
                } else if (logEvent.getLogEventType().equals(LogEventType.OUTGOING_MSG)) {
                    logOutgoing(logEvent.getLogTime(), logEvent.getMessageType(), logEvent.getMessage());
                } else {
                    throw new IllegalStateException("Unknown LogEventType " + logEvent.getLogEventType());
                }
            }
        }

        @Override
        public void logIncoming(UTCTime logTime, MessageType messageType, ByteBuffer message) {
            logMessage(logTime, message, logInPrefix, intermediateIncomingBuffer);
        }

        @Override
        public void logOutgoing(UTCTime logTime, MessageType messageType, ByteBuffer message) {
            logMessage(logTime, message, logOutPrefix, intermediateOutgoingBuffer);
        }

        private void logMessage(UTCTime logTime, ByteBuffer message, byte[] logPrefix, ByteBuffer intermediateBuffer) {
            if (nextFileCompressionTime.compareTo(logTime) < 0) {
                try {
                    switchToNextLogFile();
                } catch (IOException e) {
                    log.error("Failed to switch to next log file", e);
                }
            }
            try {
                intermediateBuffer = fillBuffer(logTime, message, logPrefix, intermediateBuffer);
                logFileAccess.write(intermediateBuffer.array(), intermediateBuffer.position(), intermediateBuffer.remaining());
            } catch (IOException e) {
                log.error("Failed to write log to file", e);
            }
        }

        private ByteBuffer fillBuffer(UTCTime logTime, ByteBuffer message, byte[] logPrefix, ByteBuffer intermediateBuffer) {
            try {
                return intermediateBuffer.clear()
                        .put(UtcDateTimeSerde.serializeTime(logTime, logTimePrecision))
                        .put(logPrefix)
                        .put(message)
                        .put(newLine).flip();
            } catch (BufferOverflowException e) {
                if (intermediateBuffer == intermediateEventBuffer) {
                    intermediateEventBuffer = ByteBuffer.allocate(intermediateEventBuffer.capacity() * 2);
                    log.debug("New intermediateEventBuffer size is {}", intermediateEventBuffer.capacity());
                    return fillBuffer(logTime, message.position(0), logPrefix, intermediateEventBuffer);
                } else if (intermediateBuffer == intermediateIncomingBuffer) {
                    intermediateIncomingBuffer = ByteBuffer.allocate(intermediateIncomingBuffer.capacity() * 2);
                    log.debug("New intermediateIncomingBuffer size is {}", intermediateIncomingBuffer.capacity());
                    return fillBuffer(logTime, message.position(0), logPrefix, intermediateIncomingBuffer);
                } else if (intermediateBuffer == intermediateOutgoingBuffer) {
                    intermediateOutgoingBuffer = ByteBuffer.allocate(intermediateOutgoingBuffer.capacity() * 2);
                    log.debug("New intermediateOutgoingBuffer size is {}", intermediateOutgoingBuffer.capacity());
                    return fillBuffer(logTime, message.position(0), logPrefix, intermediateEventBuffer);
                } else {
                    throw new IllegalStateException("Should never have happened");
                }
            }
        }

        private void switchToNextLogFile() throws IOException {
            nextFileCompressionTime = computeNextFileCompressionTime();
            closeFileChannel();
            File tmpFile = new File(logFile.getParentFile(), logFile.getName() + ".tmp");
            if (!logFile.renameTo(tmpFile)) {
                // try to reopen the file channel
                openFileChannel();
                throw new IOException("Unable to rename log file to .tmp file");
            }
            openFileChannel();
            Thread compressionThread = new Thread(() -> {
                try {
                    compress(tmpFile, logFile);
                } catch (IOException e) {
                    log.error("Failed to compress log file {}", tmpFile, e);
                }
            }, "FIX-logs-compression");
            compressionThread.setDaemon(false);
            compressionThread.start();
        }

        @Override
        public void logEvent(UTCTime eventTime, String event) {
            logMessage(eventTime, ByteBuffer.wrap(event.getBytes(SerDe.CHARSET)), logEventPrefix, intermediateEventBuffer);
        }

        enum LogFileName {
            MILLISECONDS(new SimpleDateFormat(BASE_DATE_FORMAT + "-HHmmssSSS")),
            SECONDS(new SimpleDateFormat(BASE_DATE_FORMAT + "-HHmmss")),
            MINUTES(new SimpleDateFormat(BASE_DATE_FORMAT + "-HHmm")),
            HOURS(new SimpleDateFormat(BASE_DATE_FORMAT + "-HH")),
            DAYS(new SimpleDateFormat(BASE_DATE_FORMAT));

            private final SimpleDateFormat dateFormat;

            LogFileName(SimpleDateFormat dateFormat) {
                this.dateFormat = dateFormat;
            }

            static LogFileName from(TimeUnit timeUnit) {
                switch (timeUnit) {
                    case MILLISECONDS:
                        return MILLISECONDS;
                    case SECONDS:
                        return SECONDS;
                    case MINUTES:
                        return MINUTES;
                    case HOURS:
                        return HOURS;
                    case DAYS:
                        return DAYS;
                    default:
                        throw new IllegalStateException("Time unit " + timeUnit + " is not supported");
                }
            }

            String getFormattedFileName(File file) {
                synchronized (this) {
                    return file.getName() + "_" + dateFormat.format(new Date()) + ".gz";
                }
            }
        }
    }

    public static class FileMessagesLoggerFactoryImpl implements FixMessagesLoggerSettings.FixMessagesLoggerFactory<FileMessagesLoggerSettings> {

        @Override
        public FixMessagesLogger newInstance(FileMessagesLoggerSettings settings) {
            return new FileMessagesLogger(settings);
        }

        @Override
        public Class<FileMessagesLoggerSettings> getSettingsClass() {
            return FileMessagesLoggerSettings.class;
        }
    }
}