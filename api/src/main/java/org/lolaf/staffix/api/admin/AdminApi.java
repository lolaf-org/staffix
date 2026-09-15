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
package org.lolaf.staffix.api.admin;

import org.lolaf.staffix.api.InstanceIdSupplier;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;

import java.util.List;

/**
 * Management control surface for a FIX engine's sessions. Implemented by the engine itself, which
 * routes each operation to the initiator/acceptor managing the target session.
 *
 * <p>{@link #getInstanceId()} returns the owning engine's instance id.
 */
public interface AdminApi extends InstanceIdSupplier {

    /**
     * Initiates a logon for the given session, establishing the FIX connection if it is not already up.
     *
     * @param fixSessionId the session to log on
     * @throws IllegalArgumentException if no initiator or acceptor manages the given session
     */
    void logonSession(FixSessionId fixSessionId);

    /**
     * Initiates a logout for the given session, gracefully tearing down the FIX connection.
     *
     * @param fixSessionId the session to log out
     * @throws IllegalArgumentException if no initiator or acceptor manages the given session
     */
    void logoutSession(FixSessionId fixSessionId);

    /**
     * Resets the given session according to the supplied mode.
     *
     * @param fixSessionId        the session to reset
     * @param resetFixSessionMode how the session should be reset (see {@link ResetFixSessionMode})
     * @throws IllegalArgumentException if no initiator or acceptor manages the given session
     */
    void resetSession(FixSessionId fixSessionId, ResetFixSessionMode resetFixSessionMode);

    /**
     * Overrides the next expected incoming (received) sequence number for the given session.
     *
     * @param fixSessionId   the session to update
     * @param incomingSeqNum the next expected incoming sequence number
     * @throws IllegalArgumentException if no initiator or acceptor manages the given session
     */
    void setIncomingSeqNum(FixSessionId fixSessionId, long incomingSeqNum);

    /**
     * Overrides the next outgoing (sent) sequence number for the given session.
     *
     * @param fixSessionId   the session to update
     * @param outgoingSeqNum the next outgoing sequence number
     * @throws IllegalArgumentException if no initiator or acceptor manages the given session
     */
    void setOutgoingSeqNum(FixSessionId fixSessionId, long outgoingSeqNum);

    /**
     * Returns the next expected incoming (received) sequence number for the given session.
     *
     * @param fixSessionId the session to query
     * @return the next expected incoming sequence number
     * @throws IllegalArgumentException if no initiator or acceptor manages the given session
     */
    long getIncomingSeqNum(FixSessionId fixSessionId);

    /**
     * Returns the next outgoing (sent) sequence number for the given session.
     *
     * @param fixSessionId the session to query
     * @return the next outgoing sequence number
     * @throws IllegalArgumentException if no initiator or acceptor manages the given session
     */
    long getOutgoingSeqNum(FixSessionId fixSessionId);

    /**
     * Sends the message on the given FIX session with default FIX field separator and possible duplicate flag set to false
     * see {@link #sendFixMessage(FixSessionId, String, char, boolean)}
     */
    default void sendFixMessage(FixSessionId fixSessionId, String fixMessage) {
        sendFixMessage(fixSessionId, fixMessage, CoreFields.FIELD_SEPARATOR, false);
    }

    /**
     * As {@link #sendFixMessage(FixSessionId, String)}, for a message whose fields are separated by something else than
     * SOH - {@code |} is what a message written by hand or copied out of a report usually carries - and for sending the
     * same message a second time.
     * <p>
     * The separator is stated rather than guessed: a field value is free to contain whichever character is not the
     * separator, so a message holding both would otherwise be read either way.
     * <p>
     * With {@code possDupFlag} set, the message goes out as the duplicate it is: its SendingTime(52) becomes
     * OrigSendingTime(122) and PossDupFlag(43)=Y is added, exactly as a retransmission carries a stored message, while
     * the SendingTime(52) that goes on the wire is the one this session stamps now. Section 4.4 requires
     * OrigSendingTime wherever PossDupFlag is Y, so a message carrying no SendingTime is refused rather than marked.
     *
     * @param fixSessionId the session to send on
     * @param fixMessage   the message to send
     * @param separator    the character between the message's fields
     * @param possDupFlag  whether the message is the same one going out again
     * @throws IllegalArgumentException if no initiator or acceptor manages the given session, or the message is not one
     *                                  that session could send
     * @throws IllegalStateException    if the session is not logged in
     */
    void sendFixMessage(FixSessionId fixSessionId, String fixMessage, char separator, boolean possDupFlag);

    /**
     * Returns the settings of every session currently managed by the engine's initiators and acceptors.
     *
     * @return the managed sessions' settings; empty if no session is managed
     */
    List<FixSessionSettings> getManagedFixSessionsSettings();

    /**
     * Returns the instance ids of the {@link org.lolaf.staffix.api.session.FixSessionsSettingsStore}s
     * configured in the engine.
     */
    List<String> getFixSessionsSettingsStoresInstanceIds();

    /**
     * Reloads the {@link org.lolaf.staffix.api.session.FixSessionsSettingsStore} with the given instance id by
     * calling {@link org.lolaf.staffix.api.session.FixSessionsSettingsStore#load()} and reconciling the result
     * against the currently managed settings, keyed by {@link org.lolaf.staffix.api.session.FixSessionId}: added,
     * removed and updated sessions are applied through the store's add/remove/update so listeners are notified.
     *
     * @param instanceId the instance id of the store to reload
     * @throws IllegalArgumentException if no store with the given instance id is configured
     */
    void reloadFixSessionsSettingsStore(String instanceId);

    /**
     * Returns a snapshot of every session currently managed by the engine's initiators and acceptors, each paired with
     * its initiator/acceptor role.
     *
     * <p>Sessions come and go at runtime (initiators/acceptors are created after the engine starts, and the
     * settings-store reload path adds/removes sessions), so callers that need to stay in sync should both take this
     * snapshot and register a {@link SessionLifecycleListener}.
     *
     * @return the managed sessions; empty if no session is managed
     */
    List<FixSession> getManagedFixSessions();

    /**
     * Registers a listener notified whenever a session is added to or removed from the set of managed sessions, so that
     * {@link AdminApiExporter exporters} (JMX, HTTP, ...) can publish/unpublish a per-session management endpoint as
     * sessions appear and disappear.
     *
     * @param listener the listener to register
     */
    void registerSessionLifecycleListener(SessionLifecycleListener listener);

    /**
     * Unregisters a previously {@link #registerSessionLifecycleListener(SessionLifecycleListener) registered} listener.
     *
     * @param listener the listener to remove
     */
    void unregisterSessionLifecycleListener(SessionLifecycleListener listener);

    /**
     * Strategy used by {@link #resetSession(FixSessionId, ResetFixSessionMode)} to reset a session.
     * <p>
     * The ways a session's sequence numbers can be put back to 1, which differ in two respects that matter
     * operationally: whether the peer is told, and whether the connection survives.
     *
     * <table border="1">
     *     <caption>Choosing between them</caption>
     *     <tr><th>Mode</th><th>Peer told?</th><th>Connection kept?</th><th>Usable from</th></tr>
     *     <tr><td>{@link #LOGOUT_LOGON_REST_NUM_FLAG}</td><td>yes</td><td>no</td><td>an initiator session</td></tr>
     *     <tr><td>{@link #RESET_SEQUENCE}</td><td>no</td><td>yes</td><td>either end</td></tr>
     *     <tr><td>{@link #RESET_SEQUENCE_IN_SESSION}</td><td>yes</td><td>yes</td><td>either end</td></tr>
     * </table>
     *
     * <p>Whichever is used, both ends must finish agreeing on the numbering or the session cannot carry on: one side
     * counting from 1 while the other counts from where it was is answered with a Logout for a MsgSeqNum that went
     * backwards. The modes that tell the peer arrange that agreement themselves; {@link #RESET_SEQUENCE} leaves it to
     * whoever is driving.
     */
    enum ResetFixSessionMode {
        /**
         * Logs the session out, resets both sequence numbers, and logs back on with ResetSeqNumFlag(141)=Y so that
         * the peer resets with it - section 4.4.3, a reset while the connection is being established.
         * <p>
         * The connection is dropped and rebuilt, so the session is unusable in between and any message handed over
         * meanwhile is refused. The reset itself rides on the Logon that re-establishes the session, which is to say
         * it takes effect once the transport has reconnected rather than when the call returns.
         * Only an initiator session can drive it, being the end that sends a Logon of its own;
         * {@link #RESET_SEQUENCE_IN_SESSION} is the equivalent that keeps the connection and works from either end.
         */
        LOGOUT_LOGON_REST_NUM_FLAG,
        /**
         * Puts this session's incoming and outgoing sequence numbers back to 1 and tells nobody: nothing goes on the
         * wire, and the peer carries on counting from where it was.
         * <p>
         * <b>This breaks the session on its own.</b> It is half of an operation whose other half is the same call on
         * the other end, and it is the caller's business to make both happen - typically while the connection is
         * down, or on a peer being restored from a backup whose numbering is known to be stale. Left one-sided, the
         * next message either end sends is answered with a Logout for a MsgSeqNum that went backwards. Prefer
         * {@link #RESET_SEQUENCE_IN_SESSION}, which reaches the same place with the peer's agreement.
         */
        RESET_SEQUENCE,
        /**
         * Resets the session over the connection it is already running on, section 4.4.2 - "the FIX session may be
         * reset to NextNumIn=1 and NextNumOut=1 over an active FIX session", provided for continuously operating
         * markets that need to restart the numbering periodically without ever dropping the connection.
         * <p>
         * The exchange, all of it while the session stays logged on:
         * <ol>
         *     <li>this side puts both its sequence numbers back to 1 and sends a Logon(35=A) carrying
         *     ResetSeqNumFlag(141)=Y and MsgSeqNum(34)=1;</li>
         *     <li>the peer sets NextNumIn to 2 and NextNumOut to 1, and answers with a Logon of its own, also
         *     141=Y and 34=1;</li>
         *     <li>both ends finish at NextNumIn = 2 and NextNumOut = 2.</li>
         * </ol>
         * <p>
         * When and by whom it is done is for the counterparties to agree between themselves: nothing about it is
         * negotiated on the wire, and either end may be the one to start it. A session configured
         * {@code resetSeqNumOnLogon(false)} - "I do not accept resets" - answers with a Logout(35=5) naming the
         * reason and drops the connection, which is what the specification asks of it.
         * <p>
         * The specification also allows the initiating side to send a TestRequest(35=1) first and wait for the
         * Heartbeat, making sure no gap is outstanding before the numbering restarts. That step is a "may" rather
         * than a must, so it is left to the caller and
         * {@link org.lolaf.staffix.api.session.FixSession#testRequest} is there for it.
         */
        RESET_SEQUENCE_IN_SESSION
    }

    /**
     * Callback contract notified as the engine's managed sessions appear and disappear. Implemented by
     * {@link AdminApiExporter}s that expose a per-session management endpoint.
     */
    interface SessionLifecycleListener {

        /**
         * Invoked when a session starts being managed by an initiator or acceptor.
         *
         * @param session the newly managed session
         */
        void onSessionRegistered(FixSession session);

        /**
         * Invoked when a session stops being managed by an initiator or acceptor.
         *
         * @param session the no-longer-managed session
         */
        void onSessionUnregistered(FixSession session);
    }

}