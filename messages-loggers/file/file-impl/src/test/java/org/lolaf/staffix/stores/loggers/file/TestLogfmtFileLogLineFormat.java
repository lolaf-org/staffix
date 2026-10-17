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
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestLogfmtFileLogLineFormat {

    private static final UTCTime TIME = UTCTime.of(Instant.parse("2026-10-16T10:15:30.123456Z"));
    private static final MessageType ORDER = indexed(MessageType.of("D", false), 0);
    private static final FixSessionId SESSION = FixSessionId.of("CLIENT1", FixRegularVersion.VERSION_44, "EXEC",
            "CLIENT");

    private final MessageTypeRegistry registry = registry(ORDER);

    @Test
    void writesTheAttributesThenTheMessage() {
        FileLogLineFormatter formatter = LogfmtFileLogLineFormat.builder().build()
                .newFormatter("E1", "acceptor", SESSION, registry, TimeUnit.MICROSECONDS);
        String head = "time=2026-10-16T10:15:30.123456Z fix.eid=E1 fix.iid=acceptor fix.sg=" + SESSION.getGroup()
                + " fix.sn=CLIENT1";

        assertThat(line(l -> formatter.formatIncoming(TIME, ORDER, bytes("8=FIX.4.4\u000158=a b\u0001"), l)))
                .isEqualTo(head + " fix.md=i fix.mt=D - 8=FIX.4.4|58=a b|\n");
        assertThat(line(l -> formatter.formatOutgoing(TIME, ORDER, bytes("8=FIX.4.4\u0001"), l)))
                .isEqualTo(head + " fix.md=o fix.mt=D - 8=FIX.4.4|\n");
        assertThat(line(l -> formatter.formatEvent(TIME, bytes("Logged on"), l)))
                .isEqualTo(head + " - Logged on\n");
    }

    @Test
    void keepsTheSohWithoutADelimiter() {
        FileLogLineFormatter formatter = LogfmtFileLogLineFormat.builder().fixMessageFieldsDelimiter(null).build()
                .newFormatter("E1", "acceptor", SESSION, registry, TimeUnit.SECONDS);

        assertThat(line(l -> formatter.formatIncoming(TIME, ORDER, bytes("8=FIX.4.4\u0001"), l)))
                .endsWith(" fix.md=i fix.mt=D - 8=FIX.4.4\u0001\n");
    }

    /**
     * As a generated dictionary's types are: {@link MessageType#of} makes an unindexed one, which the formatter's
     * cache ignores.
     */
    private static MessageType indexed(MessageType type, int index) {
        return new MessageType() {
            @Override
            public int getAsInt() {
                return index;
            }

            @Override
            public String code() {
                return type.code();
            }

            @Override
            public boolean isAdmin() {
                return type.isAdmin();
            }

            @Override
            public boolean isStorable() {
                return type.isStorable();
            }

            @Override
            public byte[] serialized() {
                return type.serialized();
            }
        };
    }

    private static MessageTypeRegistry registry(MessageType... types) {
        MessageTypeRegistry registry = mock(MessageTypeRegistry.class);
        when(registry.getMessageTypes()).thenReturn(List.of(types));
        return registry;
    }

    private static ByteBuffer bytes(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static String line(Consumer<ByteBuffer> format) {
        ByteBuffer line = ByteBuffer.allocate(512);
        format.accept(line);
        return new String(line.array(), 0, line.position(), StandardCharsets.US_ASCII);
    }
}
