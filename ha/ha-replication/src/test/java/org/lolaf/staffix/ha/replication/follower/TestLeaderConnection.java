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
package org.lolaf.staffix.ha.replication.follower;

import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import org.lolaf.staffix.ha.replication.encryption.TestingKeyExchanges;
import org.lolaf.staffix.ha.replication.journal.JournalCopy;
import org.lolaf.staffix.ha.replication.wire.CloseReason;
import org.lolaf.staffix.ha.replication.wire.FrameDecoder;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;
import org.lolaf.staffix.ha.replication.wire.WireFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

class TestLeaderConnection extends LeaderConnectionHarness {

    @Test
    void theWelcomeGivesTheReplicaTheLeadersLaneCount() {
        read(welcome(EPOCH, WireFormat.NO_RESUME, 3));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.STREAMING);
        assertThat(followerStreams.getLanes()).isEqualTo(3);
    }

    @Test
    void aWelcomeOnALaneTheLeaderDoesNotHaveClosesTheStream() {
        LeaderConnection second = newConnection(1);

        decode(writeFrames(welcome(EPOCH, WireFormat.NO_RESUME, 1)), second);

        assertThat(second.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void aLeaderOfAnotherApplicationClosesTheStream() {
        decode(writeFrames((out, index) -> FrameEncoder.welcome(out, index, EPOCH, 0x41505031, "leader-a",
                WireFormat.NO_RESUME, 1, EncryptionMode.DISABLED, TestingKeyExchanges.NO_NONCE)), connection);

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).last().asString()
                .isEqualTo("CLOSE epoch=7 APPLICATION_MISMATCH follower replicates application 4a524e4c, leader 41505031");
    }

    @Test
    void aStreamOfAnotherLaneClosesTheConnection() {
        read(welcome(EPOCH, WireFormat.NO_RESUME, 2), stream(1, MAIN));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR stream key 1 on lane 0 of 2");
    }

    @Test
    void eachLaneResumesFromTheLastRecordItApplied() {
        LeaderConnection second = newConnection(1);
        read(welcome(EPOCH, WireFormat.NO_RESUME, 2));
        second.read(writeFrames(welcome(EPOCH, WireFormat.NO_RESUME, 2), stream(1, MAIN), record(EPOCH, 96, 1, 2, "")),
                new FrameDecoder(1 << 20));
        sent.frames.clear();

        connection.hello();
        second.hello();

        assertThat(sent.frames).containsExactly(
                "HELLO epoch=7 version=1 node=follower-b lastEpoch=0 lastOffset=-1 lane=0",
                "HELLO epoch=7 version=1 node=follower-b lastEpoch=7 lastOffset=96 lane=1");
    }

    @Test
    void theHelloCarriesTheLastRecordApplied() {
        connection.hello();

        assertThat(sent.frames).containsExactly("HELLO epoch=7 version=1 node=follower-b lastEpoch=0 lastOffset=-1 lane=0");
    }

    @Test
    void aWelcomeStartsTheStreamAndIsAcknowledged() {
        read(welcome(EPOCH, WireFormat.NO_RESUME));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.STREAMING);
        assertThat(connection.getLeaderId()).isEqualTo("leader-a");
        assertThat(sent.frames).as("the leader's streams not announced yet").containsExactly("ACK epoch=7 applied=-1 inSync=false");
    }

    @Test
    void aFollowerThatNeverStreamedHasNothingMissing() {
        assertThat(followerStreams.isInSync()).isTrue();
    }

    @Test
    void aLaneIsInSyncOnceTheLeaderAnnouncedEveryStreamItReplicates() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), announced(0));

        assertThat(followerStreams.isInSync()).isTrue();
        assertThat(sent.frames).containsExactly("ACK epoch=7 applied=-1 inSync=true");
    }

    @Test
    void aLaneTheWelcomeNamesIsNotInSyncUntilItsOwnStreamsAreAnnounced() {
        read(welcome(EPOCH, WireFormat.NO_RESUME, 2), announced(0));

        assertThat(followerStreams.isInSync()).as("lane 1 never connected").isFalse();
    }

    @Test
    void aResumedConnectionIsNotInSyncUntilItsStreamsAreAnnouncedAgain() {
        streaming();
        read(snapshotBegin(512, "13"), snapshotEnd(0, "14"));
        assertThat(followerStreams.isInSync()).isTrue();
        LeaderConnection next = newConnection(0);

        decode(writeFrames(welcome(EPOCH, 48), stream(0, MAIN)), next);
        assertThat(followerStreams.isInSync()).as("a stream added on the leader meanwhile is not known yet").isFalse();
        decode(writeFrames(announced(1)), next);

        assertThat(followerStreams.isInSync()).isTrue();
    }

    @Test
    void anAnnouncementCountingOtherStreamsThanAnnouncedIsAProtocolError() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), stream(0, MAIN), announced(2));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void aSecondAnnouncementIsAProtocolError() {
        streaming();

        read(announced(1));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).last().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void anAckCarriesWhatTheSinksHaveYetToWrite() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), stream(0, MAIN));
        main.setPendingWrites(7);
        sent.frames.clear();

        read(heartbeat());

        assertThat(sent.frames).containsExactly("ACK epoch=7 applied=-1 inSync=false pending=7");
    }

    @Test
    void aNewConnectionAsksTheSinksForItsFirstAck() {
        read(welcome(EPOCH, WireFormat.NO_RESUME), stream(0, MAIN));
        main.setPendingWrites(7);
        sent.frames.clear();
        LeaderConnection checkingHourly = checkingHourly();

        checkingHourly.read(writeFrames(welcome(EPOCH, WireFormat.NO_RESUME), stream(0, MAIN), heartbeat()), new FrameDecoder(1 << 20));

        assertThat(sent.frames).filteredOn(frame -> frame.startsWith("ACK")).first().asString().endsWith("pending=7");
    }

    @Test
    void theSinksAreAskedAtMostOnceACheckInterval() {
        LeaderConnection checkingHourly = checkingHourly();
        checkingHourly.read(writeFrames(welcome(EPOCH, WireFormat.NO_RESUME), stream(0, MAIN)), new FrameDecoder(1 << 20));
        main.setPendingWrites(7);
        sent.frames.clear();

        checkingHourly.read(writeFrames(heartbeat()), new FrameDecoder(1 << 20));

        assertThat(sent.frames).containsExactly("ACK epoch=7 applied=-1 inSync=false");
    }

    @Test
    void theSinksAreAskedAtOnceOnTurningInSync() {
        LeaderConnection checkingHourly = checkingHourly();
        checkingHourly.read(writeFrames(welcome(EPOCH, WireFormat.NO_RESUME), stream(0, MAIN), announced(1)), new FrameDecoder(1 << 20));
        main.setPendingWrites(7);
        sent.frames.clear();

        checkingHourly.read(writeFrames(snapshotBegin(512, "13"), snapshotEnd(0, "14")), new FrameDecoder(1 << 20));

        assertThat(sent.frames).last().isEqualTo("ACK epoch=7 applied=-1 inSync=true pending=7");
    }

    @Test
    void aLeaderOfAnOlderEpochIsRefused() {
        read(welcome(EPOCH - 1, 0));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 STALE_EPOCH");
        verify(ioSession).stop(any());
    }

    @Test
    void aReconnectionKeepsTheSinkAndResumesAfterTheLastRecord() {
        streaming();
        read(record(EPOCH, 0, 0, 3, "three"));
        BoundStream stream = followerStreams.find(MAIN);

        LeaderConnection next = newConnection(0);
        sent.frames.clear();
        next.hello();
        decode(writeFrames(welcome(EPOCH, 48), stream(0, MAIN)), next);

        assertThat(sent.frames).startsWith("HELLO epoch=7 version=1 node=follower-b lastEpoch=7 lastOffset=0 lane=0");
        assertThat(followerStreams.find(MAIN)).isSameAs(stream);
        assertThat(stream.getSink()).isSameAs(main);
        assertThat(main.getWrites()).containsExactly("3 three");
    }

    @Test
    void aSnapshotHandsTheSinkItsStateAndRecordsThenPutsTheStreamInSync() {
        streaming();

        read(snapshotBegin(512, "13"), snapshotRecord(11, "eleven"), snapshotRecord(12, "twelve"), snapshotEnd(2, "14"));

        assertThat(main.getWrites()).containsExactly("begin state=13", "snapshot 11 eleven", "snapshot 12 twelve",
                "end state=14");
        assertThat(followerStreams.isInSync()).isTrue();
        assertThat(sent.frames).containsExactly("STREAM_ANSWER epoch=7 key=0 REPLICATING", "ACK epoch=7 applied=-1 inSync=true");
    }

    @Test
    void aSnapshotEndWritesWhatTheSinkHeldPendingFirst() {
        main.setBatching(true);
        streaming();

        read(snapshotBegin(512, "1"), record(EPOCH, 600, 0, 1, "live"), snapshotEnd(0, "2"));

        assertThat(main.getWrites()).containsExactly("begin state=1", "1 live", "end state=2");
    }

    @Test
    void aNewSnapshotDropsWhatTheSinkHeldPending() {
        main.setBatching(true);
        streaming();

        read(snapshotBegin(512, "1"), record(EPOCH, 600, 0, 1, "live"), snapshotBegin(700, "1"), snapshotEnd(0, "2"));

        assertThat(main.getWrites()).containsExactly("begin state=1", "begin state=1", "end state=2");
    }

    @Test
    void aSnapshotEndingWithAnotherCountThanReceivedIsAProtocolError() {
        streaming();

        read(snapshotBegin(512, "13"), snapshotRecord(11, "eleven"), snapshotEnd(2, "13"));

        assertThat(followerStreams.isInSync()).isFalse();
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void theFragmentsOfARecordAreAppliedAsOneOnceTheLastArrives() {
        JournalCopy other = replica.configure("other", StreamReplicationMode.ASYNC);
        streaming();
        read(stream(3, "other"));

        read(fragment(0, 0, 5, "frag", FIRST), record(EPOCH, 64, 3, 2, "other's"));
        assertThat(main.getWrites()).as("nothing until the last fragment").isEmpty();
        read(fragment(96, 0, 5, "men", MIDDLE), fragment(128, 0, 5, "ted", LAST));

        assertThat(main.getWrites()).containsExactly("5 fragmented");
        assertThat(other.getWrites()).containsExactly("2 other's");
        assertThat(followerStreams.getLastAppliedOffset(0)).isEqualTo(128);
    }

    @Test
    void aSnapshotRecordInFragmentsIsHandedOnceAndCountedOnce() {
        streaming();

        read(snapshotBegin(0, "6"), snapshotFragment(5, "frag", FIRST), snapshotFragment(5, "men", MIDDLE),
                snapshotFragment(5, "ted", LAST), snapshotEnd(1, "6"));

        assertThat(main.getWrites()).contains("snapshot 5 fragmented");
        assertThat(sent.frames).contains("STREAM_ANSWER epoch=7 key=0 REPLICATING");
    }

    @Test
    void aFragmentOfAnotherRecordClosesTheConnection() {
        streaming();

        read(fragment(0, 0, 5, "frag", FIRST), fragment(64, 0, 6, "ted", LAST));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).anyMatch(frame -> frame.startsWith("CLOSE epoch=7 PROTOCOL_ERROR"));
        assertThat(main.getWrites()).isEmpty();
    }

    /**
     * Resuming starts after the last record applied, which may be past the record's first fragments: only a snapshot
     * gives the stream that record.
     */
    @Test
    void aConnectionResumedInTheMiddleOfARecordCatchesItsStreamUpBySnapshot() {
        streaming();
        complete();
        read(fragment(0, 0, 5, "frag", FIRST));

        LeaderConnection resumed = newConnection(0);
        sent.frames.clear();
        decode(writeFrames(welcome(EPOCH, 0), stream(0, MAIN)), resumed);

        assertThat(sent.frames).containsExactly("STREAM_ANSWER epoch=7 key=0 SNAPSHOT_NEEDED");
        decode(writeFrames(fragment(64, 0, 5, "ted", LAST)), resumed);
        assertThat(main.getWrites()).as("the rest of a record alone is not applied").isEmpty();
    }

    @Test
    void aResumedConnectionKeepsTheStreamsInSyncAndOneThatDidNotResumeLosesThem() {
        streaming();
        read(snapshotBegin(0, "1"), snapshotEnd(0, "1"));
        read(record(EPOCH, 0, 0, 3, ""));

        LeaderConnection resumed = newConnection(0);
        sent.frames.clear();
        decode(writeFrames(welcome(EPOCH, 48), stream(0, MAIN)), resumed);
        assertThat(sent.frames).containsExactly("STREAM_ANSWER epoch=7 key=0 REPLICATING");

        LeaderConnection notResumed = newConnection(0);
        sent.frames.clear();
        decode(writeFrames(welcome(EPOCH, WireFormat.NO_RESUME), stream(0, MAIN)), notResumed);
        assertThat(sent.frames).containsExactly("STREAM_ANSWER epoch=7 key=0 SNAPSHOT_NEEDED");
        assertThat(followerStreams.getLastAppliedOffset(0)).as("nothing left to resume from").isEqualTo(-1);
    }

    @Test
    void aCleanShutdownHandsOverEveryStreamInSync() {
        streaming();
        complete();

        read(close(CloseReason.SHUTDOWN));

        assertThat(followerStreams.find(MAIN).isHandedOver()).isTrue();
    }

    @Test
    void aStreamOutOfSyncAtTheShutdownIsNotHandedOver() {
        streaming();

        read(close(CloseReason.SHUTDOWN));

        assertThat(followerStreams.find(MAIN).isHandedOver()).isFalse();
    }

    @Test
    void aCloseForAnyOtherReasonIsNoHandOver() {
        streaming();
        complete();

        read(close(CloseReason.TOO_SLOW));

        assertThat(followerStreams.find(MAIN).isHandedOver()).isFalse();
    }

    @Test
    void aHandOverLastsUntilTheNextConnectionStreams() {
        streaming();
        complete();
        read(close(CloseReason.SHUTDOWN));

        decode(writeFrames(welcome(EPOCH, 48)), newConnection(0));

        assertThat(followerStreams.find(MAIN).isHandedOver()).isFalse();
    }

    @Test
    void aFrameOfAnotherEpochClosesTheConnection() {
        streaming();

        read(record(EPOCH + 1, 0, 0, 3, "three"));

        assertThat(main.getWrites()).isEmpty();
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 STALE_EPOCH");
    }

    @Test
    void aRecordOfAStreamNeverAnnouncedIsAProtocolError() {
        streaming();

        read(record(EPOCH, 0, 1, 3, "three"));

        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void aSyncReceivedStreamsRecordIsAcknowledgedAsReceivedBeforeItIsWritten() {
        streamingSyncReceived();

        read(record(EPOCH, 0, 0, 2, ""));

        assertThat(sent.frames).containsExactly("RECEIVED epoch=7 received=0", "ACK epoch=7 applied=0 inSync=false");
    }

    @Test
    void anotherModesRecordIsAcknowledgedAsAppliedOnly() {
        streaming();

        read(record(EPOCH, 0, 0, 2, ""));

        assertThat(sent.frames).singleElement().asString().startsWith("ACK epoch=7 applied=0");
    }

    @Test
    void aReadWithoutNewRecordsIsAcknowledgedAsAppliedOnly() {
        streamingSyncReceived();
        read(record(EPOCH, 0, 0, 2, ""));
        sent.frames.clear();

        read((out, index) -> FrameEncoder.heartbeat(out, index, EPOCH, 0));

        assertThat(sent.frames).singleElement().asString().startsWith("ACK epoch=7 applied=0");
    }

    @Test
    void aFrameBeforeTheWelcomeIsAProtocolError() {
        read((out, index) -> FrameEncoder.heartbeat(out, index, EPOCH, 0));

        assertThat(connection.getState()).isEqualTo(LeaderConnection.State.CLOSED);
        assertThat(sent.frames).singleElement().asString().startsWith("CLOSE epoch=7 PROTOCOL_ERROR");
    }

    @Test
    void closingTheReplicaStopsItsSinks() {
        streaming();

        followerStreams.close(Deadline.unlimited());

        assertThat(main.isStopped()).isTrue();
    }
}
