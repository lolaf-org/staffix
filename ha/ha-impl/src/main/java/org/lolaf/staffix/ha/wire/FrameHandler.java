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
package org.lolaf.staffix.ha.wire;

import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;

import java.nio.ByteBuffer;

/**
 * Receives decoded frames as primitives, so the frequent ones allocate nothing. A message is handed over as a range
 * of the receive buffer, valid only for the duration of the call.
 *
 * <p>Every method gets the sender's epoch: fencing a deposed leader applies to every frame alike.
 */
public interface FrameHandler {

    /**
     * A follower's first frame.
     *
     * @param lastAppliedOffset the offset of the last event it applied in {@code lastAppliedEpoch}, -1 for none: the
     *                          leader resumes after it when its log still holds it in the same epoch
     */
    void onHello(long epoch, int version, String nodeId, long lastAppliedEpoch, long lastAppliedOffset);

    /**
     * @param resumeOffset where the stream resumes, or {@link WireFormat#NO_RESUME} when every session is caught up
     *                     by snapshot
     */
    void onWelcome(long epoch, int version, String leaderId, long resumeOffset);

    /**
     * Binds a session key, used by every later frame about the session, to the session's id.
     *
     * @param fixSessionId the id's {@link org.lolaf.staffix.api.session.FixSessionId#toString() full form}
     */
    void onSession(long epoch, int sessionKey, FixSessionType fixSessionType, ReplicationMode replicationMode,
                   String fixSessionId, @Nullable String storageIdentity);

    /**
     * The follower's answer to a {@code SESSION}, and again whenever the session's state on the follower changes, as
     * when a snapshot completes it.
     */
    void onSessionStatus(long epoch, int sessionKey, SessionStatus status);

    /**
     * @param logOffset     the event's position in the leader's replication log
     * @param fragmentFlags {@link WireFormat#MORE_FRAGMENTS} and {@link WireFormat#CONTINUED_FRAGMENT}, zero for a
     *                      whole event
     */
    void onStoreEvent(long epoch, long logOffset, int sessionKey, StoreEventType eventType, long sequenceNumber,
                      ByteBuffer buffer, int messageIndex, int messageLength, byte fragmentFlags);

    /**
     * @param cutOffset the log offset the snapshot starts at; the session's events after it come through the snapshot
     *                  until its end, and through the stream again after that
     */
    void onSnapshotBegin(long epoch, int sessionKey, long cutOffset, long nextIncomingSeqNum, long nextOutgoingSeqNum);

    /**
     * @param fragmentFlags as for {@link #onStoreEvent}, from the frame's header: a snapshot carries a message longer
     *                      than a frame as consecutive fragments
     */
    void onSnapshotMessage(long epoch, int sessionKey, long sequenceNumber, ByteBuffer buffer, int messageIndex,
                           int messageLength, byte fragmentFlags);

    /**
     * @param messageCount       the messages the snapshot carried, a fragmented one counted once: the follower checks
     *                           it received them all
     * @param nextIncomingSeqNum the session's seqnums when the snapshot caught up with its store, which supersede the
     *                           ones of {@link #onSnapshotBegin}
     */
    void onSnapshotEnd(long epoch, int sessionKey, int messageCount, long nextIncomingSeqNum, long nextOutgoingSeqNum);

    /**
     * @param appliedOffset the offset of the last event the follower applied, which releases the {@code SYNC} sends
     *                      waiting for it
     * @param inSync        whether the follower holds every session the leader replicates
     */
    void onAck(long epoch, long appliedOffset, boolean inSync);

    /**
     * Sent when the leader sent nothing for a heartbeat interval. The follower answers with an ACK, so a quiet stream
     * still carries traffic both ways.
     *
     * @param logTailOffset where the leader's log ends
     */
    void onHeartbeat(long epoch, long logTailOffset);

    /**
     * The sender's last frame before it disconnects. A {@link CloseReason#SHUTDOWN} from the leader tells the follower
     * it was sent every event, so it takes over without moving its seqnums ahead.
     */
    void onClose(long epoch, CloseReason reason, String text);
}
