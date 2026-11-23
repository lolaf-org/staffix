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
package org.lolaf.staffix.ha.replication.leader;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.ha.replication.EncryptionMode;
import org.lolaf.staffix.ha.replication.FollowerLaneStatus;
import org.lolaf.staffix.ha.replication.encryption.ConnectionEncryptionKeys;
import org.lolaf.staffix.ha.replication.encryption.KeyAgreement;
import org.lolaf.staffix.ha.replication.encryption.KeyExchange;
import org.lolaf.staffix.ha.replication.encryption.Negotiation;
import org.lolaf.staffix.ha.replication.log.ReplicationLog;
import org.lolaf.staffix.ha.replication.log.ReplicationLogs;
import org.lolaf.staffix.ha.replication.spi.LeaderStreams;
import org.lolaf.staffix.ha.replication.spi.ReplicationClock;
import org.lolaf.staffix.ha.replication.spi.StreamAnswer;
import org.lolaf.staffix.ha.replication.wire.*;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One follower's connection for one lane: the handshake, the frames the follower sends back, and the close.
 *
 * <p>Frames from the follower arrive on the connection's IO thread; a {@link FollowerSender} streams the log to it
 * from a thread of its own. While streaming, its reader holds appends back rather than be lapped; a writer that
 * waited the log full timeout for it drops it, and it reconnects to be caught up.
 */
@Slf4j
public final class FollowerConnection implements FrameHandler {

    private static final Duration CLOSE_FLUSH = Duration.ofSeconds(1);
    private static final AtomicReferenceFieldUpdater<FollowerConnection, State> STATE =
            AtomicReferenceFieldUpdater.newUpdater(FollowerConnection.class, State.class, "state");
    private static final AtomicReferenceFieldUpdater<FollowerConnection, PendingClose> PENDING_CLOSE =
            AtomicReferenceFieldUpdater.newUpdater(FollowerConnection.class, PendingClose.class, "pendingClose");
    private final IOSession ioSession;
    private final String address;
    private final ReplicationLogs logs;
    /**
     * The log of the lane the HELLO named, null before it.
     */
    private volatile @Nullable ReplicationLog replicationLog;
    /**
     * Opened by the HELLO, holding the lane's appends back until the connection closes; null before it.
     */
    private volatile ReplicationLog.@Nullable Reader reader;
    private final LeaderStreams streams;
    /**
     * This node's current election epoch, stamped on every frame it sends.
     */
    private final LongSupplier nodeEpoch;
    private final String leaderId;
    @Getter
    private volatile int lane;
    private final ReplicationStats stats;
    private final StreamSyncs syncs;
    @Getter
    private final FollowerAcks acks;
    @Getter(AccessLevel.PACKAGE)
    private final FollowerSender sender;
    private final KeyExchange keyExchange;
    private final KeyAgreement keyAgreement;
    /**
     * Opens the follower's frames once its KEY_CONFIRMATION verified; IO thread only.
     */
    @Getter(AccessLevel.PACKAGE)
    private FrameCipher answerOpener;
    @Getter
    private volatile State state;
    private volatile boolean followerHungUp;
    @Getter
    private volatile @Nullable String followerId;
    /**
     * Recorded by the first close; the IO thread sends it once the sender stopped, sealed after the sender's last frame.
     */
    private volatile @Nullable PendingClose pendingClose;
    private final AtomicBoolean closeSent;

    /**
     * @param sendBuffers        the batch buffers the connection allocates at most, a power of two; the one written
     *                           last is reused only once the next is written
     * @param snapshotChunkBytes the most bytes of a snapshot sent between two live batches, at most a batch
     * @param senderLauncher     starts the loop streaming the log, on a thread of its own
     * @param clock              what the stall timeout and the heartbeat interval are measured on
     * @param stallTimeout       also how long a follower may take to send its KEY_CONFIRMATION; zero waits for ever
     */
    FollowerConnection(IOSession ioSession, ReplicationLogs logs, LeaderStreams streams, LongSupplier nodeEpoch,
                       String leaderId, Duration heartbeatInterval, Duration stallTimeout, int sendBuffers,
                       int snapshotChunkBytes, IdleStrategy senderIdle, ReplicationStats stats,
                       Consumer<Runnable> senderLauncher, ReplicationClock clock, KeyExchange keyExchange) {
        this.syncs = new StreamSyncs();
        this.answerOpener = FrameCipher.CLEAR;
        this.closeSent = new AtomicBoolean();
        this.state = State.AWAITING_HELLO;
        this.ioSession = ioSession;
        this.address = String.valueOf(ioSession.getSocketAddress());
        this.logs = logs;
        this.streams = streams;
        this.nodeEpoch = nodeEpoch;
        this.leaderId = leaderId;
        this.stats = stats;
        this.keyExchange = keyExchange;
        this.keyAgreement = KeyAgreement.ofLeader(keyExchange);
        this.acks = new FollowerAcks(stats, clock);
        this.sender = new FollowerSender(this, ioSession, streams, syncs, acks, nodeEpoch, logs.getMaxRecordFrameBytes(),
                heartbeatInterval, stallTimeout, sendBuffers, snapshotChunkBytes, senderIdle, stats, senderLauncher, clock);
    }

    @Override
    public void onHello(long epoch, int version, int applicationId, String nodeId, long lastAppliedEpoch,
                        long lastAppliedOffset, int lane, EncryptionMode followerMode, byte[] followerNonce) {
        followerId = nodeId;
        long currentEpoch = nodeEpoch.getAsLong();
        if (state != State.AWAITING_HELLO) {
            close(CloseReason.PROTOCOL_ERROR, "a second HELLO");
            return;
        }
        if (version != WireFormat.VERSION) {
            close(CloseReason.VERSION_MISMATCH, "leader speaks version " + WireFormat.VERSION + ", follower " + version);
            return;
        }
        if (applicationId != streams.getApplicationId()) {
            close(CloseReason.APPLICATION_MISMATCH, "leader replicates application " + Integer.toHexString(streams.getApplicationId())
                    + ", follower " + Integer.toHexString(applicationId));
            return;
        }
        if (epoch > currentEpoch) {
            close(CloseReason.STALE_EPOCH, "follower is at epoch " + epoch + ", this leader at " + currentEpoch);
            return;
        }
        if (lane >= logs.count()) {
            close(CloseReason.NO_SUCH_LANE, "lane " + lane + " of " + logs.count());
            return;
        }
        this.lane = lane;
        Negotiation negotiation = keyExchange.negotiate(followerMode);
        if (negotiation == Negotiation.REFUSED) {
            close(CloseReason.ENCRYPTION_REQUIRED, "this leader's encryption is " + keyExchange.getMode()
                    + ", the follower's " + followerMode);
            return;
        }
        replicationLog = logs.get(lane);
        Optional<Instant> heldOffUntil = stats.getSlowFollowers().heldOffUntil(nodeId, lane);
        if (heldOffUntil.isPresent()) {
            close(CloseReason.RETRY_LATER, "dropped as too slow, held off until " + heldOffUntil.get());
            return;
        }
        reader = replicationLog.newReader(this::droppedAsTooSlow);
        boolean resumed = lastAppliedEpoch == currentEpoch && resume(lastAppliedOffset);
        if (!resumed) {
            reader.seekToTail();
        }
        acks.hello(nodeId, lane, lastAppliedOffset);
        boolean encrypted = negotiation == Negotiation.ENCRYPTED;
        // a writer may have dropped the reader already
        if (!STATE.compareAndSet(this, State.AWAITING_HELLO, encrypted ? State.AWAITING_KEY_CONFIRMATION : State.STREAMING)) {
            if (state == State.DROPPED_AS_TOO_SLOW) {
                closeDropped();
            }
            return;
        }
        log.info("Follower {} connected lane {} of {} at epoch {}, {}", nodeId, lane, logs.count(), currentEpoch,
                resumed ? "resuming after offset " + lastAppliedOffset : "needing a snapshot");
        if (negotiation == Negotiation.CLEAR_AGAINST_PREFERENCE) {
            log.error("Replicating in clear to follower {} lane {}: this leader prefers encryption, the follower has it "
                    + "disabled", nodeId, lane);
        }
        long resumeOffset = resumed ? reader.getOffset() : WireFormat.NO_RESUME;
        ByteBuffer frame = ioSession.borrow(FrameEncoder.bytesWith(FrameType.WELCOME, leaderId));
        int length = FrameEncoder.welcome(frame.clear(), 0, currentEpoch, streams.getApplicationId(), leaderId,
                resumeOffset, logs.count(), keyExchange.getMode(), keyAgreement.getNonce());
        FrameCipher sealer = FrameCipher.CLEAR;
        if (encrypted) {
            keyAgreement.hello(FrameEncoder.helloBytes(epoch, applicationId, nodeId, lastAppliedEpoch, lastAppliedOffset,
                    lane, followerMode, followerNonce));
            keyAgreement.agree(followerNonce, FrameDecoder.bytes(frame, 0, length));
            sealer = keyAgreement.newSealer();
        }
        ioSession.send(BatchBuffers.written(frame, length), true);
        sender.start(replicationLog, reader, sealer);
    }

    @Override
    public void onKeyConfirmation(long epoch, byte[] confirmation) {
        if (state == State.DROPPED_AS_TOO_SLOW) {
            return;
        }
        if (state != State.AWAITING_KEY_CONFIRMATION || keyAgreement.getKeys() == null) {
            close(CloseReason.PROTOCOL_ERROR, "a KEY_CONFIRMATION on a connection not waiting for one");
            return;
        }
        if (!keyAgreement.confirms(confirmation)) {
            close(CloseReason.KEY_MISMATCH, "the follower's secret or cipher differ from this leader's "
                    + keyExchange.describeCipher());
            return;
        }
        answerOpener = keyAgreement.newOpener();
        if (STATE.compareAndSet(this, State.AWAITING_KEY_CONFIRMATION, State.STREAMING)) {
            log.info("Replicating encrypted to follower {} lane {}, {}", followerId, lane, keyExchange.describeCipher());
            sender.wakeup();
        }
    }

    /**
     * Positions the reader after the follower's last applied record, if that record is still in the log.
     */
    private boolean resume(long lastAppliedOffset) {
        if (lastAppliedOffset < 0 || !reader.seek(lastAppliedOffset)) {
            return false;
        }
        ByteBuffer skipped = ByteBuffer.allocate(logs.getMaxRecordFrameBytes());
        return reader.readInto(skipped, 0) >= 0;
    }

    @Override
    public void onReceived(long epoch, long receivedOffset) {
        acks.onReceived(receivedOffset);
    }

    @Override
    public void onAck(long epoch, long appliedOffset, boolean inSync, long pendingWrites) {
        acks.onAck(appliedOffset, inSync, pendingWrites);
    }

    void wakeSender() {
        sender.wakeup();
    }

    /**
     * How long the follower has been behind a write it has not applied yet, 0 when it is not, or not streaming.
     */
    public long getLagNanos(long nowNanos) {
        return state == State.STREAMING ? acks.getLagNanos(nowNanos) : 0;
    }

    /**
     * The follower holds everything of the stream up to its last ack, so a {@code SYNC} send of the stream waits
     * for its ack. Allocates nothing.
     */
    public boolean isStreamInSync(int streamKey) {
        return state == State.STREAMING && syncs.get(streamKey) == StreamSyncState.IN_SYNC;
    }

    @Nullable StreamSyncState getSyncState(int streamKey) {
        return syncs.get(streamKey);
    }

    @Override
    public void onStreamAnswer(long epoch, int streamKey, StreamAnswer answer) {
        if (syncs.get(streamKey) == null) {
            close(CloseReason.PROTOCOL_ERROR, "a STREAM_ANSWER for stream key " + streamKey + ", never announced");
            return;
        }
        syncs.answered(streamKey, answer);
        switch (answer) {
            case REPLICATING:
                syncs.set(streamKey, StreamSyncState.IN_SYNC);
                break;
            case SNAPSHOT_NEEDED:
                syncs.set(streamKey, StreamSyncState.SNAPSHOT_PENDING);
                sender.requestSnapshot();
                break;
            case NOT_REPLICATED:
                syncs.set(streamKey, StreamSyncState.NOT_REPLICATED);
                break;
            default:
                syncs.set(streamKey, StreamSyncState.NOT_REPLICATED);
                log.warn("Follower {} does not replicate stream {}: {}", followerId,
                        streams.get(streamKey).getStreamName(), answer);
        }
    }

    /**
     * Sends the stream again by snapshot, which resets the follower's copy first. Only a copy the follower holds is
     * replaced: a stream it refuses or is still catching up on is left as it is.
     */
    void resync(int streamKey) {
        if (carries(streamKey) && syncs.getAnswer(streamKey) == StreamAnswer.REPLICATING
                && syncs.compareAndSet(streamKey, StreamSyncState.IN_SYNC, StreamSyncState.SNAPSHOT_PENDING)) {
            sender.requestSnapshot();
        }
    }

    public @Nullable StreamAnswer getStreamAnswer(int streamKey) {
        return syncs.getAnswer(streamKey);
    }

    /**
     * @param streamCount the streams the leader replicates, every one of which the follower must hold to be in sync
     */
    public FollowerLaneStatus getStatus(int streamCount) {
        return FollowerLaneStatus.builder()
                .nodeId(followerId)
                .lane(lane)
                .address(address)
                .connected(isConnected())
                .inSync(isLaneInSync(streamCount))
                .snapshotInProgress(syncs.isSnapshotInProgress(streamCount))
                .unsentBytes(getUnsentBytes())
                .lagMillis(TimeUnit.NANOSECONDS.toMillis(getLagNanos(System.nanoTime())))
                .cipher(isEncrypted() ? keyExchange.describeCipher() : null)
                .build();
    }

    public boolean isConnected() {
        return state == State.STREAMING;
    }

    /**
     * Streaming encrypted, the follower having confirmed the keys.
     */
    public boolean isEncrypted() {
        return isConnected() && keyAgreement.getKeys() != null;
    }

    /**
     * This connection's keys once both peers encrypt, null on a clear one.
     */
    @Nullable ConnectionEncryptionKeys getEncryptionKeys() {
        return keyAgreement.getKeys();
    }

    /**
     * @param streamCount the streams the leader replicates, every one of which the follower must hold
     */
    public boolean isLaneInSync(int streamCount) {
        for (int key = 0; key < streamCount; key++) {
            if (carries(key) && !isStreamInSync(key) && streams.get(key).isReplicated()) {
                return false;
            }
        }
        return isConnected();
    }

    /**
     * Whether the stream is replicated over this lane, rather than another of the follower's.
     */
    public boolean carries(int streamKey) {
        return logs.laneOf(streamKey) == lane;
    }

    /**
     * The bytes the leader wrote and has not sent this follower, -1 before it started sending.
     */
    public long getUnsentBytes() {
        long sent = sender.getSentOffset();
        return sent < 0 ? -1 : replicationLog.getTailOffset() - sent;
    }

    @Override
    public void onClose(long epoch, CloseReason reason, String text) {
        log.info("Follower {} closed the replication stream: {} {}", followerId, reason, text);
        markClosed();
    }

    /**
     * The stream's replication mode changed: the follower stops holding it, catches up, or checks its own settings
     * agree.
     */
    void reannounce(int streamKey) {
        sender.reannounce(streamKey);
    }

    void onWriteWatermark(boolean reached) {
        sender.onWriteWatermark(reached);
    }

    /**
     * For a leader stopping cleanly, its writers already stopped: waits for every record of the log to be sent, then
     * closes with {@link CloseReason#SHUTDOWN}. Arriving after them on the same connection, it tells the follower it
     * holds everything, so the follower takes over knowing it lost no write. Returns once the follower hung up
     * on it, so it has read it before leadership moves. When the deadline passes first, the connection is dropped.
     *
     * @return whether the follower read it
     */
    boolean handOver(Deadline deadline) {
        if (state != State.STREAMING) {
            return false;
        }
        boolean sentAll = deadline.waitAsLongAs(() -> state == State.STREAMING
                && sender.getSentOffset() != replicationLog.getTailOffset()) && state == State.STREAMING;
        sender.stop();
        boolean senderStopped = deadline.waitAsLongAs(sender::isRunning);
        if (!sentAll || !senderStopped) {
            log.warn("Follower {} was not sent every record before the deadline: it will not take over as handed over",
                    followerId);
            markClosed();
            ioSession.stop(Deadline.immediate());
            return false;
        }
        if (!requestClose(PendingClose.thenAwaitHangUp(CloseReason.SHUTDOWN,
                "the leader stopped cleanly, every record was sent"))) {
            return false;
        }
        boolean read = deadline.waitAsLongAs(() -> !followerHungUp);
        ioSession.stop(Deadline.of(CLOSE_FLUSH));
        if (!read) {
            log.warn("Follower {} did not hang up on the hand over before the deadline: it may not take over as handed over",
                    followerId);
        }
        return read;
    }

    /**
     * Sends a CLOSE and disconnects, the connection flushing it first. Any thread; only the first close of the
     * connection does it. The IO thread sends it once the sender stopped, the sender leaving its loop at its next turn,
     * so it is sealed in order after the sender's last frame.
     *
     * @return false when the connection was already closed, and nothing sent
     */
    boolean close(CloseReason reason, String text) {
        return requestClose(PendingClose.thenStop(reason, text));
    }

    private boolean requestClose(PendingClose closing) {
        // recorded before the mark: a sender leaving its loop on the mark finds it
        if (!PENDING_CLOSE.compareAndSet(this, null, closing)) {
            return false;
        }
        if (!markClosed()) {
            PENDING_CLOSE.compareAndSet(this, closing, null);
            return false;
        }
        logClose(closing.getReason(), closing.getText());
        if (sender.isRunning()) {
            sender.wakeup();
        } else {
            sendPendingCloseOnIoThread();
        }
        return true;
    }

    /**
     * Run by the sender leaving its loop.
     */
    void senderStopped() {
        if (pendingClose != null) {
            sendPendingCloseOnIoThread();
        }
    }

    private void sendPendingCloseOnIoThread() {
        ioSession.processTask(this::sendPendingClose, (task, refusedOnceDisconnected) -> {
        });
    }

    private void sendPendingClose() {
        PendingClose closing = pendingClose;
        if (closing == null || !closeSent.compareAndSet(false, true)) {
            return;
        }
        FrameCipher sealer = sender.isStreamStarted() ? sender.getSealer() : FrameCipher.CLEAR;
        ByteBuffer frame = ioSession.borrow(FrameEncoder.bytesWith(FrameType.CLOSE, closing.getText())
                + sealer.getTagBytes());
        int length = FrameEncoder.close(frame.clear(), 0, nodeEpoch.getAsLong(), closing.getReason(), closing.getText());
        ioSession.send(BatchBuffers.written(frame, sealer.seal(frame, 0, length)), true);
        if (closing.isStopAfter()) {
            ioSession.stop(Deadline.of(CLOSE_FLUSH));
        }
    }

    private void logClose(CloseReason reason, String shortText) {
        if (reason == CloseReason.SHUTDOWN || reason == CloseReason.RETRY_LATER) {
            log.info("Closing the replication stream to follower {} lane {}: {} {}", followerId, lane, reason, shortText);
        } else if (reason.isEncryptionRefusal()) {
            log.error("Closing the replication stream to follower {} lane {}: {} {}", followerId, lane, reason, shortText);
        } else {
            log.warn("Closing the replication stream to follower {} lane {}: {} {}", followerId, lane, reason, shortText);
        }
    }

    /**
     * The connection is gone: the send loop ends at its next turn.
     */
    void disconnected() {
        followerHungUp = true;
        markClosed();
    }

    /**
     * Run by the appending thread, which only marks the connection: closing it would hold that thread, and the log,
     * flushing the CLOSE to a follower that does not read. The sender, or the HELLO before it starts, closes it.
     */
    void droppedAsTooSlow() {
        State current;
        do {
            current = state;
            if (current != State.AWAITING_HELLO && current != State.AWAITING_KEY_CONFIRMATION && current != State.STREAMING) {
                return;
            }
        } while (!STATE.compareAndSet(this, current, State.DROPPED_AS_TOO_SLOW));
        stats.droppedTooSlow(followerId, lane);
        sender.wakeup();
    }

    void closeDropped() {
        close(CloseReason.TOO_SLOW, "a write waited the log full timeout for it");
    }

    /**
     * @return false when another thread closed the connection first
     */
    private boolean markClosed() {
        State current;
        do {
            current = state;
            if (current == State.CLOSED) {
                return false;
            }
        } while (!STATE.compareAndSet(this, current, State.CLOSED));
        ReplicationLog.Reader opened = reader;
        if (opened != null) {
            opened.close();
        }
        return true;
    }

    @Value
    private static class PendingClose {
        CloseReason reason;
        String text;
        /**
         * Stop the connection once the CLOSE is sent, rather than wait for the follower to hang up as a hand over does.
         */
        boolean stopAfter;

        static PendingClose thenStop(CloseReason reason, String text) {
            return new PendingClose(reason, FrameEncoder.closeText(text), true);
        }

        static PendingClose thenAwaitHangUp(CloseReason reason, String text) {
            return new PendingClose(reason, FrameEncoder.closeText(text), false);
        }
    }

    public enum State {
        AWAITING_HELLO,
        /**
         * Both peers encrypt: nothing streams until the follower's KEY_CONFIRMATION verifies.
         */
        AWAITING_KEY_CONFIRMATION,
        STREAMING,
        /**
         * Dropped as too slow by a write, its CLOSE not sent yet.
         */
        DROPPED_AS_TOO_SLOW,
        CLOSED
    }
}
