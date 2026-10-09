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
package org.lolaf.staffix.impl;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.api.version.FixRegularVersion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestApplicationDictionary {

    private static final FixDictionaryId ALPHA_44 = FixDictionaryId.of("alpha", FixRegularVersion.VERSION_44);

    @Test
    void aSessionOnTheApplicationsVersionGetsItsDictionary() {
        FixSessionId fix44 = FixSessionId.of("trading", FixRegularVersion.VERSION_44, "US", "ALPHA");

        assertThat(ApplicationDictionary.require(ALPHA_44, "orders", fix44)).isEqualTo(ALPHA_44);
    }

    @Test
    void aFixtSessionIsMatchedOnItsDefaultApplVerID() {
        FixSessionId fixt44 = FixSessionId.ofFIXT11("trading", FixApplVerID.FIX44, "US", "ALPHA");

        assertThat(ApplicationDictionary.require(ALPHA_44, "orders", fixt44)).isEqualTo(ALPHA_44);
    }

    @Test
    void aSessionOnAnotherVersionIsRefused() {
        FixSessionId fix42 = FixSessionId.of("trading", FixRegularVersion.VERSION_42, "US", "ALPHA");

        assertThatThrownBy(() -> ApplicationDictionary.require(ALPHA_44, "orders", fix42))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'orders' speaks FIX.4.4")
                .hasMessageContaining("is on FIX.4.2");
    }

    @Test
    void aFixtSessionCarryingAnotherVersionIsRefused() {
        FixSessionId fixt50sp2 = FixSessionId.ofFIXT11("trading", FixApplVerID.FIX50SP2, "US", "ALPHA");

        assertThatThrownBy(() -> ApplicationDictionary.require(ALPHA_44, "orders", fixt50sp2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'orders' speaks FIX.4.4");
    }

    @Test
    void anApplicationDeclaringNoDictionaryIsRefused() {
        FixSessionId fix44 = FixSessionId.of("trading", FixRegularVersion.VERSION_44, "US", "ALPHA");

        assertThatThrownBy(() -> ApplicationDictionary.require(null, "orders", fix44))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'orders' declares no dictionary");
    }
}
