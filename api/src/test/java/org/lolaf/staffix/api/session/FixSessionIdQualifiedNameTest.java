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
import org.lolaf.staffix.api.version.FixRegularVersion;

import static org.assertj.core.api.Assertions.assertThat;

class FixSessionIdQualifiedNameTest {

    @Test
    void joinsGroupAndName() {
        assertThat(sessionId("alpha", "trading-drp", "ALPHA").getQualifiedName()).isEqualTo("alpha.trading-drp");
        assertThat(sessionId("alpha", "trading", "ALPHA").forFileName(".bin")).isEqualTo("alpha.trading.bin");
    }

    @Test
    void startsItsTextWithTheQualifiedName() {
        assertThat(sessionId("alpha", "trading", "ALPHA")).hasToString("alpha.trading:VERSION_44:US->ALPHA");
    }

    @Test
    void usesTheDefaultGroupWhenNoneIsGiven() {
        assertThat(FixSessionId.of("trading", FixRegularVersion.VERSION_44, "US", "THEM").getQualifiedName())
                .isEqualTo("default.trading");
    }

    @Test
    void takesANullGroupAsTheDefaultOne() {
        assertThat(sessionId(null, "trading", "THEM").getQualifiedName()).isEqualTo("default.trading");
    }

    @Test
    void escapesWhatIsNotSafeInAFileName() {
        assertThat(sessionId("clients/eu", "trading desk", "EU").getQualifiedName()).isEqualTo("clients%2Feu.trading%20desk");
        assertThat(sessionId("café", "trading", "CAFE").getQualifiedName()).isEqualTo("caf%C3%A9.trading");
    }

    @Test
    void neverGivesTwoSessionsTheSameKey() {
        assertThat(sessionId("a.b", "c", "X").getQualifiedName()).isNotEqualTo(sessionId("a", "b.c", "X").getQualifiedName());
    }

    @Test
    void internsSessionsDifferingOnlyByGroupApart() {
        FixSessionId alpha = sessionId("alpha", "trading", "SAME");
        FixSessionId beta = sessionId("beta", "trading", "SAME");

        assertThat(alpha).isNotSameAs(beta);
        assertThat(alpha.getGroup()).isEqualTo("alpha");
        assertThat(beta.getGroup()).isEqualTo("beta");
        assertThat(sessionId("alpha", "trading", "SAME")).isSameAs(alpha);
    }

    private static FixSessionId sessionId(String group, String name, String targetCompId) {
        return FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                .name(name).group(group).senderCompID("US").targetCompID(targetCompId).build());
    }
}
