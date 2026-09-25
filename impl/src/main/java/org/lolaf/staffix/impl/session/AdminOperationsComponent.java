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

import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.codec.decoders.AdminFixMessageTransformer;
import org.lolaf.staffix.codec.encoders.GenericFixMessageEncoder;
import org.lolaf.staffix.codec.serde.ByteArraySerde;
import org.lolaf.staffix.impl.session.codec.FixAdminMessagesCodec;
import org.lolaf.staffix.impl.threading.ExternalThread;

import java.util.concurrent.TimeUnit;

/**
 * What an operator can do to a live session from a console or a script: read and move its sequence numbers, reset
 * them in one of three ways, and put a hand written message on the wire.
 *
 * <p>Every one of these is destructive in a way the protocol itself never is, which is why they are gathered here
 * rather than spread through the session: renumbering behind the peer's back, or sending a message no decoder ever
 * produced, is something a person asked for.
 */
@RequiredArgsConstructor
class AdminOperationsComponent implements FixSessionLayerComponent {

    private final FixSessionImpl fixSession;
    private final FixSessionStateComponent fixSessionStateComponent;
    private final FixSessionSettings fixSessionSettings;
    private final FixAdminMessagesCodec fixAdminMessagesCodec;
    private final FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore;
    private final MessageTypeRegistry messageTypeRegistry;
    private final FieldsRegistry fieldsRegistry;
    private final Clock clock;
    private final TimeUnit sendingTimeAccuracy;
    private final FixSessionId fixSessionId;
    private final LogonLogoutComponent logonLogout;
    /**
     * Built on first administrative send and kept: it holds a stateful parser, and such sends are rare enough that
     * one instance for the session is all they need.
     */
    private AdminFixMessageTransformer adminFixMessageTransformer;

    /**
     * Sends the message a caller wrote as a string - the administrative send, from an operator's console or a script.
     * <p>
     * Only the body of what was handed in goes out: {@link AdminFixMessageTransformer} throws the frame of the pasted
     * message away and refuses anything this session's dictionary does not describe, and the session's own encoder then
     * puts a header and a trailer of its own around the fields, with the sequence number, sending time, body length and
     * checksum this session owes its peer. Which is the point of the exercise - a message copied out of a log cannot be
     * put back on the wire as it was.
     * <p>
     * Validation is this call's; delivery is the session's. Anything wrong with the message is thrown back here, and
     * the send itself is handed to the session thread like every other.
     *
     * @param fixMessage  the message
     * @param separator   the character between its fields
     * @param possDupFlag whether the message is the same one going out again, its SendingTime(52) then becoming
     *                    OrigSendingTime(122) under a PossDupFlag(43)=Y
     * @throws IllegalArgumentException if the string is not a message this session could send
     * @throws IllegalStateException    if the session is not logged in
     */
    @ExternalThread
    void sendFixMessage(String fixMessage, char separator, boolean possDupFlag) {
        if (!fixSessionStateComponent.isLoggedIn()) {
            throw new IllegalStateException("Cannot send a FIX message on FIX session " + fixSessionId
                    + ": it is not logged in");
        }
        DecodedFixMessage bodyFields = adminFixMessageTransformer().transform(fixMessage, separator, possDupFlag);
        GenericFixMessageEncoder encoder = new GenericFixMessageEncoder(bodyFields.getMessageType()).begin();
        bodyFields.foreach((field, value) -> encoder.addField(field, value, ByteArraySerde.instance()));
        fixSession.logEvent("Admin API send %s", bodyFields.getMessageType().code());
        fixSession.send(encoder, null);
    }

    @ExternalThread
    long getIncomingSeqNum() {
        return fixSessionMessagesStore.getIncomingSeqNum();
    }

    @ExternalThread
    void setIncomingSeqNum(long seqNum) {
        failIfStoreStopped("set incoming sequence number");
        fixSession.logEvent("Admin API set incoming sequence: %s", seqNum);
        fixSessionMessagesStore.storeNextIncomingSeqNum(seqNum);
    }

    @ExternalThread
    long getOutgoingSeqNum() {
        return fixSessionMessagesStore.getOutgoingSeqNum();
    }

    @ExternalThread
    void setOutgoingSeqNum(long seqNum) {
        failIfStoreStopped("set outgoing sequence number");
        fixSession.logEvent("Admin API set outgoing sequence: %s", seqNum);
        fixSessionMessagesStore.storeNextOutgoingSeqNum(seqNum);
    }

    @ExternalThread
    void resetSequence(AdminApi.ResetFixSessionMode resetFixSessionMode) {
        fixSession.runOnSessionOwnerThread(() -> {
            fixSession.logEvent("Admin API reset sequence: %s", resetFixSessionMode);
            applyResetSequence(resetFixSessionMode);
        }, fixSession.currentIOSession());
    }

    /**
     * Sends a SequenceReset(35=4) with GapFillFlag(123)=N - a hard reset - announcing {@code newSeqNum} as the
     * MsgSeqNum(34) this session will send next, and renumbers this side to match once the message is on the wire.
     * <p>
     * Section 4.8.6: the peer applies it without regard to the reset's own MsgSeqNum(34), and answers one that would
     * lower its expected sequence number with a Reject(35=3). Nothing is checked here - the peer's answer is the
     * check - so a caller that announces a number the peer has already passed simply gets refused.
     * <p>
     * This is the live session counterpart of {@link #setOutgoingSeqNum}, which renumbers silently and is only
     * meaningful when both ends have agreed to it while the session is down. Nothing else in staffix sends this
     * message, and it is deliberately absent from the admin API: renumbering a live session is destructive. It exists
     * as a method rather than as a recipe because of the ordering - the reset consumes a MsgSeqNum of its own, so
     * this side may only be renumbered once the message has actually gone out, which is why the store is written from
     * the send callback rather than straight after the call.
     */
    void hardSequenceReset(long newSeqNum) {
        fixSession.logEvent("Hard SequenceReset announcing NewSeqNum %s", newSeqNum);
        fixSession.send(fixAdminMessagesCodec.generateSequenceReset(newSeqNum, false), null,
                (sendingError, unused1, unused2) -> {
                    if (sendingError == null) {
                        fixSessionMessagesStore.storeNextOutgoingSeqNum(newSeqNum);
                    }
                }, null, null);
    }

    /**
     * Every mode of {@link #resetSequence} runs here, on the session's own thread.
     * <p>
     * Two reasons, and the second was a bug. Moving the sequence numbers from whichever thread called the admin API
     * races the session thread that is reading and writing them as it sends and receives. And where a mode also puts
     * a message on the wire, the reset has to land on the right side of it:
     * {@link AdminApi.ResetFixSessionMode#LOGOUT_LOGON_REST_NUM_FLAG} used to queue its Logout to this thread through
     * {@code logout} while resetting on the caller's, so the numbering could go back to 1 before the Logout was
     * sent - and a Logout arriving as MsgSeqNum(34)=1 is answered by the peer with a "MsgSeqNum too low" Logout of
     * its own, which is the opposite of a clean cycle.
     */
    private void applyResetSequence(AdminApi.ResetFixSessionMode resetFixSessionMode) {
        switch (resetFixSessionMode) {
            case RESET_SEQUENCE:
                fixSession.resetSequence("Admin API reset sequence");
                break;
            case LOGOUT_LOGON_REST_NUM_FLAG:
                cycleSessionWithSequenceReset();
                break;
            case RESET_SEQUENCE_IN_SESSION:
                fixSession.sendInSessionSequenceReset();
                break;
        }
    }

    private void cycleSessionWithSequenceReset() {
        if (!fixSessionStateComponent.canSendLogoutRequest()) {
            fixSession.logEvent("Ignoring admin reset sequence through logout and logon: the session is not logged in");
            return;
        }
        fixSessionStateComponent.runOnceLoggedOutConnectionClosed(
                fixSessionStateComponent::armSequenceResetOnNextLogon);
        logonLogout.sendLogoutRequest("Admin reset sequence", false);
    }

    private void failIfStoreStopped(String operation) {
        if (!fixSessionMessagesStore.isStarted()) {
            throw new IllegalStateException("Cannot " + operation + " on FIX session " + fixSessionId
                    + ": its resources have been released");
        }
    }

    private synchronized AdminFixMessageTransformer adminFixMessageTransformer() {
        if (adminFixMessageTransformer == null) {
            adminFixMessageTransformer = new AdminFixMessageTransformer(fixSessionId, messageTypeRegistry, fieldsRegistry,
                    fixSessionSettings.getValidationSettings(), clock, sendingTimeAccuracy);
        }
        return adminFixMessageTransformer;
    }
}