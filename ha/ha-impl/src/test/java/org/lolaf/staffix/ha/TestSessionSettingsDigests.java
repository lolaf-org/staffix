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
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSessionSettingsDigests {

    private static final String POINTER = "/logInOrOutResponseTimeout";

    private static FixSessionSettings session(Duration timeout) {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of("s", FixRegularVersion.VERSION_44, "SENDER", "TARGET"))
                .fixSessionType(FixSessionType.ACCEPTOR)
                .logInOrOutResponseTimeout(timeout)
                .build();
    }

    private static String digest(FixSessionSettings settings) {
        FixSessionsSettingsStore store = mock(FixSessionsSettingsStore.class);
        when(store.getInstanceId()).thenReturn("files");
        when(store.getSettings()).thenReturn(List.of(settings));
        return SessionSettingsDigests.of(List.of(store)).get(0).getValue();
    }

    @Test
    void differentValuesDiffer() {
        assertThat(digest(session(Duration.ofSeconds(1)))).isNotEqualTo(digest(session(Duration.ofSeconds(2))));
    }

    @Test
    void aPlaceholderResolvingDifferentlyOnTwoNodesReadsTheSame() {
        Map<String, String> placeholders = Map.of(POINTER, "${env:TIMEOUT}");

        assertThat(digest(session(Duration.ofSeconds(1)).toBuilder().placeholders(placeholders).build()))
                .isEqualTo(digest(session(Duration.ofSeconds(2)).toBuilder().placeholders(placeholders).build()));
    }

    @Test
    void anotherExpressionDiffers() {
        assertThat(digest(session(Duration.ofSeconds(1)).toBuilder().placeholders(Map.of(POINTER, "${env:A}")).build()))
                .isNotEqualTo(digest(session(Duration.ofSeconds(1)).toBuilder().placeholders(Map.of(POINTER, "${env:B}")).build()));
    }

    @Test
    void nothingIsPublishedUntilAsked() {
        SessionSettingsDigests digests = new SessionSettingsDigests();

        assertThat(digests.getPublished().getGeneration()).isZero();
        digests.publish(List.of(), store -> true);
        assertThat(digests.getPublished().getGeneration()).isEqualTo(1);
    }
}
