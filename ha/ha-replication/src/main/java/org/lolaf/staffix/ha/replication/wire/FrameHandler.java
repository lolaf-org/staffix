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
package org.lolaf.staffix.ha.replication.wire;

import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.replication.spi.RecordFlag;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;
import org.lolaf.staffix.ha.replication.spi.StreamReplicationMode;

import java.nio.ByteBuffer;

/**
 * Receives decoded frames as primitives, so the frequent ones allocate nothing. A payload or a state is handed over
 * as a range of the receive buffer, valid only for the duration of the call.
 *
 * <p>Every method gets the sender's epoch: fencing a deposed leader applies to every frame alike. A frame the handler
 * does not take, one of the other direction's, is refused: by default every method throws {@link WireFormatException}.
 */
public interface FrameHandler {

    /**
     * A follower's first frame.
     *
     * @param lastAppliedOffset the offset of the last record it applied in {@code lastAppliedEpoch}, -1 for none: the
     *                          leader resumes after it when its log still holds it in the same epoch
     * @param lane              which of the leader's lanes this connection carries, 0 for the first, the one that
     *                          learns how many there are
     * @param applicationId     what the follower replicates, which must be the leader's: two applications' streams
     *                          do not mix
     * @param encryptionMode    the follower's, which with the leader's decides whether the connection encrypts
     * @param nonce             the follower's random part of this connection's keys, zeros when it does not encrypt
     */
    default void onHello(long epoch, int version, int applicationId, String nodeId, long lastAppliedEpoch,
                         long lastAppliedOffset, int lane, EncryptionMode encryptionMode, byte[] nonce) {
        throw unexpected(FrameType.HELLO);
    }

    /**
     * @param resumeOffset where the stream resumes, or {@link WireFormat#NO_RESUME} when every stream is caught up
     *                     by snapshot
     * @param lanes        how many lanes the leader splits its streams over; the follower opens the others
     * @param applicationId  the leader's, as in {@link #onHello}
     * @param encryptionMode the leader's, as in {@link #onHello}
     * @param nonce          the leader's, as in {@link #onHello}
     */
    default void onWelcome(long epoch, int version, int applicationId, String leaderId, long resumeOffset, int lanes,
                           EncryptionMode encryptionMode, byte[] nonce) {
        throw unexpected(FrameType.WELCOME);
    }

    /**
     * The follower holds the same secret and cipher: {@code confirmation} is the HMAC of the HELLO and WELCOME under a
     * key derived from both. The leader streams only once it verifies.
     */
    default void onKeyConfirmation(long epoch, byte[] confirmation) {
        throw unexpected(FrameType.KEY_CONFIRMATION);
    }

    /**
     * Binds a stream key, used by every later frame about the stream, to the stream's name.
     *
     * @param streamName the application's name for the stream, which the follower finds its own copy by
     */
    default void onStream(long epoch, int streamKey, StreamReplicationMode replicationMode, String streamName,
                          @Nullable String storageIdentity) {
        throw unexpected(FrameType.STREAM);
    }

    /**
     * Every stream the leader replicated when the connection started has been announced: until then, the follower
     * cannot tell a stream it lacks from one not announced yet.
     */
    default void onStreamsAnnounced(long epoch, int announcedCount) {
        throw unexpected(FrameType.STREAMS_ANNOUNCED);
    }

    /**
     * The follower's answer to a {@code STREAM}, and again whenever the stream's state on the follower changes, as
     * when a snapshot completes it.
     */
    default void onStreamAnswer(long epoch, int streamKey, StreamAnswer answer) {
        throw unexpected(FrameType.STREAM_ANSWER);
    }

    /**
     * @param logOffset   the record's position in the leader's replication log
     * @param contentType the application's, telling what the payload holds
     * @param flags       {@link WireFormat#MORE_FRAGMENTS}, {@link WireFormat#CONTINUED_FRAGMENT} and
     *                    {@link RecordFlag#RESET}; zero for a whole record
     * @param position    the record's place in its stream, increasing between two resets
     */
    default void onRecord(long epoch, long logOffset, int streamKey, byte contentType, byte flags, long position,
                          ByteBuffer buffer, int payloadIndex, int payloadLength) {
        throw unexpected(FrameType.RECORD);
    }

    /**
     * @param snapshotOffset the log offset the snapshot starts at; the stream's records after it come through the
     *                       snapshot until its end, and through the log again after that
     * @param stateIndex     where the stream's state starts in {@code buffer}, the application's to resume from
     */
    default void onSnapshotBegin(long epoch, int streamKey, long snapshotOffset, ByteBuffer buffer, int stateIndex,
                                 int stateLength) {
        throw unexpected(FrameType.SNAPSHOT_BEGIN);
    }

    /**
     * As {@link #onRecord}: a snapshot carries a payload longer than a frame as consecutive fragments too.
     */
    default void onSnapshotRecord(long epoch, int streamKey, byte contentType, byte flags, long position,
                                  ByteBuffer buffer, int payloadIndex, int payloadLength) {
        throw unexpected(FrameType.SNAPSHOT_RECORD);
    }

    /**
     * @param recordCount the records the snapshot carried, a fragmented one counted once: the follower checks it
     *                    received them all
     * @param stateIndex  as for {@link #onSnapshotBegin}: the stream's state when the snapshot caught up with it,
     *                    which supersedes the one it began with
     */
    default void onSnapshotEnd(long epoch, int streamKey, int recordCount, ByteBuffer buffer, int stateIndex,
                               int stateLength) {
        throw unexpected(FrameType.SNAPSHOT_END);
    }

    /**
     * @param appliedOffset the offset of the last record the follower applied, which releases the {@code SYNC} sends
     *                      waiting for it
     * @param inSync        whether the follower holds every stream the leader replicates
     * @param pendingWrites what the follower's sinks for this lane have yet to write, as of the last second or so
     */
    default void onAck(long epoch, long appliedOffset, boolean inSync, long pendingWrites) {
        throw unexpected(FrameType.ACK);
    }

    /**
     * Sent before a read's records are written to the follower's sinks, then followed by its {@link #onAck ACK}.
     *
     * @param receivedOffset the offset of the last record the follower holds in memory, which releases the
     *                       {@code SYNC_RECEIVED} sends waiting for it
     */
    default void onReceived(long epoch, long receivedOffset) {
        throw unexpected(FrameType.RECEIVED);
    }

    /**
     * Sent when the leader sent nothing for a heartbeat interval. The follower answers with an ACK, so a quiet stream
     * still carries traffic both ways.
     *
     * @param logTailOffset where the leader's log ends
     */
    default void onHeartbeat(long epoch, long logTailOffset) {
        throw unexpected(FrameType.HEARTBEAT);
    }

    /**
     * The sender's last frame before it disconnects. A {@link CloseReason#SHUTDOWN} from the leader tells the follower
     * it was sent every record, so it takes over knowing it lost no write.
     */
    default void onClose(long epoch, CloseReason reason, String text) {
        throw unexpected(FrameType.CLOSE);
    }

    private static WireFormatException unexpected(FrameType type) {
        return new WireFormatException("Unexpected " + type + " frame");
    }
}
