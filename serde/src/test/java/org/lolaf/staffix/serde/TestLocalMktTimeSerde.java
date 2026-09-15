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
package org.lolaf.staffix.serde;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.serde.IllegalFieldValueException;
import org.lolaf.staffix.api.serde.SerDe;

import java.nio.ByteBuffer;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertThrows;

class TestLocalMktTimeSerde {

    SerDe.DeserializationContext ctx = new TestingDeserializationContext();

    @Test
    void testDeserializeLocalMktTime() {
        ctx.setup("12:00:01".getBytes());
        Assertions.assertThat(LocalMktTimeSerde.instance().deserialize(ctx)).hasToString("12:00:01");

        ctx.setup("23:00:01".getBytes());
        Assertions.assertThat(LocalMktTimeSerde.instance().deserialize(ctx)).hasToString("23:00:01");

        assertThrows(IllegalFieldValueException.class, () ->
                LocalMktTimeSerde.instance().deserialize(ctx.setup("52:00:01".getBytes())));
    }

    @Test
    void testSerializeLocalMktTime() {
        ByteBuffer out = ByteBuffer.allocate(20);
        LocalMktTimeSerde.instance().serialize(out, LocalTime.parse("12:00:01"));

        byte[] value = new byte[out.position()];
        out.flip().get(value);
        Assertions.assertThat(new String(value)).isEqualTo("12:00:01");

        out.clear();
        LocalMktTimeSerde.instance().serialize(out, LocalTime.parse("23:00:01"));
        value = new byte[out.position()];
        out.flip().get(value);
        Assertions.assertThat(new String(value)).isEqualTo("23:00:01");
    }
}
