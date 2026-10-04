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
package org.lolaf.staffix.api.application;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestFixApplicationSessionSettingDescriptor {

    @Test
    void aSettingReadBeforeTheApplicationDeclaresItSecretBecomesSecret() {
        FixApplicationSessionSettingDescriptor read = FixApplicationSessionSettingDescriptor.of("alpha.pin");

        FixApplicationSessionSettingDescriptor.secret("alpha.pin", "The PIN the venue asks for at Logon");

        assertThat(read.isSecret()).isTrue();
        assertThat(read.getDescription()).isEqualTo("The PIN the venue asks for at Logon");
    }

    @Test
    void aSecretSettingStaysSecret() {
        FixApplicationSessionSettingDescriptor.secret("beta.password", null);

        assertThat(FixApplicationSessionSettingDescriptor.of("beta.password", "Logon password").isSecret()).isTrue();
    }

    @Test
    void aSettingKeepsItsPlaceInAMapOnceDescribed() {
        Map<FixApplicationSessionSettingDescriptor, String> values = new HashMap<>();
        values.put(FixApplicationSessionSettingDescriptor.of("gamma.account"), "ACC-1");

        FixApplicationSessionSettingDescriptor.secret("gamma.account", "The account orders are booked to");

        assertThat(values).containsEntry(FixApplicationSessionSettingDescriptor.of("gamma.account"), "ACC-1");
    }
}
