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
package org.lolaf.staffix.api.application;

import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.staffix.api.FixInitiatorBuilder;
import org.lolaf.staffix.api.codec.FixFieldsEncoder;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.CancelOnDisconnectType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * A FIX application, this is up to the implementation choice to provide one instance for all session or one instance per session
 * see {@link FixApplicationFactory#getInstance(String)} for more details
 */
public interface FixApplication {

    /**
     * Indicates that the FIX application instance is being destroyed, usually when the application is shutting down
     * All sessions bound to this application at this stage are terminated and their {@link #onSessionPreDestroy(FixSession)}
     * and {@link #onSessionDestroyed(FixSession)} methods called
     */
    default void destroy() {
    }

    /**
     * Cancel on disconnect triggered for the given FIX session
     *
     * @param fixSession             the fix session that triggered the cancel on disconnect event
     * @param cancelOnDisconnectType the type of triggered cancel on disconnect
     */
    default void onCancelOnDisconnectTriggered(FixSession fixSession, CancelOnDisconnectType cancelOnDisconnectType) {
    }

    /**
     * A list of required fix application implementation settings that are necessary to make the FIX application work for the sessions bound to it.
     * See {@link FixSessionSettings#getFixApplicationSessionSettings()} to set the required settings in the fix session settings
     *
     * @return a collection of FixApplicationSessionSetting or an empty List if none a required
     */
    default Collection<FixApplicationSessionSettingDescriptor> getRequiredFixSessionSettings() {
        return Collections.emptyList();
    }

    /**
     * The fix api version implemented by this application
     *
     * @return the FIX api version implemented by this application
     */
    FixApiVersion getFixApiVersion();

    /**
     * Set up the list of messages decoders instances for the given FIX session, warning decoders instance cannot be shared within multiple session as they are stateful
     * This method is called only once before {@link #onSessionCreated(FixSession, FieldsRegistry, MessageTypeRegistry, List)}
     * Warning at this stage session plugins are not initialized and calls to {@link FixSession#getPluginContext(Class)} will not work
     *
     * @param fixSessionSettings   the FIX session settings
     * @param fixSession           the FIX setting to assign the decoders
     * @param encodedMessagesTypes a set to add expected FIX message types to be encoded by the application, important for proper message monitoring if needed
     * @return a list of message decoders to process FIX incoming messages
     */
    List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes);

    /**
     * Called when a session is created, typically when the initiator or acceptor start or when an administrative call adds a session on the fly
     * Called only once during the application lifecycle
     * At this stage session plugins are fully initialized and ready to get retrieved trough {@link FixSession#getPluginContext(Class)}
     *
     * @param fixSession          the FIX session that has been created
     * @param fieldsRegistry      the registry of FIX fields available for this session
     * @param messageTypeRegistry the registry of FIX message types available for this session
     * @param decoders            the list of message decoders previously created by {@link #setup(FixSessionSettings, FixSession, Set)}
     */
    default void onSessionCreated(FixSession fixSession, FieldsRegistry fieldsRegistry, MessageTypeRegistry messageTypeRegistry, List<FixMessageDecoder> decoders) {
    }

    /**
     * Called when application is about to be terminated (app shutdown or session removed)
     * this call will be done prior of real session destruction, at this stage hte application may still be logged in
     * and able to send messages
     *
     * @param fixSession the FIX session being destroyed
     */
    default void onSessionPreDestroy(FixSession fixSession) {
    }

    /**
     * Called when application is shutting down or a session has been removed by an administrative call
     * this call will be done after onSessionPreDestroy and automatic logout, at this stage messages cannot be sent anymore
     *
     * @param fixSession the FIX session destroyed
     */
    default void onSessionDestroyed(FixSession fixSession) {
    }

    /**
     * Called when the session enters its configured session time window and is allowed to be active
     *
     * @param fixSession the fix session entering its session time
     */
    default void onInsideSessionTime(FixSession fixSession) {
    }

    /**
     * Called when the session leaves its configured session time window and should no longer be active
     *
     * @param fixSession the fix session leaving its session time
     */
    default void onOutsideSessionTime(FixSession fixSession) {
    }

    /**
     * Called shortly before the session is about to leave its configured session time window
     *
     * @param fixSession              the fix session about to leave its session time
     * @param outsideSessionTimeDelay the remaining delay before the session goes outside of its session time
     */
    default void onPreOutsideSessionTime(FixSession fixSession, Duration outsideSessionTimeDelay) {
    }

    /**
     * Received a message which has no decoders setup
     *
     * @param fixSession  the fix session that received the message
     * @param messageType the type of the message that has no decoder setup
     * @return true if a BusinessMessageReject should be returned back to the remote session.
     */
    default boolean onNoDecoderSetupForMessage(FixSession fixSession, MessageType messageType) {
        LoggerFactory.getLogger(this.getClass()).warn("Received an unmapped FIX message in session {}: {}", fixSession.getFixSessionId(), messageType);
        return false;
    }

    /**
     * Called when a message could not be sent to the remote session
     *
     * @param fixSession   the fix session that failed to send the message
     * @param messageType  the type of the message that failed to be sent
     * @param message      the raw encoded message that failed to be sent
     * @param sendingError the error that occurred while sending the message
     */
    default void onMessageSendingFailure(FixSession fixSession, MessageType messageType, ByteBuffer message, Exception sendingError) {
    }

    /**
     * Called for each message about to being resend during a resend request, allows to filter which messages should be sent back or not
     *
     * @param fixSession  the FixSession that is resending messages
     * @param messageType the message type
     * @param toResend    the message to resend
     * @return true if the message should be sent back or false if it should be discarded
     */
    default boolean onResendRequest(FixSession fixSession, MessageType messageType, DecodedFixMessage toResend) {
        return true;
    }

    /**
     * Called when a sequence reset is being sent to the remote session
     *
     * @param fixSession the FixSession that is resending messages
     * @param newSeqNum  the new outgoing sequence number
     * @param gapFill    sequence reset initiated ofr a gap fill
     */
    default void onSequenceReset(FixSession fixSession, long newSeqNum, boolean gapFill) {
    }

    /**
     * Called when a fix session is initiating a resend request due to an out-of-order sequence state during logon.
     * Application will start to receive message shortly after that with possible duplicate flag set
     * This method is called before {@link #onLogon(FixSession, DecodedFixMessage)} during the sequence synchronization and messages resending process
     *
     * @param fixSession the FixSession that has initiated a resend request
     * @param fromSeqNum from sequence number
     * @param toSeqNum   to sequence number
     */
    default void onResendRequestInitiated(FixSession fixSession, long fromSeqNum, long toSeqNum) {
    }

    /**
     * Called when a fix session as fully received all messages from its previous resend request
     * This method is called after {@link #onResendRequestInitiated(FixSession, long, long)}
     *
     * @param fixSession the FixSession that has completed the resend request
     * @param fromSeqNum from sequence number
     * @param toSeqNum   to sequence number
     */
    default void onResendRequestTerminated(FixSession fixSession, long fromSeqNum, long toSeqNum) {
    }


    /**
     * Called while an administrative (session level) message is being encoded, allowing extra fields to be appended to the
     * header, body or trailer of the message
     *
     * @param fixSession      the fix session encoding the message
     * @param messageType     the type of the message being encoded
     * @param headersAppender supplies an encoder to append additional fields to the message header
     * @param bodyAppender    supplies an encoder to append additional fields to the message body
     * @param trailerAppender supplies an encoder to append additional fields to the message trailer
     */
    default void onAdminMessageEncoding(FixSession fixSession, MessageType messageType, Supplier<FixFieldsEncoder> headersAppender, Supplier<FixFieldsEncoder> bodyAppender, Supplier<FixFieldsEncoder> trailerAppender) {
    }

    /**
     * Called while an application (business level) message is being encoded, allowing extra fields to be appended to the
     * header or trailer of the message
     *
     * @param fixSession      the fix session encoding the message
     * @param messageType     the type of the message being encoded
     * @param headersAppender supplies an encoder to append additional fields to the message header
     * @param trailerAppender supplies an encoder to append additional fields to the message trailer
     */
    default void onMessageEncoding(FixSession fixSession, MessageType messageType, Supplier<FixFieldsEncoder> headersAppender, Supplier<FixFieldsEncoder> trailerAppender) {
    }

    /**
     * Validates a logon message, can be used to check credentials.
     * This method call does not mean that the session is ready to receive and send application message,
     * use {@link #onLogon(FixSession, DecodedFixMessage)} for such purpose.
     * WARNING: this task is running in the IO thread, any blocking IO calls or long CPU consuming calls must be avoided at all cost
     * or troughput/latency of other FIX sessions bound to this IO thread could be impacted, use the provided Executor in
     * such case to process the CompletableFuture
     *
     * @param fixSession   fixSession
     * @param logonMessage the logon message
     * @param executor     the executor to use to run the CompletableFuture if needed
     * @return a {@code CompletableFuture<Optional<String>>}; if the logon should be rejected, the
     * {@code Optional<String>} will be used as the rejection message, or {@code null} if
     * the logon must not be validated.
     */
    default CompletableFuture<Optional<String>> validateLogon(FixSession fixSession, DecodedFixMessage logonMessage, Executor executor) {
        return null;
    }

    /**
     * Event called when a logon message has been received and the session is ready to be used (eventual sequence reset events fully processed)
     *
     * @param fixSession   the fix session that is now logged on
     * @param logonMessage the received logon message
     */
    default void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
    }

    /**
     * Indicates that the session is about the be logged out, you can still send a last messages in the method before the real logout message to be initiated
     *
     * @param fixSession the fix session
     * @param message    the logout message
     */
    default void onLogoutInitiated(FixSession fixSession, String message) {
    }

    /**
     * Indicates that the session is logged out, no message can be sent anymore at this stage
     *
     * @param fixSession    the fix session
     * @param message       the logout message
     * @param logoutMessage the received logout message or null if we had a hard TCP disconnect without any logout request
     */
    default void onLogout(FixSession fixSession, String message, DecodedFixMessage logoutMessage) {
    }

    /**
     * Called when the underlying transport of the session has been disconnected
     *
     * @param fixSession the fix session that has been disconnected
     */
    default void onDisconnected(FixSession fixSession) {
    }

    /**
     * Called when a heartbeat message has been received from the remote session
     *
     * @param fixSession      the fix session that received the heartbeat
     * @param remoteTimestamp the sending time of the heartbeat as reported by the remote session
     */
    default void onHeartbeat(FixSession fixSession, UTCTime remoteTimestamp) {
    }

    /**
     * Called when a test request has been received
     *
     * @param fixSession      the fix session that received the test request
     * @param testReqID       the test request identifier sent by the remote session
     * @param remoteTimestamp the sending time of the test request as reported by the remote session
     */
    default void onTestRequest(FixSession fixSession, String testReqID, UTCTime remoteTimestamp) {
    }

    /**
     * Called when a test request response has been received
     *
     * @param fixSession      the fix session that received the test request response
     * @param testReqID       the test request identifier echoed back by the remote session
     * @param remoteTimestamp the sending time of the test request response as reported by the remote session
     */
    default void onTestRequestResponse(FixSession fixSession, String testReqID, UTCTime remoteTimestamp) {
    }

    /**
     * Called when a session level reject has been triggered on the remote fix session
     *
     * @param fixSession   the fix session that received the reject
     * @param rejectText   the human readable text describing the reject
     * @param rejectReason the session level reject reason code
     * @param refSeqNum    the sequence number of the rejected message
     * @param refTagId     the tag id that caused the reject, or 0 if not applicable
     * @param refMsgType   the message type of the rejected message
     */
    default void onMessageReject(FixSession fixSession, String rejectText, int rejectReason, long refSeqNum, int refTagId, String refMsgType) {
        LoggerFactory.getLogger(this.getClass()).warn("Received a session level message reject on session {}: {}, reason: {}, refSeqNum: {}, refTagId: {}, refMsgType: {}",
                fixSession.getFixSessionId(), rejectText, rejectReason, refSeqNum, refTagId, refMsgType);
    }

    /**
     * Called when a business level reject has been triggered by the remote fix application
     *
     * @param fixSession          the fix session that received the business reject
     * @param rejectText          the human readable text describing the reject
     * @param rejectReason        the business level reject reason code
     * @param refSeqNum           the sequence number of the rejected message
     * @param businessRejectRefId the business identifier of the rejected message, or null if not applicable
     * @param refMsgType          the message type of the rejected message
     */
    default void onBusinessMessageReject(FixSession fixSession, String rejectText, int rejectReason, long refSeqNum, String businessRejectRefId, String refMsgType) {
        LoggerFactory.getLogger(this.getClass()).warn("Received a business level message reject on session {}: {}, reason: {}, refSeqNum: {}, businessRejectRefId: {}, refMsgType: {}",
                fixSession.getFixSessionId(), rejectText, rejectReason, refSeqNum, businessRejectRefId, refMsgType);
    }

    /**
     * Callback for network watermark events when thresholds defined by {@link FixInitiatorBuilder#getIoSettings()}
     * {@link IOSettings#getWriteHighWatermark()} or {@link IOSettings#getWriteLowWatermark()} are reached.
     * This typically indicates that remote FIX session has troubles consuming message at the current sending rate
     *
     * @param fixSession           the fix session having the watermark event
     * @param highWatermarkReached true when bytes left to write io IOSession are above thresholds defined
     *                             by {@link IOSettings#getWriteHighWatermark()} or false when under the {@link IOSettings#getWriteLowWatermark()} thresholds
     * @param bytesLeftToWrite     the number of bytes left to write to remote socket when event is triggered
     */
    default void onNetworkWatermarkEvent(FixSession fixSession, boolean highWatermarkReached, long bytesLeftToWrite) {
    }


}