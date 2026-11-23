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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import org.lolaf.staffix.ha.replication.encryption.ConnectionEncryptionKeys;
import org.lolaf.staffix.ha.replication.encryption.KeyExchange;
import org.lolaf.staffix.ha.replication.encryption.Negotiation;
import org.lolaf.staffix.ha.replication.wire.*;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.ObjIntConsumer;

/**
 * One connection to the leader, for one of its replication lanes: the handshake, then the leader's frames for the
 * lane's streams applied to the {@link ReplicaStreams}, all on the connection's IO thread. Every frame read is
 * acknowledged at the end of the read, so a {@code SYNC} send on the leader waits one round trip, not a timer.
 *
 * <p>A frame from another epoch than the leader's WELCOME closes the connection: the leader it came from is not the
 * one this stream follows.
 */
@Slf4j
public final class LeaderConnection implements FrameHandler {

    private static final Duration CLOSE_FLUSH = Duration.ofSeconds(1);
    /**
     * Fits an {@link ThroughNetworkThreadAnswerSender} frame, at three bytes a character.
     */
    private final IOSession ioSession;
    private final ReplicaStreams replicaStreams;
    /**
     * This node's current election epoch, sent in its HELLO and CLOSE; a WELCOME of an older epoch is refused.
     */
    private final LongSupplier nodeEpoch;
    private final String nodeId;
    @Getter
    private final int lane;
    private final IntConsumer lanesAnnounced;
    private final AnswerSender answers;
    private final boolean receivedAckedByNetworkThread;
    private final long checkIntervalNanos;
    private final ObjIntConsumer<StreamAnswer> sendBoundAnswer;
    private final KeyExchange keyExchange;
    /**
     * This connection's keys once both peers encrypt, null on a clear one.
     */
    @Getter(AccessLevel.PACKAGE)
    private volatile @Nullable ConnectionEncryptionKeys encryptionKeys;
    /**
     * Opens the leader's frames once both peers encrypt; the thread applying them only.
     */
    private FrameCipher frameCipher;
    private byte[] followerNonce;
    /**
     * The HELLO as sent, which the KEY_CONFIRMATION covers.
     */
    private byte @Nullable [] sentHelloBytes;
    @Getter
    private volatile State state;
    @Getter
    private volatile @Nullable String leaderId;
    /**
     * The epoch of the stream followed, from the leader's WELCOME: every later frame must carry it, and the answers
     * are stamped with it.
     */
    private volatile long streamEpoch;
    private int streamLanes;
    private long streamResumeOffset;
    private boolean ackDue;
    private boolean applyFailed;
    private long rebindCheckedNanos;
    private long pendingWrites;
    private boolean pendingWritesRead;
    private long pendingWritesReadNanos;
    private boolean ackedInSync;
    private int streamFramesBeforeAnnounced;
    private boolean streamsAnnounced;

    /**
     * @param lanesAnnounced               told the leader's lane count from its WELCOME, before the stream starts
     * @param answers                      how this lane's answers to the leader reach the connection
     * @param receivedAckedByNetworkThread the network thread acknowledges records as received as it reads them, a sink
     *                                     writer thread writing them
     */
    LeaderConnection(IOSession ioSession, ReplicaStreams replicaStreams, LongSupplier nodeEpoch, String nodeId, int lane, IntConsumer lanesAnnounced,
                     long checkIntervalNanos, AnswerSender answers, boolean receivedAckedByNetworkThread,
                     KeyExchange keyExchange) {
        this.sendBoundAnswer = (answer, streamKey) -> sendStreamAnswer(streamKey, answer);
        this.frameCipher = FrameCipher.CLEAR;
        this.followerNonce = new byte[WireFormat.NONCE_BYTES];
        this.state = State.AWAITING_WELCOME;
        this.keyExchange = keyExchange;
        this.answers = answers;
        this.receivedAckedByNetworkThread = receivedAckedByNetworkThread;
        this.checkIntervalNanos = checkIntervalNanos;
        this.ioSession = ioSession;
        this.replicaStreams = replicaStreams;
        this.nodeEpoch = nodeEpoch;
        this.nodeId = nodeId;
        this.lane = lane;
        this.lanesAnnounced = lanesAnnounced;
    }

    void hello() {
        followerNonce = keyExchange.newNonce();
        ByteBuffer frame = answers.claim(FrameEncoder.bytesWith(FrameType.HELLO, nodeId));
        int length = FrameEncoder.hello(frame, 0, nodeEpoch.getAsLong(), replicaStreams.getApplicationId(), nodeId,
                replicaStreams.getLastAppliedEpoch(lane), replicaStreams.getLastAppliedOffset(lane), lane,
                keyExchange.getMode(), followerNonce);
        sentHelloBytes = FrameDecoder.bytes(frame, 0, length);
        answers.send(frame, length);
    }

    /**
     * With a sink writer thread the network thread acknowledges a record as received as it reads it, but not on an
     * encrypted connection: the writer thread then does, once the record's tag verified.
     */
    boolean isReceivedAckedOnRead() {
        return receivedAckedByNetworkThread && encryptionKeys == null;
    }

    /**
     * Streaming encrypted, the leader having accepted the keys.
     */
    public boolean isEncrypted() {
        return state == State.STREAMING && encryptionKeys != null;
    }

    /**
     * Whether a record of this epoch read now can be acknowledged as received: the stream it belongs to is the one
     * this lane follows.
     */
    boolean isStreamEpoch(long epoch) {
        State current = state;
        return (current == State.STREAMING || current == State.AWAITING_KEY_ACCEPTANCE) && epoch == streamEpoch;
    }

    @Override
    public void onWelcome(long epoch, int version, int applicationId, String leaderId, long resumeOffset, int lanes,
                          EncryptionMode leaderMode, byte[] leaderNonce) {
        if (state != State.AWAITING_WELCOME) {
            close(CloseReason.PROTOCOL_ERROR, "a second WELCOME");
            return;
        }
        if (version != WireFormat.VERSION) {
            close(CloseReason.VERSION_MISMATCH, "follower speaks version " + WireFormat.VERSION + ", leader " + version);
            return;
        }
        if (applicationId != replicaStreams.getApplicationId()) {
            close(CloseReason.APPLICATION_MISMATCH, "follower replicates application "
                    + Integer.toHexString(replicaStreams.getApplicationId()) + ", leader " + Integer.toHexString(applicationId));
            return;
        }
        long currentEpoch = nodeEpoch.getAsLong();
        if (epoch < currentEpoch) {
            close(CloseReason.STALE_EPOCH, "leader is at epoch " + epoch + ", this follower at " + currentEpoch);
            return;
        }
        if (lane >= lanes) {
            close(CloseReason.PROTOCOL_ERROR, "a WELCOME on lane " + lane + " of " + lanes);
            return;
        }
        this.leaderId = leaderId;
        Negotiation negotiation = keyExchange.negotiate(leaderMode);
        if (negotiation == Negotiation.REFUSED) {
            close(CloseReason.ENCRYPTION_REQUIRED, "this follower's encryption is " + keyExchange.getMode()
                    + ", the leader's " + leaderMode);
            return;
        }
        streamEpoch = epoch;
        streamLanes = lanes;
        streamResumeOffset = resumeOffset;
        if (negotiation == Negotiation.ENCRYPTED) {
            confirmKeys(epoch, applicationId, leaderId, resumeOffset, lanes, leaderMode, leaderNonce);
            state = State.AWAITING_KEY_ACCEPTANCE;
            return;
        }
        if (negotiation == Negotiation.CLEAR_AGAINST_PREFERENCE) {
            log.error("Following leader {} in clear on lane {}: this follower prefers encryption, the leader has it "
                    + "disabled", leaderId, lane);
        }
        startStreaming();
    }

    private void startStreaming() {
        lanesAnnounced.accept(streamLanes);
        boolean resumed = streamResumeOffset != WireFormat.NO_RESUME;
        replicaStreams.streamStarted(lane, resumed);
        state = State.STREAMING;
        ackDue = true;
        if (encryptionKeys != null) {
            log.info("Following leader {} encrypted on lane {}, {}", leaderId, lane, keyExchange.describeCipher());
        }
        log.info("Following leader {} at epoch {} on lane {} of {}, {}", leaderId, streamEpoch, lane, streamLanes,
                resumed ? "resuming at offset " + streamResumeOffset : "from its log tail, needing a snapshot");
    }

    /**
     * Sent before anything else this connection answers, so the leader verifies it before it streams.
     */
    private void confirmKeys(long epoch, int applicationId, String leaderId, long resumeOffset, int lanes,
                             EncryptionMode leaderMode, byte[] leaderNonce) {
        ConnectionEncryptionKeys keys = keyExchange.deriveKeys(followerNonce, leaderNonce);
        byte[] receivedWelcomeBytes = FrameEncoder.welcomeBytes(epoch, applicationId, leaderId, resumeOffset, lanes,
                leaderMode, leaderNonce);
        byte[] confirmation = keyExchange.confirm(keys, sentHelloBytes, receivedWelcomeBytes);
        frameCipher = keyExchange.newOpener(keys.getLeaderToFollower());
        encryptionKeys = keys;
        sentHelloBytes = null;
        ByteBuffer frame = answers.claim(FrameEncoder.fixedBytes(FrameType.KEY_CONFIRMATION));
        answers.sendKeyConfirmation(frame, FrameEncoder.keyConfirmation(frame, 0, nodeEpoch.getAsLong(), confirmation),
                keyExchange.newSealer(keys.getFollowerToLeader()));
    }

    @Override
    public void onStream(long epoch, int streamKey, StreamReplicationMode replicationMode, String streamName,
                         @Nullable String storageIdentity) {
        if (!acceptsFrame(epoch)) {
            return;
        }
        if (!replicaStreams.carries(lane, streamKey)) {
            close(CloseReason.PROTOCOL_ERROR, "stream key " + streamKey + " on lane " + lane + " of "
                    + replicaStreams.getLanes());
            return;
        }
        if (!streamsAnnounced) {
            streamFramesBeforeAnnounced++;
        }
        sendStreamAnswer(streamKey, replicaStreams.bind(streamKey, streamName, replicationMode, storageIdentity));
    }

    @Override
    public void onStreamsAnnounced(long epoch, int announcedCount) {
        if (!acceptsFrame(epoch)) {
            return;
        }
        if (streamsAnnounced) {
            close(CloseReason.PROTOCOL_ERROR, "a second STREAMS_ANNOUNCED");
            return;
        }
        if (announcedCount != streamFramesBeforeAnnounced) {
            close(CloseReason.PROTOCOL_ERROR, "STREAMS_ANNOUNCED counts " + announcedCount + " streams, "
                    + streamFramesBeforeAnnounced + " were announced");
            return;
        }
        streamsAnnounced = true;
        replicaStreams.streamsAnnounced(lane);
        ackDue = true;
    }

    private void sendStreamAnswer(int streamKey, StreamAnswer answer) {
        ByteBuffer frame = answers.claim(FrameEncoder.fixedBytes(FrameType.STREAM_ANSWER));
        answers.send(frame, FrameEncoder.streamAnswer(frame, 0, streamEpoch, streamKey, answer));
    }

    @Override
    public void onRecord(long epoch, long logOffset, int streamKey, byte contentType, byte flags, long position,
                         ByteBuffer buffer, int payloadIndex, int payloadLength) {
        if (!acceptsFrame(epoch)) {
            return;
        }
        if (!replicaStreams.apply(lane, epoch, logOffset, streamKey, contentType, flags, position, buffer, payloadIndex,
                payloadLength)) {
            close(CloseReason.PROTOCOL_ERROR, "a RECORD for stream key " + streamKey
                    + " never announced on this lane, or a fragment out of its payload");
            return;
        }
        ackDue = true;
    }

    @Override
    public void onHeartbeat(long epoch, long logTailOffset) {
        if (acceptsFrame(epoch)) {
            ackDue = true;
        }
    }

    @Override
    public void onClose(long epoch, CloseReason reason, String text) {
        if (reason == CloseReason.SHUTDOWN && state == State.STREAMING && epoch == streamEpoch) {
            log.info("Leader {} stopped cleanly and handed over: {}", leaderName(), text);
            replicaStreams.handedOver(lane);
        } else if (reason == CloseReason.RETRY_LATER) {
            log.info("Leader {} holds this follower off: {}", leaderName(), text);
        } else if (reason.isEncryptionRefusal()) {
            log.error("Leader {} refused the replication stream: {} {}", leaderName(), reason, text);
        } else {
            log.warn("Leader {} closed the replication stream: {} {}", leaderName(), reason, text);
        }
        state = State.CLOSED;
        // hanging up tells the leader the close was read, which a hand over waits for before leadership moves
        ioSession.stop(Deadline.immediate());
    }

    /**
     * Applies every whole frame of a read, then acknowledges it. A frame that cannot be decoded or applied closes the
     * stream: the frames after it in the read are dropped, and the follower reconnects to resume after the last record
     * it applied rather than carrying on past a gap.
     */
    void read(ByteBuffer message, FrameDecoder decoder) {
        int end = apply(message, message.position(), message.limit(), decoder);
        if (applyFailed) {
            message.position(message.limit());
            return;
        }
        endRead();
        message.position(end);
    }

    /**
     * Applies the whole frames from {@code index} to {@code limit}, a part of a read; {@link #endRead()} then writes
     * and acknowledges them.
     *
     * @return where the last whole frame ends, {@code limit} once a frame failed
     */
    int apply(ByteBuffer frames, int index, int limit, FrameDecoder decoder) {
        if (applyFailed) {
            return limit;
        }
        try {
            int consumed;
            while ((consumed = decoder.decode(frames, index, limit - index, this, openerOf(frames, index, limit))) > 0) {
                index += consumed;
            }
            return index;
        } catch (WireFormatException e) {
            failed(CloseReason.PROTOCOL_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Failed to apply what leader {} sent", leaderId, e);
            failed(CloseReason.APPLY_FAILED, e.toString());
        }
        return limit;
    }

    /**
     * Until the leader accepts the keys, it seals nothing: a refusal comes as a clear CLOSE.
     */
    private FrameCipher openerOf(ByteBuffer frames, int index, int limit) {
        boolean closeBeforeAcceptance = state == State.AWAITING_KEY_ACCEPTANCE && limit - index > WireFormat.OFFSET_TYPE
                && frames.get(index + WireFormat.OFFSET_TYPE) == FrameType.CLOSE.getCode();
        return closeBeforeAcceptance ? FrameCipher.CLEAR : frameCipher;
    }

    /**
     * Writes and acknowledges what the frames applied since the last call left pending, unless one failed: those
     * after it are dropped, and the follower reconnects to resume after the last record it applied.
     */
    void endRead() {
        if (applyFailed) {
            return;
        }
        try {
            writeAndAck();
        } catch (RuntimeException e) {
            log.warn("Failed to apply what leader {} sent", leaderId, e);
            failed(CloseReason.APPLY_FAILED, e.toString());
        }
    }

    private void failed(CloseReason reason, String text) {
        applyFailed = true;
        close(reason, text);
    }

    private void writeAndAck() {
        long received = replicaStreams.takeReceivedToAck(lane);
        if (state == State.STREAMING && received > replicaStreams.getLastAppliedOffset(lane)) {
            if (!isReceivedAckedOnRead()) {
                ByteBuffer frame = answers.claim(FrameEncoder.fixedBytes(FrameType.RECEIVED));
                answers.send(frame, FrameEncoder.received(frame, 0, streamEpoch, received));
            }
            long receivedSentNanos = System.nanoTime();
            replicaStreams.writeApplied(lane);
            replicaStreams.getReceivedWriteLatency().record(System.nanoTime() - receivedSentNanos, TimeUnit.NANOSECONDS);
        } else {
            replicaStreams.writeApplied(lane);
        }
        if (state == State.STREAMING) {
            bindStreamsAgain();
        }
        if (!ackDue || state != State.STREAMING) {
            return;
        }
        ackDue = false;
        boolean inSync = replicaStreams.isLaneInSync(lane);
        ByteBuffer frame = answers.claim(FrameEncoder.fixedBytes(FrameType.ACK));
        answers.send(frame, FrameEncoder.ack(frame, 0, streamEpoch, replicaStreams.getLastAppliedOffset(lane), inSync,
                pendingWrites(inSync)));
    }

    /**
     * At most once a check interval, as it asks every stream's sink, and at once on turning in sync, when the leader
     * starts to trust it.
     */
    private long pendingWrites(boolean inSync) {
        long now = System.nanoTime();
        if (!pendingWritesRead || inSync != ackedInSync || now - pendingWritesReadNanos >= checkIntervalNanos) {
            pendingWritesRead = true;
            ackedInSync = inSync;
            pendingWritesReadNanos = now;
            pendingWrites = replicaStreams.getPendingWrites(lane);
        }
        return pendingWrites;
    }

    /**
     * At most once a check interval: the binder looks each stream up, and a
     * refusal is rare.
     */
    private void bindStreamsAgain() {
        long now = System.nanoTime();
        if (now - rebindCheckedNanos < checkIntervalNanos || !replicaStreams.hasStreamsToBindAgain(lane)) {
            return;
        }
        rebindCheckedNanos = now;
        replicaStreams.bindAgain(lane, sendBoundAnswer);
    }

    /**
     * The leader's first frame after a KEY_CONFIRMATION starts the stream: it sends none before it verified it.
     */
    private boolean acceptsFrame(long epoch) {
        State current = state;
        if (current != State.STREAMING && current != State.AWAITING_KEY_ACCEPTANCE) {
            close(CloseReason.PROTOCOL_ERROR, "a frame before WELCOME");
            return false;
        }
        if (epoch != streamEpoch) {
            close(CloseReason.STALE_EPOCH, "a frame of epoch " + epoch + " in a stream of epoch " + streamEpoch);
            return false;
        }
        if (current == State.AWAITING_KEY_ACCEPTANCE) {
            startStreaming();
        }
        return true;
    }

    @Override
    public void onSnapshotBegin(long epoch, int streamKey, long snapshotOffset, ByteBuffer buffer, int stateIndex,
                                int stateLength) {
        if (acceptsFrame(epoch) && !replicaStreams.snapshotBegin(lane, streamKey, buffer, stateIndex, stateLength)) {
            close(CloseReason.PROTOCOL_ERROR, "a snapshot of stream key " + streamKey + ", not replicated here");
        }
    }

    @Override
    public void onSnapshotRecord(long epoch, int streamKey, byte contentType, byte flags, long position,
                                 ByteBuffer buffer, int payloadIndex, int payloadLength) {
        if (acceptsFrame(epoch) && !replicaStreams.snapshotRecord(lane, streamKey, contentType, flags, position, buffer,
                payloadIndex, payloadLength)) {
            close(CloseReason.PROTOCOL_ERROR, "a snapshot record of stream key " + streamKey
                    + " not replicated here, or a fragment out of its payload");
        }
    }

    /**
     * Tells the leader the stream is now in sync, which is when its {@code SYNC} writes start waiting for this
     * follower.
     */
    @Override
    public void onSnapshotEnd(long epoch, int streamKey, int recordCount, ByteBuffer buffer, int stateIndex,
                              int stateLength) {
        if (!acceptsFrame(epoch)) {
            return;
        }
        if (!replicaStreams.snapshotEnd(lane, streamKey, recordCount, buffer, stateIndex, stateLength)) {
            close(CloseReason.PROTOCOL_ERROR, "a snapshot of stream key " + streamKey + " that does not end with "
                    + recordCount + " records received");
            return;
        }
        sendStreamAnswer(streamKey, StreamAnswer.REPLICATING);
        ackDue = true;
    }

    /**
     * The leader's id, or its address before its WELCOME named it: a leader may close before sending one.
     */
    private String leaderName() {
        String id = leaderId;
        return id != null ? id : "at " + ioSession.getSocketAddress();
    }

    /**
     * Sends a CLOSE and disconnects, the connection flushing it first; the client connects again.
     */
    void close(CloseReason reason, String text) {
        if (state == State.CLOSED) {
            return;
        }
        String shortText = FrameEncoder.closeText(text);
        if (reason.isEncryptionRefusal()) {
            log.error("Closing the replication stream from leader {}: {} {}", leaderName(), reason, shortText);
        } else {
            log.warn("Closing the replication stream from leader {}: {} {}", leaderName(), reason, shortText);
        }
        state = State.CLOSED;
        ByteBuffer frame = answers.claim(FrameEncoder.bytesWith(FrameType.CLOSE, shortText));
        answers.send(frame, FrameEncoder.close(frame, 0, nodeEpoch.getAsLong(), reason, shortText));
        ioSession.stop(Deadline.of(CLOSE_FLUSH));
    }

    void disconnected() {
        state = State.CLOSED;
    }

    public enum State {
        AWAITING_WELCOME,
        AWAITING_KEY_ACCEPTANCE,
        STREAMING,
        CLOSED
    }

}
