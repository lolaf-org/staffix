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
package org.lolaf.staffix.ha;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.serde.Hashing;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TestMessageTypeHash {

    private static ByteBuffer bytes(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void hashesTheMsgTypeAsTheParserDoes() {
        ByteBuffer message = bytes("8=FIX.4.4\u00019=12\u000135=AE\u000149=S\u000110=000\u0001");

        assertThat(MessageTypeHash.of(message, 0, message.limit())).isEqualTo(Hashing.hash("AE"));
    }

    @Test
    void readsOnlyTheRangeGiven() {
        ByteBuffer message = bytes("junk8=FIX.4.4\u00019=5\u000135=D");

        assertThat(MessageTypeHash.of(message, 4, message.limit() - 4)).as("value ending the range")
                .isEqualTo(Hashing.hash("D"));
        assertThat(MessageTypeHash.of(message, 4, 14)).as("cut before tag 35").isEqualTo(UnencryptedMessageTypes.NO_MESSAGE_TYPE);
    }

    @Test
    void aTagEndingIn35IsNotMsgType() {
        ByteBuffer message = bytes("8=FIX.4.4\u00019=5\u0001135=X\u000135=8\u0001");

        assertThat(MessageTypeHash.of(message, 0, message.limit())).isEqualTo(Hashing.hash("8"));
    }

    @Test
    void bytesWithoutMsgTypeHaveNone() {
        ByteBuffer message = bytes("not a FIX message");

        assertThat(MessageTypeHash.of(message, 0, message.limit())).isEqualTo(UnencryptedMessageTypes.NO_MESSAGE_TYPE);
    }
}
