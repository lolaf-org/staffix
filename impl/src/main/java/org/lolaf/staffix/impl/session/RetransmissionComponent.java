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
import lombok.extern.slf4j.Slf4j;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.codec.decoders.FixMessageParser;
import org.lolaf.staffix.impl.session.codec.FixAdminMessagesCodec;
import org.lolaf.staffix.impl.threading.SchedulerThread;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Section 4.3.11: what this session is missing, what it has asked the peer for, and what it does when the answer
 * does not come.
 *
 * <p>The replay of a retransmission runs on a thread of its own, bound to the connection that asked for it: reading
 * a range back out of the store and encoding it would otherwise hold the IO thread for the whole range, and the
 * answer must never reach the connection that replaced the one it belongs to.
 */
@Slf4j
public class RetransmissionComponent implements FixSessionLayerComponent {

    private final FixSessionImpl fixSession;
    private final FixSessionSettings fixSessionSettings;
    private final FixAdminMessagesCodec fixAdminMessagesCodec;
    private final FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore;
    private final FixApplication fixApplication;
    private final String fixInstanceId;
    private final FixSessionId fixSessionId;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final MessageTypeRegistry messageTypeRegistry;
    private final FieldsRegistry fieldsRegistry;
    private final FixMessagesLogger.Logger fixMessagesLogger;
    /**
     * The gap recovery, which is a session state of its own and large enough to be kept as one: what the peer still
     * owes this session, and what arrived on top of it meanwhile.
     */
    @Getter
    private final ResendRecovery resendRecovery;
    private CodecsComponent codecs;
    private IncomingMessagesComponent incomingMessages;
    private FixMessageParser outOfSequenceMessagesParser;
    private ExecutorService resendExecutor;
    private ScheduledFuture<?> stallCheckTask;

    RetransmissionComponent(FixSessionImpl fixSession, FixSessionSettings fixSessionSettings,
                            FixAdminMessagesCodec fixAdminMessagesCodec,
                            FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore,
                            FixApplication fixApplication, String fixInstanceId, FixSessionId fixSessionId,
                            ScheduledExecutorService scheduler, Clock clock, MessageTypeRegistry messageTypeRegistry,
                            FieldsRegistry fieldsRegistry, FixMessagesLogger.Logger fixMessagesLogger,
                            HeldOutgoingMessagesComponent heldOutgoingMessages, LogonLogoutComponent logonLogout) {
        this.fixSession = fixSession;
        this.fixSessionSettings = fixSessionSettings;
        this.fixAdminMessagesCodec = fixAdminMessagesCodec;
        this.fixSessionMessagesStore = fixSessionMessagesStore;
        this.fixApplication = fixApplication;
        this.fixInstanceId = fixInstanceId;
        this.fixSessionId = fixSessionId;
        this.scheduler = scheduler;
        this.clock = clock;
        this.messageTypeRegistry = messageTypeRegistry;
        this.fieldsRegistry = fieldsRegistry;
        this.fixMessagesLogger = fixMessagesLogger;
        this.resendRecovery = new ResendRecovery(fixSession, this, heldOutgoingMessages, logonLogout, fixSessionMessagesStore);
    }

    /**
     * Asks the peer to retransmit {@code [fromSeqNum, toSeqNum]} and records the request as the one now outstanding.
     *
     * @param reason what opened the gap, for the session event log
     */
    public void requestRetransmission(long fromSeqNum, long toSeqNum, String reason) {
        fixSession.logEvent("%s, sending ResendRequest from %s to %s", reason, fromSeqNum, toSeqNum);
        recordRetransmission(fromSeqNum, toSeqNum);
        fixSession.send(fixAdminMessagesCodec.generateResendRequest(fromSeqNum, toSeqNum), null);
    }

    /**
     * Records {@code [fromSeqNum, toSeqNum]} as outstanding without asking for it, for the one case where the peer
     * retransmits of its own accord: section 4.4.1 has it driven by the NextExpectedMsgSeqNum(789) our own Logon(35=A)
     * carries, and "peers should not generate a ResendRequest(35=2) message based on MsgSeqNum(34) of the incoming
     * Logon(35=A) message but should expect any gaps to be filled automatically".
     *
     * @param reason what opened the gap, for the session event log
     */
    public void awaitPeerRetransmission(long fromSeqNum, long toSeqNum, String reason) {
        fixSession.logEvent("%s, awaiting the automatic retransmission of %s to %s", reason, fromSeqNum, toSeqNum);
        recordRetransmission(fromSeqNum, toSeqNum);
    }

    /**
     * The single way a retransmission of the messages this session is missing becomes the one outstanding, whether it
     * was asked for or is expected to arrive on its own. Everything a pending request carries hangs off here: the
     * hold on outgoing application messages, the application callbacks, the stall timeout - and above all the record
     * itself, since the queue of messages held on top of a gap is only ever drained by a request completing, so a gap
     * recovered without one recorded would be recovered on the wire and never delivered.
     */
    private void recordRetransmission(long fromSeqNum, long toSeqNum) {
        resendRecovery.onResendRequestSent(fromSeqNum, toSeqNum);
        startStallCheckIfNeeded();
        fixApplication.onResendRequestInitiated(fixSession, fromSeqNum, toSeqNum);
    }

    /**
     * The clock the stall check counts on, started by the first request this session has to make and stopped with
     * the connection. A session that never loses a message never schedules it.
     *
     * <p>Once a second, because {@link ResendRecovery#onResendRequestStallCheck} counts ticks rather than reading a
     * clock: the timeout it is given is in seconds.
     */
    private void startStallCheckIfNeeded() {
        if (stallCheckTask == null && !fixSessionSettings.getResendRequestResponseTimeout().isZero()) {
            stallCheckTask = scheduler.scheduleAtFixedRate(this::manageStalledRetransmission, 1000L, 1000L, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelStallCheck() {
        if (stallCheckTask != null) {
            stallCheckTask.cancel(false);
            stallCheckTask = null;
        }
    }

    /**
     * Asks again for what is left of a request the peer has not answered, or gives up on a session that cannot be
     * recovered.
     */
    @SchedulerThread
    private void manageStalledRetransmission() {
        Duration timeout = fixSessionSettings.getResendRequestResponseTimeout();
        if (timeout.isZero()) {
            return;
        }
        // the tick this runs on is a second long, so anything shorter than that is one tick rather than none
        int timeoutSeconds = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeout.getSeconds()));
        ResendRecovery.StalledResendAction action = resendRecovery.onResendRequestStallCheck(timeoutSeconds);
        if (action.equals(ResendRecovery.StalledResendAction.NOTHING)) {
            return;
        }
        ResendRecovery.ResendRequest pending = resendRecovery.getPendingResendRequest();
        if (pending == null) {
            // the answer landed on the IO thread while this was deciding the session had given up on it
            return;
        }
        if (action.equals(ResendRecovery.StalledResendAction.GIVE_UP)) {
            fixSession.logout(String.format("No answer to the ResendRequest from %s to %s, the session cannot be recovered",
                    pending.getFromSeqNum(), pending.getToSeqNum()));
            return;
        }
        // whatever is left of it: the part already answered is behind NextNumIn and asking for it again would have
        // the peer retransmit messages this session has processed
        long fromSeqNum = fixSessionMessagesStore.getIncomingSeqNum();
        if (fromSeqNum > pending.getToSeqNum()) {
            // the same race as above, caught one step later: the range completed on the IO thread while this was
            // reading it, so there is nothing left to ask for
            return;
        }
        requestRetransmission(fromSeqNum, pending.getToSeqNum(), String.format("No answer to the ResendRequest from %s to %s for %ss",
                pending.getFromSeqNum(), pending.getToSeqNum(), timeoutSeconds));
    }

    /**
     * Runs a retransmission off the IO thread, bound to the connection current when it was asked for. Called on the IO
     * thread, where that connection can be read.
     */
    void executeResend(Consumer<IOSession> resend) {
        IOSession connection = fixSession.currentConnection();
        if (connection == null) {
            return;
        }
        if (resendExecutor == null) {
            resendExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fix-resend-" + fixInstanceId + "-" + fixSessionId.getId());
                thread.setDaemon(true);
                return thread;
            });
        }
        resendExecutor.execute(() -> resend.accept(connection));
    }

    @Override
    public void onSessionStarted(FixSessionLayerComponents components) {
        codecs = components.get(CodecsComponent.class);
        incomingMessages = components.get(IncomingMessagesComponent.class);
    }

    /**
     * Feeds one message held by {@link ResendRecovery#queueOutOfSequenceMessage} back through the decoding path,
     * exactly as if it had just been read off the socket. The recovery owns the queue and decides the order; this
     * only puts the bytes back through a parser of the codecs'.
     *
     * @return whether the message could be replayed, the session having been disconnected when it could not
     */
    boolean replayOutOfSequenceMessage(long msgSeqNum, byte[] rawMessage) {
        try {
            outOfSequenceMessagesParser().parseMessages(ByteBuffer.wrap(rawMessage), codecs::getTargetDecoder,
                    fixSessionMessagesStore::getIncomingSeqNum, clock.nanoTime());
            return true;
        } catch (Exception ex) {
            log.warn("Failed to replay out of order message with MsgSeqNum {} on session {}", msgSeqNum, fixSessionId, ex);
            fixSession.logEvent("Failed to replay out of order message with MsgSeqNum %s: %s", msgSeqNum, ex.getMessage());
            fixSession.disconnect();
            return false;
        }
    }

    /**
     * A parser of its own for replaying a message held on top of a gap, so that feeding one back cannot disturb the
     * state the session's own parser keeps for the network read in progress - a message split across two TCP reads
     * lives in there. Built on first use: a session that never loses a message never allocates it.
     */
    private FixMessageParser outOfSequenceMessagesParser() {
        if (outOfSequenceMessagesParser == null) {
            outOfSequenceMessagesParser = new FixMessageParser(fixSessionId.invert(), messageTypeRegistry, fieldsRegistry,
                    fixMessagesLogger, fixSessionSettings.getValidationSettings(), clock, incomingMessages);
        }
        return outOfSequenceMessagesParser;
    }

    @Override
    public void onConnected() {
        resendRecovery.onNewConnection();
    }

    @Override
    public void onConnectionClosed() {
        cancelStallCheck();
    }

    @Override
    public void onLogoutProcessed(boolean cleanLogout) {
        resendRecovery.onSessionEnded();
    }

    /**
     * Before anything else touches the connection: a retransmission still running would otherwise carry on writing
     * into a session being torn down, and the logout the stop sends is what the peer should see next.
     */
    @Override
    public void onSessionStopping(Deadline deadline) {
        cancelStallCheck();
        if (resendExecutor == null) {
            return;
        }
        ExecutorService stopping = resendExecutor;
        resendExecutor = null;
        stopping.shutdownNow();
        try {
            // Deadline floors at zero and awaitTermination(0) does not wait at all, so an expired deadline is still
            // given a millisecond: the point is to leave the thread nowhere to be but finished
            if (!stopping.awaitTermination(Math.max(1L, deadline.fromRemainingTime(0.2).getRemainingTime().toMillis()), TimeUnit.MILLISECONDS)) {
                log.warn("Timed out waiting for the retransmission thread of FIX session {} to stop", fixSessionId);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
