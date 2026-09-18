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

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.serde.SerDe;

import java.nio.ByteBuffer;
import java.time.OffsetTime;

class TestTzTimeOnlySerde {

    SerDe.DeserializationContext ctx = new TestingDeserializationContext();

    @Test
    void testDeserializeUtcTimeOnly() {
        ctx.setup("12:00:01+10:05".getBytes());
        Assertions.assertThat(TzTimeOnlySerde.instance().deserialize(ctx)).hasToString("12:00:01+10:05");

        ctx.setup("12:00:01+10".getBytes());
        Assertions.assertThat(TzTimeOnlySerde.instance().deserialize(ctx)).hasToString("12:00:01+10:00");

        ctx.setup("12:00+10".getBytes());
        Assertions.assertThat(TzTimeOnlySerde.instance().deserialize(ctx)).hasToString("12:00+10:00");

        ctx.setup("12:00-10".getBytes());
        Assertions.assertThat(TzTimeOnlySerde.instance().deserialize(ctx)).hasToString("12:00-10:00");

        ctx.setup("12:00:01+10".getBytes());
        Assertions.assertThat(TzTimeOnlySerde.instance().deserialize(ctx)).hasToString("12:00:01+10:00");

        ctx.setup("12:00:01-10:05".getBytes());
        Assertions.assertThat(TzTimeOnlySerde.instance().deserialize(ctx)).hasToString("12:00:01-10:05");

        ctx.setup("12:00:00Z".getBytes());
        Assertions.assertThat(TzTimeOnlySerde.instance().deserialize(ctx)).hasToString("12:00Z");
    }

    @Test
    void testSerializeUtcTimeOnly() {
        ByteBuffer out = ByteBuffer.allocate(20);
        TzTimeOnlySerde.instance().serialize(out, OffsetTime.parse("12:00:01+10:05"));

        byte[] value = new byte[out.position()];
        out.flip().get(value);
        Assertions.assertThat(new String(value)).isEqualTo("12:00:01+10:05");

        out.clear();
        TzTimeOnlySerde.instance().serialize(out, OffsetTime.parse("12:00:01Z"));
        value = new byte[out.position()];
        out.flip().get(value);
        Assertions.assertThat(new String(value)).isEqualTo("12:00:01Z");

        out.clear();
        TzTimeOnlySerde.instance().serialize(out, OffsetTime.parse("12:00Z"));
        value = new byte[out.position()];
        out.flip().get(value);
        Assertions.assertThat(new String(value)).isEqualTo("12:00:00Z");

    }
}
