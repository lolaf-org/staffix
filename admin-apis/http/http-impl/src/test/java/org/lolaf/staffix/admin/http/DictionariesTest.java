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
package org.lolaf.staffix.admin.http;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.admin.http.dto.DictionaryRef;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.api.version.FixRegularVersion;

import static org.assertj.core.api.Assertions.assertThat;

class DictionariesTest {

    private static final FixDictionaryId ALPHA_44 = FixDictionaryId.of("alpha", FixRegularVersion.VERSION_44);

    @Test
    void aSessionDecodesWithTheDictionaryOfItsVersionAndId() {
        FixSessionId fix44 = FixSessionId.of("trading", FixRegularVersion.VERSION_44, "US", "ALPHA");

        assertThat(Dictionaries.of(fix44, ALPHA_44)).extracting(DictionaryRef::getId).containsExactly("alpha-FIX.4.4");
        assertThat(Dictionaries.of(fix44, ALPHA_44).get(0).getHash()).hasSize(64);
    }

    @Test
    void aFixtSessionDecodesWithTheTransportDictionaryThenTheApplicationOne() {
        FixSessionId fixt = FixSessionId.ofFIXT11("trading", FixApplVerID.FIX44, "US", "ALPHA");

        assertThat(Dictionaries.of(fixt, ALPHA_44)).extracting(DictionaryRef::getId).containsExactly("FIXT.1.1", "alpha-FIX.4.4");
    }

    @Test
    void aDictionaryNotShippedIsLeftOut() {
        FixSessionId fix44 = FixSessionId.of("trading", FixRegularVersion.VERSION_44, "US", "ALPHA");

        assertThat(Dictionaries.of(fix44, FixDictionaryId.of("beta", FixRegularVersion.VERSION_44))).isEmpty();
    }

    @Test
    void anIdCannotReachOtherResources() {
        assertThat(Dictionaries.get("../logback")).isEmpty();
        assertThat(Dictionaries.get("..")).isEmpty();
        assertThat(Dictionaries.get("sub/alpha-FIX.4.4")).isEmpty();
    }
}
