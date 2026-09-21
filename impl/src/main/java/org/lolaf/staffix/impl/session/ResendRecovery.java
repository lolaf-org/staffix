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
package org.lolaf.staffix.impl.session;

import lombok.Getter;
import lombok.Value;
import org.lolaf.staffix.api.session.ResendRequestRange;
import org.lolaf.staffix.api.stores.FixMessagesStore;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Everything that recovering a gap in the incoming MsgSeqNum(34) sequence needs to remember: the one
 * ResendRequest(35=2) outstanding, the messages received on top of the gap while it is, and how long the answer has
 * been making no progress.
 * <p>
 * The invariant the whole of it rests on: <b>the queue of held messages is only ever drained by a request
 * completing</b>. Nothing else looks at it, so a gap recovered without a request recorded here is recovered on the
 * wire and never delivered to the application, and a request that can never complete stops the session dead while
 * heartbeats keep both ends believing it is healthy. Every path that ends a recovery must therefore either complete
 * it - {@link #onSequenceNumbersSettledUpTo(long)} - or take the session down, which is what
 * {@link #onResendRequestStallCheck(int)} is for.
 * <p>
 * Owned by the IO thread of the session, every method of it: the stall check of {@link RetransmissionComponent}
 * is timed by the scheduler but runs here on the IO thread like everything else.
 */
public class ResendRecovery {

    /**
     * Queued in place of a message that was processed as it arrived, out of sequence and all - a Logon(35=A), a
     * ResendRequest(35=2) - and whose MsgSeqNum(34) alone is still owed. The replay consumes the slot without
     * reprocessing anything.
     */
    private static final byte[] EMPTY_QUEUED_MESSAGE = new byte[0];

    private final FixSessionImpl fixSession;
    private final RetransmissionComponent retransmission;
    private final HeldOutgoingMessagesComponent heldOutgoingMessages;
    private final LogonLogoutComponent logonLogout;
    private final FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore;
    private final NavigableMap<Long, byte[]> outOfSequenceMessages = new TreeMap<>();
    @Getter
    private ResendRequest pendingResendRequest;
    /**
     * Whether a retransmission has been outstanding at any point on this connection, which outlives the request
     * itself: it is what tells the tail of an open ended answer from an unsolicited reset, long after the request it
     * answers has been settled. Read through {@link #isAnsweringOwnOpenEndedRequest()} only.
     */
    private boolean retransmissionRequested;
    /**
     * How long the answer to {@link #pendingResendRequest} has been making no progress, counted in stall check
     * ticks. Incremented by that task and put back to zero by the IO thread as the answer comes in: a tick of slop
     * either way is of no consequence to a timeout counted in tens of seconds.
     */
    private int resendRequestStalledSeconds;
    /**
     * Whether {@link #pendingResendRequest} is the second attempt, after which a stalled retransmission is given up
     * on rather than asked for again.
     */
    private boolean resendRequestRetried;
    private boolean replayingOutOfSequenceMessages;
    @Getter
    private boolean outOfSequenceMessagesReplayRequested;

    ResendRecovery(FixSessionImpl fixSession, RetransmissionComponent retransmission,
                   HeldOutgoingMessagesComponent heldOutgoingMessages, LogonLogoutComponent logonLogout,
                   FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore) {
        this.fixSession = fixSession;
        this.retransmission = retransmission;
        this.heldOutgoingMessages = heldOutgoingMessages;
        this.logonLogout = logonLogout;
        this.fixSessionMessagesStore = fixSessionMessagesStore;
    }

    public boolean hasPendingResendRequest() {
        return pendingResendRequest != null;
    }

    /**
     * Whether this session asks peers for open ended ranges, and has actually asked one on this connection - which is
     * as close as anything gets to knowing that a gap fill turning up unaccounted for is the tail of an answer rather
     * than an unsolicited reset. Nothing on the message says which request it answers: a SequenceReset(35=4) carries
     * no reference to one, so what it is has to be inferred from what this side asked for.
     * <p>
     * A session asking {@link ResendRequestRange#OPEN_ENDED} tells the peer "everything from BeginSeqNo(7) onwards"
     * and is answered up to whatever the peer had sent, which is past the end of the gap that prompted the request -
     * the message that revealed the gap, and anything sent after it, are inside the range asked for. That request is
     * settled at the end of the gap, so the messages accounting for the rest arrive with nothing pending: the last of
     * them is typically a gap fill covering the session level messages, and taking it for an unsolicited reset would
     * log out a session whose peer did exactly what it was asked.
     * <p>
     * The two conditions are both needed and neither is enough. The setting alone would have a session that has never
     * asked for a retransmission accept a gap fill out of nowhere; having asked alone would extend the tolerance to a
     * session asking closed ranges, which is answered within the range it named and for which an unsolicited gap fill
     * stays the protocol violation it is.
     * <p>
     * Such a tail is met twice, on either side of the queue held on top of the gap being replayed: before it, the gap
     * fill is merely below what the session expects and is ignored by {@link IncomingMessagesComponent#onMessageDecodingFailed}; after
     * it, the message is rolled back before the decoder ever processes it, which
     * {@link org.lolaf.staffix.impl.session.codec.SequenceResetFixMessageDecoder} answers by not taking the
     * connection down. Both ask here.
     */
    public boolean isAnsweringOwnOpenEndedRequest() {
        return fixSession.getFixSessionSettings().getResendRequestRange().equals(ResendRequestRange.OPEN_ENDED)
                && retransmissionRequested;
    }

    /**
     * Holds an out of sequence message until the gap ahead of it has been filled, as section 4.5 state table rows 11
     * and 12 require: "receive too high of MsgSeqNum(34) from counterparty, queue message, and send
     * ResendRequest(35=2)", then while awaiting the response "queue incoming messages with MsgSeqNum(34) too high".
     * Dropping them instead leaves the session permanently one message behind, since each new message then arrives
     * into a gap of its own making.
     * <p>
     * A Logon(35=A) is the exception: it is processed on the spot by its decoder, out of sequence and all - the
     * acknowledgement goes out and the session comes up - so replaying it later would log on twice. Its MsgSeqNum(34)
     * still has to be accounted for, which {@link #EMPTY_QUEUED_MESSAGE} does.
     * <p>
     * The queue is bounded by {@link org.lolaf.staffix.api.session.FixSessionSettings#getMaxOutOfSequenceMessagesQueued()},
     * 0 meaning no bound at all: a peer that floods new messages while never answering the ResendRequest would
     * otherwise grow it without end, so the session is logged out instead once the limit is reached.
     */
    public void queueOutOfSequenceMessage(boolean processedOnArrival, long msgSeqNum, byte[] rawMessage) {
        if (rawMessage == null) {
            return;
        }
        int maxQueued = fixSession.getFixSessionSettings().getMaxOutOfSequenceMessagesQueued();
        if (maxQueued > 0 && outOfSequenceMessages.size() >= maxQueued) {
            fixSession.logEvent("Too many out of order messages queued (%s) while recovering, disconnecting",
                    outOfSequenceMessages.size());
            fixSession.logout("Too many messages received while awaiting the response to a ResendRequest");
            outOfSequenceMessages.clear();
            return;
        }
        outOfSequenceMessages.put(msgSeqNum, processedOnArrival ? EMPTY_QUEUED_MESSAGE : rawMessage);
    }

    /**
     * Asks for the queued messages to be replayed once the message being decoded right now is done with.
     * <p>
     * A resend completes in the middle of decoding the message that completed it, and replaying feeds whole messages
     * back through a parser: doing it there and then would re-enter the parser while it still holds the state of the
     * message in flight. {@link FixSessionImpl#onMessageRead} picks this up once the read is fully parsed.
     */
    public void requestOutOfSequenceMessagesReplay() {
        outOfSequenceMessagesReplayRequested = true;
    }

    /**
     * Feeds back the messages held by {@link #queueOutOfSequenceMessage} now that the gap before them is filled, in
     * MsgSeqNum(34) order, exactly as if they had just been read off the socket.
     * <p>
     * Stops as soon as the head of the queue is beyond what the session expects next: that is a gap of its own, which
     * the drain asks for. The loop therefore always terminates, whether it empties the queue or runs into the next
     * gap.
     */
    public void replayOutOfSequenceMessages() {
        if (replayingOutOfSequenceMessages) {
            // replaying runs the full decoding path, which may complete another resend and ask for a replay again
            return;
        }
        replayingOutOfSequenceMessages = true;
        try {
            while (outOfSequenceMessagesReplayRequested) {
                outOfSequenceMessagesReplayRequested = false;
                drainOutOfSequenceMessages();
            }
        } finally {
            replayingOutOfSequenceMessages = false;
        }
    }

    private void drainOutOfSequenceMessages() {
        Map.Entry<Long, byte[]> queued;
        while ((queued = outOfSequenceMessages.firstEntry()) != null) {
            long queuedSeqNum = queued.getKey();
            long expectedSeqNum = fixSessionMessagesStore.getIncomingSeqNum();
            if (queuedSeqNum > expectedSeqNum) {
                // a gap opens again before this one, and nothing else will come back to this queue: a replay is only
                // ever asked for by a request completing, so leaving it here without asking for what stands in its
                // way strands it - and with it every message the peer has already delivered. A new gap that opened
                // while the last request was outstanding was queued without one of its own being sent, see
                // FixSessionImpl.onMessageDecodingFailed, so this is where it gets asked for.
                if (pendingResendRequest == null) {
                    retransmission.requestRetransmission(expectedSeqNum, queuedSeqNum - 1,
                            "Gap left before the messages held while recovering");
                }
                return;
            }
            outOfSequenceMessages.pollFirstEntry();
            if (queuedSeqNum < expectedSeqNum) {
                // already accounted for, by a gap fill covering it or by the peer having resent it
                if (queued.getValue() != EMPTY_QUEUED_MESSAGE) {
                    // a message this session was handed and never delivered to the application: the peer accounted
                    // for its MsgSeqNum some other way - a gap fill covering it, most likely - so the session layer
                    // is right to move past it, but it is a message the peer did send and it leaves here silently
                    // otherwise
                    fixSession.logEvent("Dropping the message held with MsgSeqNum %s, already accounted for by the "
                            + "retransmission which left MsgSeqNum %s expected", queuedSeqNum, expectedSeqNum);
                }
                continue;
            }
            if (queued.getValue() == EMPTY_QUEUED_MESSAGE) {
                // processed when it arrived, a Logon: only its sequence number is still owed
                fixSession.logEvent("Consuming MsgSeqNum %s of the out of order message that opened the gap", queuedSeqNum);
                fixSessionMessagesStore.storeNextIncomingSeqNum(queuedSeqNum + 1);
                continue;
            }
            fixSession.logEvent("Replaying out of order message with MsgSeqNum %s", queuedSeqNum);
            if (!retransmission.replayOutOfSequenceMessage(queuedSeqNum, queued.getValue())) {
                return;
            }
        }
    }

    public void onResendRequestSent(long beginSeqNo, long endSeqNo) {
        // we may take more time that the define logon timeout to process the resend request,
        // disable the task if needed
        logonLogout.cancelLogonOrLogoutTaskIfNeeded();
        pendingResendRequest = new ResendRequest(beginSeqNo, endSeqNo);
        retransmissionRequested = true;
        resendRequestStalledSeconds = 0;
        // section 4.3.11: hold new application messages back until the session is synchronized again
        heldOutgoingMessages.startHoldingIfNeeded();
    }

    /**
     * Reports progress on the retransmission this session is waiting for, and completes it once the whole range has
     * been accounted for.
     *
     * @param lastSettledSeqNum the highest MsgSeqNum(34) the peer has now accounted for, which is not always the one
     *                          on the message that says so: an ordinary message settles itself, a hard
     *                          SequenceReset(35=4) settles everything below the NewSeqNo(36) it restarts the
     *                          numbering at <em>and</em> that number itself, nothing of the old numbering being left
     *                          to arrive, while a gap fill settles only what is below NewSeqNo(36) - the message
     *                          carrying that number is still owed. A request ending exactly there is owed its last
     *                          message, so passing NewSeqNo(36) for a gap fill would close it one message early: the
     *                          queue held on top of the gap is then released while the sequence number it waits for
     *                          has not been reached, the drain leaves it queued, and nothing is left to ask for
     *                          another replay.
     */
    public void onSequenceNumbersSettledUpTo(long lastSettledSeqNum) {
        if (pendingResendRequest == null) {
            return;
        }
        // the answer is coming in: whatever is left of the range is being worked through, so the wait starts over
        resendRequestStalledSeconds = 0;
        if (lastSettledSeqNum >= pendingResendRequest.getToSeqNum()) {
            onResendRequestFinished(lastSettledSeqNum);
        }
    }

    /**
     * Called once a second while a ResendRequest(35=2) of this session's own is outstanding, to decide whether the
     * answer has stopped coming.
     * <p>
     * The recovery is the session: until it completes nothing held on top of the gap is delivered, no new gap is
     * asked for and outgoing application messages stay held, all of it while heartbeats keep both ends believing the
     * session is healthy. An answer that stops half way - a garbled message inside the range is the ordinary way
     * there, since disregarding it is exactly what section 4.8 asks for - would otherwise stall the session for good.
     *
     * @return what to do about it, {@link StalledResendAction#NOTHING} for as long as the answer is making progress
     */
    public StalledResendAction onResendRequestStallCheck(int timeoutSeconds) {
        if (pendingResendRequest == null || timeoutSeconds <= 0) {
            return StalledResendAction.NOTHING;
        }
        if (++resendRequestStalledSeconds < timeoutSeconds) {
            return StalledResendAction.NOTHING;
        }
        resendRequestStalledSeconds = 0;
        if (resendRequestRetried) {
            return StalledResendAction.GIVE_UP;
        }
        resendRequestRetried = true;
        return StalledResendAction.ASK_AGAIN;
    }

    private void onResendRequestFinished(long newSeqNum) {
        ResendRequest finished = pendingResendRequest;
        fixSession.logEvent("ResendRequest from %s to %s fully processed, NewSeqNum is %s",
                finished.getFromSeqNum(), finished.getToSeqNum(), newSeqNum);
        // cleared before notifying anyone: the replay below runs the full decoding path and must see a session with
        // no resend outstanding, otherwise the messages it feeds back are taken for retransmissions
        pendingResendRequest = null;
        // this one recovered, so the next gap gets its own retry: the flag is deliberately left alone by
        // onResendRequestSent, which the retry itself goes through, and cleared here and on the way out instead
        resendRequestRetried = false;
        resendRequestStalledSeconds = 0;
        fixSession.getApplication().onResendRequestTerminated(fixSession, finished.getFromSeqNum(), finished.getToSeqNum());
        // synchronized again: whatever the application handed over meanwhile can go out, numbered after the
        // retransmission rather than into the middle of it
        heldOutgoingMessages.releaseAll();
        // the gap is closed, so the messages that arrived on top of it - the one that revealed the gap first of all -
        // can now be processed in order. Only requested here: this runs inside the decoding of the message that
        // completed the resend, and replaying feeds whole messages back through a parser
        requestOutOfSequenceMessagesReplay();
    }

    /**
     * The session has logged out: the answer that may still have been arriving belonged to the connection that just
     * ended, and so do the messages held waiting for a gap to be filled. The next session starts from whatever the
     * stores say, and replaying these later would inject messages out of nowhere.
     */
    void onSessionEnded() {
        pendingResendRequest = null;
        retransmissionRequested = false;
        resendRequestRetried = false;
        resendRequestStalledSeconds = 0;
        clearOutOfSequenceMessages();
    }

    /**
     * A connection that ended without a clean logout leaves its queue behind: those messages belong to the connection
     * that dropped them and must not be replayed into this one.
     */
    void onNewConnection() {
        clearOutOfSequenceMessages();
    }

    private void clearOutOfSequenceMessages() {
        outOfSequenceMessages.clear();
        outOfSequenceMessagesReplayRequested = false;
    }

    /**
     * What {@link #onResendRequestStallCheck(int)} decided about a retransmission that has stopped progressing.
     */
    public enum StalledResendAction {
        /**
         * No request outstanding, or its answer is still coming in.
         */
        NOTHING,
        /**
         * First expiry: ask once more for whatever is left of the range, the answer having gone missing rather than
         * the peer having refused it.
         */
        ASK_AGAIN,
        /**
         * Asked twice and still nothing: the session cannot be recovered and is taken down rather than left stalled.
         */
        GIVE_UP
    }

    @Value
    public static class ResendRequest {
        long fromSeqNum;
        long toSeqNum;
    }
}
