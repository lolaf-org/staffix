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

import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.codec.serde.UtcDateTimeSerde;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.GZIPInputStream;

class TestFileMessagesLogger {

    FileMessagesLogger fileMessagesLogger;
    FixMessagesLogger.Logger logger;
    UTCTime logTime;
    MessageType messageType;
    File logsDir;
    File logFile;
    Instant now;
    String expectedLogTime;
    FileMessagesLoggerSettings settings;
    FixSessionId fixSessionId;

    @BeforeEach
    void setup() {
        logsDir = new File("./target/tests-" + System.currentTimeMillis());
        settings = FileMessagesLoggerSettings
                .builder()
                .compressFileTimeUnit(TimeUnit.MINUTES)
                .compressFileValue(2)
                .logDirectory(logsDir.getPath())
                .build();
        fileMessagesLogger = new FileMessagesLogger(settings);
        fileMessagesLogger.start();
        fixSessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        logger = fileMessagesLogger.instanciateLogger("test", fixSessionId, null);
        logger.start();
        now = Instant.now();
        logTime = UTCTime.of(now);
        expectedLogTime = new String(UtcDateTimeSerde.serializeTime(logTime, settings.getLogTimePrecision()));
        messageType = MessageType.of("A", false);
        logFile = new File(logsDir, fixSessionId.forFileName(".log"));
    }

    @AfterEach
    void shutdown() {
        logger.stop(Deadline.immediate());
        fileMessagesLogger.stop(Deadline.immediate());
    }

    @Test
    void testLogsAreAppendedToExistingFile() throws IOException, FixMessagesLogger.LoggingException {
        logger.stop(Deadline.immediate());

        FileOutputStream fos = new FileOutputStream(logFile);
        fos.write("test initial log".getBytes());
        fos.flush();
        fos.close();

        logger.start();
        logger.logIncoming(logTime, messageType, ByteBuffer.wrap("test log".getBytes()));

        Assertions.assertThat(logFile).isFile().content().isEqualTo("test initial log" + expectedLogTime + " <-: test log\n");
    }

    @Test
    void testLogCompressionDelete() throws FixMessagesLogger.LoggingException {
        fileMessagesLogger.stop(Deadline.immediate());
        settings = FileMessagesLoggerSettings
                .builder()
                .compressFileTimeUnit(TimeUnit.MILLISECONDS)
                .compressFileValue(10)
                .maxCompressedFiles(2)
                .logDirectory(logsDir.getPath())
                .build();
        fileMessagesLogger = new FileMessagesLogger(settings);
        fileMessagesLogger.start();
        logger = fileMessagesLogger.instanciateLogger("test", fixSessionId, null);
        logger.start();

        logger.logIncoming(UTCTime.of(Instant.now()), messageType, ByteBuffer.wrap("test log".getBytes()));

        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(11));
        logger.logIncoming(UTCTime.of(Instant.now()), messageType, ByteBuffer.wrap("test log2".getBytes()));
        assertCompressLogsFilesCount(1);

        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(11));
        logger.logIncoming(UTCTime.of(Instant.now()), messageType, ByteBuffer.wrap("test log3".getBytes()));
        assertCompressLogsFilesCount(2);

        // should delete first compressed logs and we should stay with 2 compressed logs
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(11));
        logger.logIncoming(UTCTime.of(Instant.now()), messageType, ByteBuffer.wrap("test log4".getBytes()));

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));

        assertCompressLogsFilesCount(2);

    }

    private void assertCompressLogsFilesCount(int expected) {
        Awaitility.await().untilAsserted(() -> {
            File parent = logFile.getParentFile();
            File[] gz = parent.listFiles((dir, name) -> name.endsWith(".gz"));
            Assertions.assertThat(gz).isNotEmpty().hasSize(expected);
        });
    }

    @Test
    void testLogCompression() throws IOException, FixMessagesLogger.LoggingException {
        logger.logIncoming(logTime, messageType, ByteBuffer.wrap("test log".getBytes()));
        logger.logIncoming(logTime, messageType, ByteBuffer.wrap("test log2".getBytes()));

        logTime = UTCTime.of(Instant.now().plusSeconds(121));
        String newExpectedLogTime = new String(UtcDateTimeSerde.serializeTime(logTime, settings.getLogTimePrecision()));

        logger.logIncoming(logTime, messageType, ByteBuffer.wrap("test log compressed".getBytes()));

        Assertions.assertThat(logFile).isFile().content().isEqualTo(newExpectedLogTime + " <-: test log compressed\n");

        assertCompressLogsFilesCount(1);

        File gzFile = logFile.getParentFile().listFiles((dir, name) -> name.endsWith(".gz"))[0];
        FileInputStream gzFileIn = new FileInputStream(gzFile);
        GZIPInputStream in = new GZIPInputStream(gzFileIn);
        byte[] content = in.readAllBytes();
        in.close();
        gzFileIn.close();

        Assertions.assertThat(new String(content)).isEqualTo(expectedLogTime + " <-: test log\n" + expectedLogTime + " <-: test log2\n");

    }

    @Test
    void testLogIncoming() throws FixMessagesLogger.LoggingException {
        logger.logIncoming(logTime, messageType, ByteBuffer.wrap("test log".getBytes()));

        Assertions.assertThat(logFile).isFile().content().isEqualTo(expectedLogTime + " <-: test log\n");

        logger.logIncoming(logTime, messageType, ByteBuffer.wrap("test log2".getBytes()));

        Assertions.assertThat(logFile).isFile().content().isEqualTo(expectedLogTime + " <-: test log\n" + expectedLogTime + " <-: test log2\n");
    }

    @Test
    void testLogOutgoing() throws FixMessagesLogger.LoggingException {
        logger.logOutgoing(logTime, messageType, ByteBuffer.wrap("test log".getBytes()));

        Assertions.assertThat(logFile).isFile().content().isEqualTo(expectedLogTime + " ->: test log\n");

        logger.logOutgoing(logTime, messageType, ByteBuffer.wrap("test log2".getBytes()));

        Assertions.assertThat(logFile).isFile().content().isEqualTo(expectedLogTime + " ->: test log\n" + expectedLogTime + " ->: test log2\n");
    }

    @Test
    void testLogEvent() throws FixMessagesLogger.LoggingException {
        logger.logEvent(logTime, "test event");

        Assertions.assertThat(logFile).isFile().content().isEqualTo(expectedLogTime + " EVENT: test event\n");

        logger.logEvent(logTime, "test event %s", "param1");

        Assertions.assertThat(logFile).isFile().content().isEqualTo(expectedLogTime + " EVENT: test event\n" + expectedLogTime + " EVENT: test event param1\n");
    }
}