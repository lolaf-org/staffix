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
package org.lolaf.staffix.ha.follower;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.wire.CloseReason;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.WireFormat;

import static org.assertj.core.api.Assertions.assertThat;

class TestSessionBinder extends LeaderConnectionHarness {

    @Test
    void aSessionConfiguredHereIsReplicatedIntoItsOwnStartedStoreOnceASnapshotFillsIt() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN));

        assertThat(engine.store(MAIN).started).isTrue();
        assertThat(sent.frames).containsExactly("SESSION_ANSWER epoch=7 key=0 SNAPSHOT_NEEDED", "ACK epoch=7 applied=-1 inSync=false");
    }

    @Test
    void aSessionConfiguredHereAfterItWasAnnouncedIsBoundAndCaughtUp() {
        FixSessionSettings late = RecordingEngine.session("late", FixSessionType.INITIATOR);
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(3, late));
        sent.frames.clear();

        engine.with(late);
        read(event(EPOCH, 0, 3, StoreEventType.STORE_INCOMING_SEQ_NUM, 2, null));

        assertThat(engine.store(late).started).isTrue();
        assertThat(sent.frames).contains("SESSION_ANSWER epoch=7 key=3 SNAPSHOT_NEEDED");
    }

    @Test
    void aSessionUnknownHereIsReportedAndNotReplicated() {
        FixSessionSettings other = RecordingEngine.session("other", FixSessionType.INITIATOR);
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(3, other),
                event(EPOCH, 0, 3, StoreEventType.STORE_INCOMING_SEQ_NUM, 2, null));

        assertThat(engine.stores).isEmpty();
        assertThat(sent.frames).as("a session it should hold is missing, so it is not in sync")
                .containsExactly("SESSION_ANSWER epoch=7 key=3 UNKNOWN_SESSION", "ACK epoch=7 applied=0 inSync=false");
    }

    @Test
    void aSessionWithAnotherReplicationModeHereIsRefusedUntilTheSettingsHereAgree() {
        FixSessionSettings sync = MAIN.toBuilder()
                .highAvailability(FixSessionSettings.HaSessionSettings.builder().replicationMode(ReplicationMode.SYNC_APPLIED).build())
                .build();
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, sync));

        assertThat(engine.store(MAIN)).as("no store opened").isNull();
        assertThat(sent.frames).containsExactly("SESSION_ANSWER epoch=7 key=0 MODE_MISMATCH", "ACK epoch=7 applied=-1 inSync=false");
        sent.frames.clear();

        engine.replace(sync);
        read(event(EPOCH, 0, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 2, null));

        assertThat(engine.store(sync).started).isTrue();
        assertThat(sent.frames).contains("SESSION_ANSWER epoch=7 key=0 SNAPSHOT_NEEDED");
    }

    @Test
    void aSessionWhoseStorageIsTheLeadersOwnIsRefusedNeitherStartedNorWrittenTo() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN, "follower-storage"),
                event(EPOCH, 0, 0, StoreEventType.STORE_INCOMING_SEQ_NUM, 2, null));

        assertThat(engine.store(MAIN).started).isFalse();
        assertThat(engine.store(MAIN).writes).isEmpty();
        assertThat(sent.frames).containsExactly("SESSION_ANSWER epoch=7 key=0 SHARED_STORAGE", "ACK epoch=7 applied=0 inSync=false");
    }

    @Test
    void aSessionWhoseStorageIsTheLeadersOwnIsMovedAheadEvenAfterACleanShutdown() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN, "follower-storage"));

        read(close(CloseReason.SHUTDOWN));
        replicaSessions.jumpOutgoingSeqNums(1_000);

        assertThat(engine.store(MAIN).writes).containsExactly("outgoing 1001");
    }

    @Test
    void aSessionWhoseStorageCannotBeIdentifiedIsRefused() {
        engine.nextStorageIdentity = null;

        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN));

        assertThat(engine.store(MAIN).started).isFalse();
        assertThat(sent.frames).first().isEqualTo("SESSION_ANSWER epoch=7 key=0 UNKNOWN_STORAGE");
    }

    @Test
    void aSessionTheLeaderCannotIdentifyIsRefused() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), (out, index) -> FrameEncoder.session(out, index, EPOCH, 0,
                MAIN.getFixSessionType(), ReplicationMode.ASYNC, MAIN.getFixSessionId().toString(), null));

        assertThat(sent.frames).first().isEqualTo("SESSION_ANSWER epoch=7 key=0 UNKNOWN_STORAGE");
    }

    @Test
    void aRefusedSessionIsLookedAtAgainOnTheNextConnection() {
        engine.nextStorageIdentity = null;
        read(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN));
        engine.nextStorageIdentity = "follower-storage-back";

        LeaderConnection next = newConnection(0);
        sent.frames.clear();
        decode(writeFrames(welcome(EPOCH, WireFormat.NO_RESUME), session(0, MAIN)), next);

        assertThat(sent.frames).containsExactly("SESSION_ANSWER epoch=7 key=0 SNAPSHOT_NEEDED");
        assertThat(engine.store(MAIN).started).isTrue();
    }
}
