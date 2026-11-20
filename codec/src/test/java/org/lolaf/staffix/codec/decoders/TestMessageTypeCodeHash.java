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
package org.lolaf.staffix.codec.decoders;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.serde.Hashing;
import org.lolaf.staffix.api.version.FixRegularVersion;

import static org.assertj.core.api.Assertions.assertThat;

class TestMessageTypeCodeHash {

    @Test
    void everyGeneratedMessageTypeHashesItsCodeAndIsFoundByIt() {
        MessageTypeRegistry registry = MessageTypeRegistry.Registry.getInstance(
                FixDictionaryId.of("tests", FixRegularVersion.VERSION_44));

        assertThat(registry.getMessageTypes()).isNotEmpty().allSatisfy(type -> {
            assertThat(type.codeHash()).as(type.code()).isEqualTo(Hashing.hash(type.code()));
            assertThat(registry.find(type.codeHash())).isSameAs(type);
        });
    }

    @Test
    void aMessageTypeBuiltByHandHashesItsCode() {
        assertThat(MessageType.of("AE", false).codeHash()).isEqualTo(Hashing.hash("AE"));
    }
}
