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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.HaSessionStatus;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings.ReplicationMode;
import org.lolaf.staffix.api.stores.FixMessagesStore.BatchingFixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.wire.SessionAnswer;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;

/**
 * One of the leader's sessions as this follower holds it: its store, or {@link NoStore} when it is not replicated here,
 * and how far a snapshot and the stream brought it.
 */
final class ReplicaSession {

    @Getter
    private final FixSessionType fixSessionType;
    @Getter
    private final String fixSessionId;
    @Getter
    private final boolean replicated;
    /**
     * {@link NoStore} when not {@link #replicated}.
     */
    @Getter
    private final FixSessionMessagesStore store;
    private final StoreWrites storeWrites;
    /**
     * Why the session is not replicated here, unused when it is.
     */
    @Getter
    private final SessionAnswer notReplicatedStatus;
    /**
     * Kept by a session refused until its settings here change, to bind it again once they do.
     */
    @Getter
    private @Nullable String leaderStorageIdentity;
    /**
     * The mode the leader gives the session: whether its events are acknowledged on receipt, and kept by a refused
     * session to bind it again.
     */
    @Getter
    @Setter(AccessLevel.PACKAGE)
    private volatile @Nullable ReplicationMode leaderMode;
    private final LostWrites lostWrites;
    private volatile boolean complete;
    @Getter
    private volatile boolean handedOver;
    private int snapshotMessageCount;
    /**
     * Since the store was last reset.
     */
    private long highestStoredSeqNum;
    private boolean assembling;
    private long assembledSeqNum;
    /**
     * The fragments received so far of a message longer than a frame, grown as needed and kept for the next one.
     */
    private @Nullable ByteBuffer assembledMessage;

    private ReplicaSession(FixSessionType fixSessionType, String fixSessionId, boolean replicated,
                           FixSessionMessagesStore store, SessionAnswer notReplicatedStatus) {
        this.fixSessionType = fixSessionType;
        this.fixSessionId = fixSessionId;
        this.replicated = replicated;
        this.store = store;
        this.notReplicatedStatus = notReplicatedStatus;
        this.lostWrites = new LostWrites(store, fixSessionId);
        this.storeWrites = store instanceof BatchingFixSessionMessagesStore
                ? new BatchedStoreWrites((BatchingFixSessionMessagesStore) store, lostWrites)
                : new OneByOneStoreWrites(store, lostWrites);
    }

    static ReplicaSession replicated(FixSessionType fixSessionType, String fixSessionId, FixSessionMessagesStore store) {
        return new ReplicaSession(fixSessionType, fixSessionId, true, store, SessionAnswer.SNAPSHOT_NEEDED);
    }

    static ReplicaSession notReplicated(FixSessionType fixSessionType, String fixSessionId, SessionAnswer status) {
        return new ReplicaSession(fixSessionType, fixSessionId, false, NoStore.INSTANCE, status);
    }

    static ReplicaSession refused(FixSessionType fixSessionType, String fixSessionId, SessionAnswer refusal,
                                  ReplicationMode leaderMode, @Nullable String leaderStorageIdentity) {
        ReplicaSession session = notReplicated(fixSessionType, fixSessionId, refusal);
        session.leaderMode = leaderMode;
        session.leaderStorageIdentity = leaderStorageIdentity;
        return session;
    }

    SessionAnswer status() {
        if (!replicated) {
            return notReplicatedStatus;
        }
        return complete ? SessionAnswer.REPLICATING : SessionAnswer.SNAPSHOT_NEEDED;
    }
    
    boolean isNotCaughtUp() {
        return replicated ? !complete : notReplicatedStatus.isRefusal();
    }

    void handOver() {
        handedOver = replicated && complete;
    }

    void describe(HaSessionStatus.HaSessionStatusBuilder status) {
        status.handedOver(handedOver);
        if (replicated) {
            status.inSync(complete)
                    .nextIncomingSeqNum(store.getIncomingSeqNum())
                    .nextOutgoingSeqNum(store.getOutgoingSeqNum())
                    .pendingWrites(getPendingWrites())
                    .lostWrites(lostWrites.getCount() + store.getLostWrites());
        } else {
            status.notReplicatedReason(notReplicatedStatus.name());
        }
    }

    long getPendingWrites() {
        return store.getPendingWrites();
    }

    boolean isRefusedUntilSettingsChange() {
        return !replicated && (notReplicatedStatus == SessionAnswer.UNKNOWN_SESSION
                || notReplicatedStatus == SessionAnswer.MODE_MISMATCH);
    }

    boolean matches(FixSessionType fixSessionType, String fixSessionId) {
        return this.fixSessionType == fixSessionType && this.fixSessionId.equals(fixSessionId);
    }

    void streamStarted(boolean resumed) {
        handedOver = false;
        // a resume may start past the first fragments of a message this session was assembling
        complete &= resumed && !assembling;
        discardAssembledMessage();
        discardPendingWrites();
    }

    /**
     * @return false for a fragment out of place
     */
    boolean apply(StoreEventType eventType, long seqNum, ByteBuffer receiveBuffer, int messageStart, int messageLength,
                  byte fragmentFlags) {
        if (fragmentFlags != 0 || assembling) {
            return assemble(seqNum, receiveBuffer, messageStart, messageLength, fragmentFlags, false);
        }
        applyEvent(eventType, seqNum, receiveBuffer, messageStart, messageLength);
        return true;
    }

    /**
     * The message is lent as a range of the receive buffer, which is put back as it was.
     */
    private void applyEvent(StoreEventType eventType, long seqNum, ByteBuffer receiveBuffer, int messageStart,
                            int messageLength) {
        switch (eventType) {
            case STORE_MESSAGE:
                if (seqNum <= highestStoredSeqNum) {
                    break;
                }
                storeWrites.storeLiveMessage(seqNum, receiveBuffer, messageStart, messageLength);
                highestStoredSeqNum = seqNum;
                break;
            case STORE_OUTGOING_SEQ_NUM:
                storeWrites.setNextOutgoingSeqNum(seqNum);
                break;
            case STORE_INCOMING_SEQ_NUM:
                storeWrites.setNextIncomingSeqNum(seqNum);
                break;
            case RESET_SEQ_NUM:
                writePending();
                store.resetSequenceNumbers();
                lostWrites.reset();
                highestStoredSeqNum = 0;
                break;
            default:
                throw new IllegalStateException("Unexpected store event " + eventType);
        }
    }

    void snapshotBegin(long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        complete = false;
        discardAssembledMessage();
        discardPendingWrites();
        snapshotMessageCount = 0;
        store.resetSequenceNumbers();
        lostWrites.reset();
        highestStoredSeqNum = 0;
        store.storeNextIncomingSeqNum(nextIncomingSeqNum);
        store.storeNextOutgoingSeqNum(nextOutgoingSeqNum);
    }

    /**
     * @return false for a fragment out of place
     */
    boolean snapshotMessage(long seqNum, ByteBuffer receiveBuffer, int messageStart, int messageLength,
                            byte fragmentFlags) {
        if (fragmentFlags != 0 || assembling) {
            if (!assemble(seqNum, receiveBuffer, messageStart, messageLength, fragmentFlags, true)) {
                return false;
            }
        } else {
            storeSnapshotMessage(seqNum, receiveBuffer, messageStart, messageLength);
        }
        if ((fragmentFlags & WireFormat.MORE_FRAGMENTS) == 0) {
            snapshotMessageCount++;
        }
        return true;
    }

    /**
     * Completes the session with the leader's seqnums at the snapshot's end: its events in between went through
     * the snapshot, and storing a message moved the outgoing seqnum.
     *
     * @return false when the count is not what was received
     */
    boolean snapshotEnd(int messageCount, long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        if (snapshotMessageCount != messageCount) {
            return false;
        }
        writePending();
        store.storeNextIncomingSeqNum(nextIncomingSeqNum);
        store.storeNextOutgoingSeqNum(nextOutgoingSeqNum);
        complete = true;
        return true;
    }

    private void storeSnapshotMessage(long seqNum, ByteBuffer receiveBuffer, int messageStart, int messageLength) {
        storeWrites.storeSnapshotMessage(seqNum, receiveBuffer, messageStart, messageLength);
        highestStoredSeqNum = Math.max(highestStoredSeqNum, seqNum);
    }

    void writePending() {
        storeWrites.writePending();
    }

    void discardPendingWrites() {
        storeWrites.discardPending();
    }

    /**
     * Adds a fragment of a message longer than a frame, applying the message with its last fragment. A session not
     * replicated here keeps no bytes, only where the message ends. A continued fragment with no message begun is
     * what a resume leaves of a message whose start it skipped: dropped, its session catching up by snapshot.
     *
     * @return false for a fragment out of place: another message's, or a new message before the last one ended
     */
    boolean assemble(long seqNum, ByteBuffer receiveBuffer, int fragmentStart, int fragmentLength, byte fragmentFlags,
                     boolean snapshot) {
        boolean continued = (fragmentFlags & WireFormat.CONTINUED_FRAGMENT) != 0;
        boolean moreFragments = (fragmentFlags & WireFormat.MORE_FRAGMENTS) != 0;
        if (continued != assembling) {
            return continued;
        }
        if (!assembling) {
            assembling = true;
            assembledSeqNum = seqNum;
        } else if (seqNum != assembledSeqNum) {
            return false;
        }
        if (replicated) {
            ByteBuffer fragment = receiveBuffer.duplicate();
            fragment.limit(fragmentStart + fragmentLength).position(fragmentStart);
            assembledMessage = assembledMessageWithRoomFor(fragmentLength).put(fragment);
        }
        if (!moreFragments) {
            if (snapshot) {
                storeSnapshotMessage(seqNum, assembledMessage, 0, assembledMessage.position());
            } else if (replicated) {
                applyEvent(StoreEventType.STORE_MESSAGE, seqNum, assembledMessage, 0, assembledMessage.position());
            }
            discardAssembledMessage();
        }
        return true;
    }

    private ByteBuffer assembledMessageWithRoomFor(int length) {
        if (assembledMessage == null) {
            return ByteBuffer.allocate(length);
        }
        if (assembledMessage.remaining() >= length) {
            return assembledMessage;
        }
        ByteBuffer grown = ByteBuffer.allocate(Math.max(assembledMessage.position() + length,
                assembledMessage.capacity() * 2));
        return grown.put(assembledMessage.flip());
    }

    void discardAssembledMessage() {
        assembling = false;
        if (assembledMessage != null) {
            assembledMessage.clear();
        }
    }

    void stop(Deadline deadline) {
        discardPendingWrites();
        store.stop(deadline);
    }
}
