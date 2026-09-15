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
package org.lolaf.staffix.api.logging;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.msg.MessageType;

import java.nio.ByteBuffer;

class TestLogObfuscator {

    @Test
    void testObfuscate() {

        String message = "8=FIX.4.49=8335=A34=149=TEST52=20241013-19:07:17.86156=SENDER98=0108=30141=Y10=249";

        LogObfuscator obfuscator = new LogObfuscator.AbstractLogObfuscator() {
            @Override
            protected int getFieldNumberCharsLen() {
                // 49
                return 2;
            }

            @Override
            protected boolean positionMatchesFieldStart(ByteBuffer log, int position) {
                return log.get(position) == (byte) '4' && log.get(position + 1) == (byte) '9' && log.get(position + 2) == EQUALS_CHAR;
            }

            @Override
            public boolean isForMessageType(MessageType messageType) {
                return false;
            }
        };

        ByteBuffer toObfuscate = ByteBuffer.wrap(message.getBytes());
        ByteBuffer obfuscated = obfuscator.obfuscate(toObfuscate);
        Assertions.assertThat(message.replace("TEST", "****")).isEqualTo(new String(obfuscated.array()));
    }

    @Test
    void testNoObfuscate() {

        String message = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";

        LogObfuscator obfuscator = new LogObfuscator.AbstractLogObfuscator() {
            @Override
            protected int getFieldNumberCharsLen() {
                throw new IllegalStateException("Should not have been called");
            }

            @Override
            protected boolean positionMatchesFieldStart(ByteBuffer log, int position) {
                return false;
            }

            @Override
            public boolean isForMessageType(MessageType messageType) {
                return false;
            }
        };

        ByteBuffer notObfuscated = obfuscator.obfuscate(ByteBuffer.wrap(message.getBytes()));

        Assertions.assertThat(message).isEqualTo(new String(notObfuscated.array()));

    }
}
