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
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestUnencryptedMessageTypes {

    @Test
    void noneByDefault() {
        UnencryptedMessageTypes unencrypted = UnencryptedMessageTypes.of(HaSessionSettings.builder().build().getUnencryptedMessageTypes());

        assertThat(unencrypted).isSameAs(UnencryptedMessageTypes.NONE);
        assertThat(unencrypted.isUnencrypted(Hashing.hash("S"))).isFalse();
    }

    @Test
    void theTypesListedAreUnencryptedAndNoOther() {
        UnencryptedMessageTypes unencrypted = UnencryptedMessageTypes.of(List.of("W", "S", "X"));

        assertThat(unencrypted.isUnencrypted(Hashing.hash("S"))).isTrue();
        assertThat(unencrypted.isUnencrypted(Hashing.hash("W"))).isTrue();
        assertThat(unencrypted.isUnencrypted(Hashing.hash("X"))).isTrue();
        assertThat(unencrypted.isUnencrypted(Hashing.hash("D"))).isFalse();
    }

    @Test
    void everyTypeIsUnencryptedWithAllButLogonAndAnUnknownType() {
        UnencryptedMessageTypes unencrypted = UnencryptedMessageTypes.of(List.of(HaSessionSettings.ALL_MESSAGE_TYPES));

        assertThat(unencrypted.isUnencrypted(Hashing.hash("D"))).isTrue();
        assertThat(unencrypted.isUnencrypted(Hashing.hash("A"))).as("Logon carries the password").isFalse();
        assertThat(unencrypted.isUnencrypted(UnencryptedMessageTypes.NO_MESSAGE_TYPE)).as("an untyped message").isFalse();
    }
}
