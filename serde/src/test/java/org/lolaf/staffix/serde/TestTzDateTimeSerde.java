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
import org.lolaf.staffix.api.serde.SerDe;

import java.util.concurrent.TimeUnit;

class TestTzDateTimeSerde {

    SerDe.DeserializationContext ctx = new TestingDeserializationContext();

    @Test
    void testDeserializeUtcTimeOnly() {

        ctx.setup("20241110-12:10:10+10:05".getBytes());
        Assertions.assertThat(TzDateTimeSerde.instance().deserialize(ctx)).hasToString("2024-11-10T12:10:10+10:05");

        ctx.setup("20241110-12:10:10+10".getBytes());
        Assertions.assertThat(TzDateTimeSerde.instance().deserialize(ctx)).hasToString("2024-11-10T12:10:10+10:00");

        ctx.setup("20241110-12:10:10Z".getBytes());
        Assertions.assertThat(TzDateTimeSerde.instance().deserialize(ctx)).hasToString("2024-11-10T12:10:10Z");

        ctx.setup("20241110-12:10:10.123+10:05".getBytes());
        Assertions.assertThat(TzDateTimeSerde.instance().deserialize(ctx)).hasToString("2024-11-10T12:10:10.123+10:05");

        ctx.setup("20241110-12:10:10.123456+10:05".getBytes());
        Assertions.assertThat(TzDateTimeSerde.instance().deserialize(ctx)).hasToString("2024-11-10T12:10:10.123456+10:05");

        ctx.setup("20241110-12:10:10.123456789+10:05".getBytes());
        Assertions.assertThat(TzDateTimeSerde.instance().deserialize(ctx)).hasToString("2024-11-10T12:10:10.123456789+10:05");

        ctx.setup("20241110-12:10:10.123Z".getBytes());
        Assertions.assertThat(TzDateTimeSerde.instance().deserialize(ctx)).hasToString("2024-11-10T12:10:10.123Z");

        ctx.setup("20241110-12:10:10.123345Z".getBytes());
        Assertions.assertThat(TzDateTimeSerde.instance().deserialize(ctx)).hasToString("2024-11-10T12:10:10.123345Z");


    }

    @Test
    void testSerializeUtcTimeOnly() {

        ctx.setup("20241110-12:10:10.123123123+10:05".getBytes());

        byte[] value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.SECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10+10:05");
        value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.MILLISECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10.123+10:05");
        value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.MICROSECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10.123123+10:05");
        value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10.123123123+10:05");

        ctx.setup("20241110-12:10:10.123123123+10".getBytes());

        value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.SECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10+10");
        value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.MILLISECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10.123+10");
        value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.MICROSECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10.123123+10");
        value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10.123123123+10");

        ctx.setup("20241110-12:10:10.123123123Z".getBytes());

        value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.SECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10Z");
        value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.MILLISECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10.123Z");
        value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.MICROSECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10.123123Z");
        value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10.123123123Z");

        ctx.setup("20241110-12:10:10Z".getBytes());

        value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.SECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10Z");
        value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.MILLISECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10.000Z");
        value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.MICROSECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10.000000Z");
        value = TzDateTimeSerde.instance().serialize(TzDateTimeSerde.instance().deserialize(ctx), TimeUnit.NANOSECONDS);
        Assertions.assertThat(new String(value)).isEqualTo("20241110-12:10:10.000000000Z");

    }
}
