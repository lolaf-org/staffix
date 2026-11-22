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
import org.lolaf.staffix.api.ha.FixEngineHa.EngineAccess;
import org.lolaf.staffix.api.ha.HaStatus.SessionSettingsDifference;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;
import org.lolaf.staffix.ha.SessionSettingsDigests.Digest;
import org.lolaf.staffix.ha.wire.WireFormatException;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSessionSettingsReplica {

    private static final ByteBuffer NO_STATE = ByteBuffer.allocate(0);

    private final SessionSettingsComparison comparison;
    private final SessionSettingsReplica replica;

    TestSessionSettingsReplica() {
        FixSessionsSettingsStore store = mock(FixSessionsSettingsStore.class);
        when(store.getInstanceId()).thenReturn("files");
        when(store.getSettings()).thenReturn(List.of());
        EngineAccess engine = mock(EngineAccess.class);
        when(engine.getFixSessionsSettingsStores()).thenReturn(List.of(store));
        this.comparison = new SessionSettingsComparison(engine);
        comparison.setApplyExecutor(apply -> {
        });
        this.replica = new SessionSettingsReplica(comparison);
    }

    private static Digest digest(String id) {
        return new Digest("files", FixSessionType.ACCEPTOR, id, "abc", "", "", false);
    }

    private void record(byte contentType, Digest digest, boolean snapshot) {
        ByteBuffer payload = SessionSettingsRecords.encode(digest);
        if (snapshot) {
            replica.snapshotRecord(contentType, 1, payload, 0, payload.remaining());
        } else {
            replica.apply(contentType, 1, payload, 0, payload.remaining());
        }
    }

    private List<String> missingHere() {
        return comparison.getDifferences().stream().filter(difference -> difference.getKind() == SessionSettingsDifference.Kind.MISSING_HERE)
                .map(SessionSettingsDifference::getFixSessionId).collect(Collectors.toList());
    }

    @Test
    void theLeadersSetIsWholeOnceASnapshotEnds() {
        record(SessionSettingsRecords.DIGEST, digest("before"), false);
        replica.snapshotBegin(NO_STATE, 0, 0);
        record(SessionSettingsRecords.DIGEST, digest("a"), true);
        record(SessionSettingsRecords.DIGEST, digest("b"), true);
        record(SessionSettingsRecords.FORGOTTEN, digest("b"), true);
        assertThat(comparison.getDifferences()).as("not compared before the end").isEmpty();

        replica.snapshotEnd(NO_STATE, 0, 0);

        assertThat(missingHere()).containsExactly("a");
    }

    @Test
    void recordsAfterTheSnapshotKeepItCurrent() {
        replica.snapshotBegin(NO_STATE, 0, 0);
        replica.snapshotEnd(NO_STATE, 0, 0);

        record(SessionSettingsRecords.DIGEST, digest("a"), false);
        assertThat(missingHere()).containsExactly("a");

        record(SessionSettingsRecords.FORGOTTEN, digest("a"), false);
        assertThat(comparison.getDifferences()).isEmpty();
    }

    @Test
    void whatTheStreamNeverCarriesIsTheLeadersError() {
        ByteBuffer payload = SessionSettingsRecords.encode(digest("a"));

        assertThatThrownBy(() -> replica.apply((byte) 9, 1, payload, 0, payload.remaining()))
                .isInstanceOf(WireFormatException.class).hasMessageContaining("record type 9");
        assertThatThrownBy(() -> replica.apply(SessionSettingsRecords.DIGEST, 1, payload, 0, payload.remaining() - 1))
                .isInstanceOf(WireFormatException.class);
        assertThatThrownBy(() -> replica.snapshotBegin(ByteBuffer.allocate(8), 0, 8))
                .isInstanceOf(WireFormatException.class).hasMessageContaining("no state");
    }
}
