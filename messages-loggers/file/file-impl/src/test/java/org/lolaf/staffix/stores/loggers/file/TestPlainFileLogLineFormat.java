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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.time.UTCTime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TestPlainFileLogLineFormat {

    private static final UTCTime TIME = UTCTime.of(Instant.parse("2026-10-16T10:15:30.123456Z"));
    private static final MessageType LOGON = MessageType.of("A", true);

    private final FileLogLineFormatter formatter = PlainFileLogLineFormat.builder().build()
            .newFormatter("engine", "acceptor", null, null, TimeUnit.MICROSECONDS);

    @Test
    void writesTheTimeThePrefixAndTheMessageAsTheLoggerAlwaysHas() {
        assertThat(line(l -> formatter.formatIncoming(TIME, LOGON, bytes("8=FIX.4.4\u00019=5"), l)))
                .isEqualTo("20261016-10:15:30.123456 <-: 8=FIX.4.4\u00019=5\n");
        assertThat(line(l -> formatter.formatOutgoing(TIME, LOGON, bytes("8=FIX.4.4"), l)))
                .isEqualTo("20261016-10:15:30.123456 ->: 8=FIX.4.4\n");
        assertThat(line(l -> formatter.formatEvent(TIME, bytes("Logged on"), l)))
                .isEqualTo("20261016-10:15:30.123456 EVENT: Logged on\n");
    }

    @Test
    void writesItsOwnPrefixes() {
        FileLogLineFormatter custom = PlainFileLogLineFormat.builder().logInPrefix(" IN ").build()
                .newFormatter("engine", "acceptor", null, null, TimeUnit.SECONDS);

        assertThat(line(l -> custom.formatIncoming(TIME, LOGON, bytes("8=FIX.4.4"), l)))
                .isEqualTo("20261016-10:15:30 IN 8=FIX.4.4\n");
    }

    private static ByteBuffer bytes(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static String line(java.util.function.Consumer<ByteBuffer> format) {
        ByteBuffer line = ByteBuffer.allocate(256);
        format.accept(line);
        return new String(line.array(), 0, line.position(), StandardCharsets.US_ASCII);
    }
}
