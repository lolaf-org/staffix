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
package org.lolaf.staffix.stores.loggers.core;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.msg.MessageType;

import java.nio.ByteBuffer;

class TestPasswordObfuscator {

    @Test
    void testObfuscate() {

        String message = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y554=PASSWORD10=249";

        ByteBuffer obfuscated = PasswordObfuscator.getInstance().obfuscate(ByteBuffer.wrap(message.getBytes()));

        Assertions.assertThat(message.replace("PASSWORD", "********")).isEqualTo(new String(obfuscated.array()));
    }

    @Test
    void testObfuscateWithNoPassword() {

        String message = "8=FIX.4.49=8335=A34=149=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=249";

        ByteBuffer notObfuscated = PasswordObfuscator.getInstance().obfuscate(ByteBuffer.wrap(message.getBytes()));

        Assertions.assertThat(message).isEqualTo(new String(notObfuscated.array()));

    }

    @Test
    void testIsForMessageType() {

        Assertions.assertThat(PasswordObfuscator.getInstance().isForMessageType(MessageType.of("A", true))).isTrue();
    }

    @Test
    void testIsNotForMessageType() {

        Assertions.assertThat(PasswordObfuscator.getInstance().isForMessageType(MessageType.of("A", false))).isFalse();

        Assertions.assertThat(PasswordObfuscator.getInstance().isForMessageType(MessageType.of("Z", true))).isFalse();
    }
}
