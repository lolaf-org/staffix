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
package org.lolaf.staffix.api.session;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.api.version.FixtVersion;

import static org.assertj.core.api.Assertions.assertThat;

class FixSessionIdMatchTest {

    private static final FixSessionId FIXT_SESSION =
            FixSessionId.ofFIXT11("id", FixApplVerID.FIX50SP2, "testSender", "testTarget");

    @Test
    void matchesWhenApplVerIdIsTheConfiguredOne() {
        assertThat(FIXT_SESSION.matches(FixtVersion.FIXT_11, FixApplVerID.FIX50SP2,
                "testSender", null, null, "testTarget", null, null)).isTrue();
    }

    @Test
    void doesNotMatchWhenApplVerIdDiffers() {
        assertThat(FIXT_SESSION.matches(FixtVersion.FIXT_11, FixApplVerID.FIX50SP1,
                "testSender", null, null, "testTarget", null, null)).isFalse();
    }

    @Test
    void doesNotMatchWhenApplVerIdIsUnknown() {
        assertThat(FIXT_SESSION.matches(FixtVersion.FIXT_11, null,
                "testSender", null, null, "testTarget", null, null)).isFalse();
    }

    @Test
    void relaxedMatchIgnoresApplVerIdWhenCompIdsMatch() {
        // both a different applVerId and an unknown (null) applVerId still resolve the session by comp ids
        assertThat(FIXT_SESSION.matchesIgnoringApplVerId(FixtVersion.FIXT_11,
                "testSender", null, null, "testTarget", null, null)).isTrue();
    }

    @Test
    void relaxedMatchStillRequiresMatchingCompIds() {
        assertThat(FIXT_SESSION.matchesIgnoringApplVerId(FixtVersion.FIXT_11,
                "testSender", null, null, "SOMEONE_ELSE", null, null)).isFalse();
    }
}
