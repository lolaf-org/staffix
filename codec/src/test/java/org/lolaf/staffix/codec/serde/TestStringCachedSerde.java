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
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSessionId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;


class TestStringCachedSerde {

    @Test
    void testDeserialize() {
        SerDe.DeserializationContext ctx = new TestingDeserializationContext();
        String toDeserialize = "testStringToSerialize";
        ctx.setup(toDeserialize.getBytes());

        FixSessionId fixSessionId = mock(FixSessionId.class);

        String deserialized = StringCachedSerde.instance(fixSessionId).deserialize(ctx);
        assertThat(deserialized).isEqualTo(toDeserialize)
                .hasSameHashCodeAs(toDeserialize);

        String deserialized2 = StringCachedSerde.instance(fixSessionId).deserialize(ctx);
        assertThat(deserialized2).isSameAs(deserialized);
    }

    @Test
    void testDeserializeForDifferentFixSessionAreTheSame() {
        SerDe.DeserializationContext ctx = new TestingDeserializationContext();
        String toDeserialize = "testStringToSerializeForDifferentSession";
        ctx.setup(toDeserialize.getBytes());

        StringCachedSerde serde1 = StringCachedSerde.instance(mock(FixSessionId.class));
        StringCachedSerde serde2 = StringCachedSerde.instance(mock(FixSessionId.class));

        assertThat(serde1).isNotEqualTo(serde2);

        // StringCachedSerde uses string.intern so same instance should be received for different cached instance
        String deserialized = serde1.deserialize(ctx);
        assertThat(deserialized).isEqualTo(toDeserialize).hasSameHashCodeAs(toDeserialize);

        String deserialized2 = serde2.deserialize(ctx);
        assertThat(deserialized2).isSameAs(deserialized);
    }

}