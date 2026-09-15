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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSessionId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestDecimalFloatCachedSerde {

    @Test
    void testDeserialize() {
        SerDe.DeserializationContext ctx = new TestingDeserializationContext();
        ctx.setup("10000.123".getBytes());

        FixSessionId fixSessionId = mock(FixSessionId.class);

        DecimalFloat toCompare = DecimalFloat.of(10000123, (byte) 3);
        DecimalFloat deserialized = DecimalFloatCachedSerde.instance(fixSessionId).deserialize(ctx);
        assertThat(deserialized).isEqualTo(toCompare).hasSameHashCodeAs(toCompare);

        DecimalFloat deserialized2 = DecimalFloatCachedSerde.instance(fixSessionId).deserialize(ctx);
        assertThat(deserialized2).isSameAs(deserialized);
    }

    @Test
    void testDeserializeForDifferentFixSessionAreNotTheSame() {
        SerDe.DeserializationContext ctx = new TestingDeserializationContext();
        String toDeserialize = "10000.1234";
        ctx.setup(toDeserialize.getBytes());

        DecimalFloatCachedSerde serde1 = DecimalFloatCachedSerde.instance(mock(FixSessionId.class));
        DecimalFloatCachedSerde serde2 = DecimalFloatCachedSerde.instance(mock(FixSessionId.class));

        assertThat(serde1).isNotEqualTo(serde2);
        assertThat(serde1.deserialize(ctx)).isNotSameAs(serde2.deserialize(ctx));
    }

}