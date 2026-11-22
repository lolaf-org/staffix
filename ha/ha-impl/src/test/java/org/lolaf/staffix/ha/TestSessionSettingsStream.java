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
import org.lolaf.staffix.ha.leader.Followers;
import org.lolaf.staffix.ha.log.LogFullWait;
import org.lolaf.staffix.ha.log.ReplicationLog;
import org.lolaf.staffix.ha.spi.SnapshotSource;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSessionSettingsStream {

    private final ReplicationLog log;
    private final SessionSettingsStream stream;
    private final FixSessionsSettingsStore store;

    TestSessionSettingsStream() {
        this.log = new ReplicationLog(64 * 1024);
        this.stream = new SessionSettingsStream(0, log, new LogFullWait(Duration.ofSeconds(1)), new Followers());
        this.store = mock(FixSessionsSettingsStore.class);
        when(store.getInstanceId()).thenReturn("files");
    }

    private static FixSessionSettings session(String sender, Duration timeout) {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of("s", FixRegularVersion.VERSION_44, sender, "TARGET"))
                .fixSessionType(FixSessionType.ACCEPTOR)
                .logInOrOutResponseTimeout(timeout)
                .build();
    }

    private void publish(FixSessionSettings... settings) {
        when(store.getSettings()).thenReturn(List.of(settings));
        stream.publish(List.of(store), replicated -> false);
    }

    private List<String> snapshot() {
        SnapshotSource source = stream.newSnapshotSource();
        long last = source.captureState(ByteBuffer.allocate(16));
        List<String> records = new ArrayList<>();
        source.read(0, last, (contentType, flags, position, payload) -> {
            SessionSettingsDigests.Digest digest = SessionSettingsRecords.decode(contentType, payload, payload.position(), payload.remaining());
            records.add(position + " " + contentType + " " + digest.getFixSessionId());
            return true;
        });
        return records;
    }

    @Test
    void onlyWhatChangedIsAppended() {
        publish(session("A", Duration.ofSeconds(1)), session("B", Duration.ofSeconds(1)));
        long tail = log.getTailOffset();

        publish(session("A", Duration.ofSeconds(1)), session("B", Duration.ofSeconds(1)));
        assertThat(log.getTailOffset()).as("nothing changed").isEqualTo(tail);

        publish(session("A", Duration.ofSeconds(1)), session("B", Duration.ofSeconds(2)));
        assertThat(log.getTailOffset()).isGreaterThan(tail);
        assertThat(snapshot()).as("each session's latest, by version").hasSize(2).last().asString().startsWith("3 1 ");
    }

    @Test
    void aSessionGoneIsForgottenUnlessRemoved() {
        FixSessionSettings removed = session("A", Duration.ofSeconds(1));
        publish(removed, session("B", Duration.ofSeconds(1)));
        stream.onRemoved("files", removed);
        when(store.getSettings()).thenReturn(List.of());
        stream.publish(List.of(store), replicated -> true);

        assertThat(snapshot()).extracting(record -> record.split(" ")[1])
                .containsExactlyInAnyOrder(String.valueOf(SessionSettingsRecords.REMOVED), String.valueOf(SessionSettingsRecords.FORGOTTEN));
    }

    @Test
    void aRoundReadsUpToTheVersionItCaptured() {
        publish(session("A", Duration.ofSeconds(1)), session("B", Duration.ofSeconds(1)));
        SnapshotSource source = stream.newSnapshotSource();
        long roundEnd = source.captureState(ByteBuffer.allocate(16));

        publish(session("A", Duration.ofSeconds(2)), session("B", Duration.ofSeconds(1)));

        List<Long> positions = new ArrayList<>();
        source.read(0, roundEnd, (contentType, flags, position, payload) -> positions.add(position));
        assertThat(positions).as("A moved past the round, for the next one").containsExactly(2L);
        assertThat(source.captureState(ByteBuffer.allocate(16))).isEqualTo(3);
    }
}
