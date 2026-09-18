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
package org.lolaf.staffix.codec.serde;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.serde.SerDe;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

class TestDecimalFloatTLSerde {

    private final DecimalFloatTLSerde serde = DecimalFloatTLSerde.instance();

    @Test
    void testDeserializeBasics() {
        assertThat(deserialize("0")).isEqualTo(DecimalFloat.of(0, (byte) 0));
        assertThat(deserialize("123.45")).isEqualTo(DecimalFloat.of(12345, (byte) 2));
        assertThat(deserialize("-0.001")).isEqualTo(DecimalFloat.of(-1, (byte) 3));
    }

    @Test
    void testSerializeBasics() {
        assertThat(serialize(DecimalFloat.of(0, (byte) 0))).isEqualTo("0");
        assertThat(serialize(DecimalFloat.of(12345, (byte) 2))).isEqualTo("123.45");
        assertThat(serialize(DecimalFloat.of(-1, (byte) 3))).isEqualTo("-0.001");
    }

    @Test
    void testDeserializeReturnsSameInstanceOnSubsequentCalls() {
        DecimalFloat first = deserialize("1.5");
        DecimalFloat second = deserialize("2.5");
        assertThat(first).isSameAs(second);
        assertThat(second.getUnscaledValue()).isEqualTo(25);
        assertThat(second.getScale()).isEqualTo((byte) 1);
    }

    private DecimalFloat deserialize(String s) {
        SerDe.DeserializationContext ctx = new TestingDeserializationContext();
        ctx.setup(s.getBytes());
        return serde.deserialize(ctx);
    }

    private String serialize(DecimalFloat value) {
        ByteBuffer out = ByteBuffer.allocate(256);
        serde.serialize(out, value);
        byte[] bytes = new byte[out.position()];
        out.flip().get(bytes);
        return new String(bytes);
    }
}
