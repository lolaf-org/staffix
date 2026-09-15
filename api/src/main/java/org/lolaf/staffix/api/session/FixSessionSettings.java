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
package org.lolaf.staffix.api.session;

import lombok.*;
import lombok.experimental.SuperBuilder;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationFactory;
import org.lolaf.staffix.api.application.FixApplicationFactorySettings;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;

import java.net.InetAddress;
import java.security.cert.Certificate;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Everything about a session that is not its identity: heartbeat interval, which stores and loggers it uses,
 * what it validates, how it resets sequence numbers, what it does on a disconnect.
 *
 * <p>Most fields carry a default that is the specification's, or the safe reading of it where the specification
 * leaves a choice, so a minimal configuration is a valid one. The nested {@code ValidationSettings} is the
 * exception worth reading before changing: each flag there buys a check on the message path, and each is off by
 * default because that path is where the engine's latency is spent.
 */
@Getter
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode
public class FixSessionSettings {

    /**
     * The session's identity on the wire - FIX version and CompIDs. Required, and what an inbound Logon is matched
     * against.
     */
    @NonNull
    private FixSessionId fixSessionId;
    /**
     * Whether these settings describe an initiator or an acceptor session. Required: the same {@link FixSessionId} may
     * be configured for both roles, so this distinguishes them (e.g. in {@link FixSessionsSettingsStore#find}).
     */
    @NonNull
    private FixSession.FixSessionType fixSessionType;
    /**
     * Which dictionary's encoders this session uses, for a counterparty whose messages differ from the standard.
     * Defaults to the one every generated FIX package registers.
     */
    @Builder.Default
    private String dictionaryId = FixDictionaryId.DEFAULT_ID;
    /**
     * HeartBtInt(108): how often a quiet session proves it is alive, and how long it waits before asking.
     * <p>
     * An initiator proposes it at Logon and an acceptor may accept or impose its own, which is why this is a
     * settings object rather than a single duration.
     */
    @Builder.Default
    private HeartbeatInterval heartBeatInterval = HeartbeatInterval.builder().build();
    /**
     * The precision SendingTime(52) is written to. Microseconds by default; a counterparty that cannot parse
     * sub-second digits needs {@link TimeUnit#SECONDS}.
     */
    @Builder.Default
    private TimeUnit sendingTimeAccuracy = TimeUnit.MICROSECONDS;

    /**
     * See {@link FixApplication#getRequiredFixSessionSettings()}
     */
    @Singular
    private Map<FixApplicationSessionSettingDescriptor, String> fixApplicationSessionSettings;

    /**
     * Whether the Logon lists the message types this session sends and accepts, in MsgTypeGrp(384).
     * <p>
     * Off by default: it makes the Logon considerably larger and few counterparties read it.
     */
    @Builder.Default
    private boolean advertiseMsgTypeGrpOnLogon = false;
    /**
     * Whether the Logon names this engine and its version. Off by default - some venues record it, most ignore it.
     */
    @Builder.Default
    private boolean advertiseEngineOnLogon = false;
    /**
     * Whether the Logon names the application and its version, as distinct from the engine's.
     */
    @Builder.Default
    private boolean advertiseApplicationOnLogon = false;
    /**
     * Whether the Logon carries NextExpectedMsgSeqNum(789), telling the peer what we expect next so a gap is
     * resolved by the Logon exchange itself rather than by a ResendRequest afterwards (section 4.4.1).
     * <p>
     * On by default. A counterparty that does not support it will reject the field, in which case turn it off.
     */
    @Builder.Default
    private boolean enabledLogonNextExpectedMsgSeqNum = true;
    /**
     * Addresses an acceptor will accept this session from. Empty means anywhere.
     * <p>
     * Checked before the Logon is processed, so a connection from elsewhere is dropped without the session ever
     * starting.
     */
    @Singular
    private List<InetAddress> allowedAddresses;
    /**
     * Client certificates an acceptor will accept this session from, for mutual TLS. Empty means any certificate the
     * truststore already trusts.
     */
    @Singular
    private List<Certificate> allowedCertificates;
    /**
     * Flag to reset sequence number on logon, if not specified, the counterparty manages the sequence reset flag on
     * session establishment
     */
    @Builder.Default
    private Boolean resetSeqNumOnLogon = null;
    /**
     * The state the session should hold itself in. {@link FixSessionState#LOGGED_IN} by default, so it logs on and
     * stays on.
     * <p>
     * Set it to {@link FixSessionState#LOGGED_OUT} to keep a configured session from connecting - which is how a
     * session is taken out of service without removing its configuration.
     */
    @Builder.Default
    private FixSessionState desiredSessionState = FixSessionState.LOGGED_IN;

    /**
     * Login or logout response timeout, follows FIX specs and should probably not be changed
     */
    @Builder.Default
    private Duration logInOrOutResponseTimeout = Duration.ofSeconds(10);

    /**
     * How long a ResendRequest(35=2) this session sent may go without making progress before it is asked for again,
     * and then given up on.
     * <p>
     * A retransmission drives everything that follows it: the messages received on top of the gap are only delivered
     * once the request completes, new gaps found meanwhile are queued without a request of their own, and outgoing
     * application messages are held back (see {@link #maxOutgoingMessagesHeldDuringRecovery}). A request that can
     * never complete therefore stops the session dead while heartbeats keep flowing on both sides - which is what an
     * answer that goes missing leaves behind, a garbled message inside the range being the ordinary way there: it is
     * disregarded as section 4.8 requires, and nothing after it can close the range.
     * <p>
     * The delay is measured from the last message that advanced the recovery, not from the request, so a long but
     * progressing retransmission never trips it. On expiry the missing part of the range is asked for once more; if
     * that goes unanswered too, the session is logged out rather than left silently stalled.
     * <p>
     * Set to {@link Duration#ZERO} to wait forever, which is how the engine behaved before this existed.
     * <p>
     * Costs nothing on a healthy session: it is looked at once a second by the heartbeat task, and only while a
     * retransmission this session asked for is outstanding.
     */
    @Builder.Default
    private Duration resendRequestResponseTimeout = Duration.ofSeconds(30);

    /**
     * How many messages received with a MsgSeqNum(34) higher than expected the session holds on to while it waits for
     * the gap ahead of them to be filled.
     * <p>
     * Section 4.5 of the FIX Session Layer specification (state table rows 11 and 12) requires those messages to be
     * queued rather than dropped, so that they can be processed in order once the ResendRequest(35=2) has been
     * answered. This bounds that queue: a peer that keeps sending while never answering the ResendRequest would
     * otherwise grow it without end, so the session is logged out instead once the limit is reached.
     * <p>
     * Set to 0 for no limit, queueing whatever arrives for as long as the gap stays open. As with
     * {@link #maxMessagesResentPerRequest}, 0 turns the <em>restriction</em> off rather than the queueing, which is
     * required behaviour and cannot be disabled.
     * <p>
     * Costs nothing on a healthy session - the queue only ever receives messages on the out of sequence branch.
     */
    @Builder.Default
    private int maxOutOfSequenceMessagesQueued = 10_000;

    /**
     * How many application messages the session holds back while a retransmission it asked for is still under way.
     * <p>
     * Section 4.3.11 of the FIX Session Layer specification recommends waiting "a short period of time following
     * receipt of the Logon(35=A) message from the counterparty before transmitting queued or new application
     * messages to permit both sides to synchronize the FIX session". Holding them means their MsgSeqNum(34) is spent
     * once the recovery is over rather than in the middle of it.
     * <p>
     * Past this many, a message is refused rather than held: its
     * {@link FixSession.MessageSendOperationCallback} is called with the failure and nothing is sent, leaving the
     * application to decide what to do with a message the session could not take. Sending it on regardless would
     * defeat the synchronization the holding is for, and dropping it silently would lose it.
     * <p>
     * Set to 0 to never hold anything, which sends application messages straight through as this engine did before
     * the holding existed.
     * <p>
     * Costs nothing when no retransmission is under way, which is when the holding list does not exist at all.
     */
    @Builder.Default
    private int maxOutgoingMessagesHeldDuringRecovery = 1_000;

    /**
     * How many messages at most are actually put back on the wire in answer to a single peer ResendRequest(35=2).
     * <p>
     * The requested range is chosen by the peer, so without a bound it decides how much work this engine does and how
     * long its IO thread spends doing it. Past this many, the rest of the range is covered by a SequenceReset(35=4)
     * gap fill instead of being replayed, which section 4.8.5 explicitly allows - "the resender may choose to gap
     * fill rather than retransmit" - so the peer still ends up correctly synchronized, just without the messages the
     * session declined to send. It also stops the store being read past that point at all.
     * <p>
     * Set to 0 for no limit, retransmitting whatever the peer asks for however wide the range. Note this is the
     * opposite sense to {@link #maxOutgoingMessagesHeldDuringRecovery}, where 0 turns the holding off: here 0 turns
     * the <em>restriction</em> off, since a resend with no cap is the unrestricted behaviour.
     * <p>
     * Only ever consulted while answering a ResendRequest.
     */
    @Builder.Default
    private int maxMessagesResentPerRequest = 10_000;

    /**
     * How EndSeqNo(16) is filled in on a ResendRequest(35=2) this session sends: with the last message of the gap, or
     * with the "infinity" form that asks for everything from BeginSeqNo(7) onwards.
     * <p>
     * Section 4.8.2 allows a request to name "a single message, a range of messages or all messages", so both are
     * correct and the choice is the counterparty's to drive: some peers only answer one of the two forms. This engine
     * defaults to {@link ResendRequestRange#CLOSED}, which asks for exactly the messages that are missing;
     * QuickFIX/J's {@code ClosedResendInterval=N} - its default - is {@link ResendRequestRange#OPEN_ENDED} here.
     * <p>
     * Only affects requests this session sends. What it accepts is not configurable: an incoming EndSeqNo(16) of 0 or
     * 999999 is answered as open ended whatever this says, since the peer chose that form.
     */
    @Builder.Default
    private ResendRequestRange resendRequestRange = ResendRequestRange.CLOSED;

    /**
     * Specify a target fix application factory to use with the session
     * {@link FixApplicationFactorySettings#getInstanceId()}
     */
    @Builder.Default
    private String fixApplicationFactoryInstanceId = InstanceProvider.DEFAULT_INSTANCE_ID;
    /**
     * Specify a target fix message store to use with the session
     * {@link FixMessagesStoreSettings#getInstanceId()}
     */
    @Builder.Default
    private String fixMessageStoreInstanceId = InstanceProvider.DEFAULT_INSTANCE_ID;
    /**
     * Specify a target fix message logger to use with the session
     * {@link FixMessagesLoggerSettings#getInstanceId()}
     */
    @Builder.Default
    private String fixMessageLoggerInstanceId = InstanceProvider.DEFAULT_INSTANCE_ID;

    /**
     * Specify the target FixApplication instance id this session is bound to
     * within the selected FixApplicationFactory, see {@link FixApplicationFactory#getInstance(String)}
     */
    @Builder.Default
    private String fixApplicationInstanceId = InstanceProvider.DEFAULT_INSTANCE_ID;

    /**
     * Specify fix session plugins to use with the session
     * {@link FixEngineBuilder#getFixSessionsPlugins()}
     */
    @Singular
    private Map<Class<? extends FixSessionsPlugin<?>>, String> fixSessionPluginsInstanceIds;

    /**
     * FIX messages validation settings
     */
    @Builder.Default
    private ValidationSettings validationSettings = ValidationSettings.builder().build();

    /**
     * Setting to enable testing mode on the session (Logon TestMessageIndicator field)
     */
    @Builder.Default
    private boolean testingMode = false;

    /**
     * Deadline for flushing messages when an established FIX session must disconnect
     */
    @Builder.Default
    private Duration disconnectMessagesFlushDeadline = Duration.ofSeconds(2);

    /**
     * Session schedule settings
     */
    @Builder.Default
    private SessionScheduleSettings sessionScheduleSettings = SessionScheduleSettings.builder().build();

    /**
     * Continuous network RTT and remote clock-offset measurement settings.
     * <p>
     * When enabled (non-null {@link RttMeasurementSettings#getProbeInterval()}), the session emits
     * TestRequest probes at that interval and uses the matching Heartbeat responses (with the remote
     * SendingTime in tag 52) to compute a smoothed RTT and remote-vs-local clock offset.
     * Heartbeat-driven TestRequests issued by the existing heartbeat machinery are also fed into the
     * estimator regardless of this setting.
     */
    @Builder.Default
    private RttMeasurementSettings rttMeasurementSettings = RttMeasurementSettings.builder().build();

    /**
     * Cancel on disconnect settings sent by initiator during logon, settings also required for acceptors to validate incoming
     * FIX logon messages COD settings
     */
    @Builder.Default
    private CancelOnDisconnectSettings cancelOnDisconnectSettings = CancelOnDisconnectSettings.builder().build();

    /**
     * Whether removing these settings from their {@link FixSessionsSettingsStore} disconnects the session when it is
     * live.
     * <p>
     * On by default, which is what removing a session usually means: the engine stops managing it and the session
     * goes down. Turn it off to make the removal a configuration change only - the settings stop being managed and
     * the live session is left running until it drops for a reason of its own, which is what a venue asking you to
     * stop reconnecting a session <em>after</em> the trading day needs.
     * <p>
     * Read from the settings being removed, i.e. the ones the running session was started under.
     *
     * @see FixSessionsSettingsStore.Listener#onRemovedSession(FixSessionSettings)
     */
    @Builder.Default
    private boolean disconnectOnRemove = true;

    /**
     * Whether updating these settings in their {@link FixSessionsSettingsStore} restarts the session when it is live,
     * so that the new settings take effect immediately.
     * <p>
     * On by default: an update that does not reach the running session is an update that silently did not happen, and
     * that is the worse surprise of the two. Turn it off when the session matters more than the promptness of the
     * change - the new settings are managed straight away and take effect the next time the session is created,
     * rather than now.
     * <p>
     * "When it is live" is the whole of the condition: a session that is not connected needs no restart, and its new
     * settings are simply picked up when it next connects.
     * <p>
     * <b>Read from the settings the session is currently running under</b> - the old ones - not from the ones
     * replacing them. The flag describes how <em>this</em> session may be treated, and it is this session that a
     * restart would disturb; a new policy governs the update after it.
     *
     * @see FixSessionsSettingsStore.Listener#onUpdatedSession(FixSessionSettings, FixSessionSettings)
     */
    @Builder.Default
    private boolean restartLiveSessionOnUpdate = true;

    /**
     * Use direct byte buffer when encode a message
     */
    @Builder.Default
    private boolean messageEncodersDirectByteBuffers = true;

    /**
     * Use direct byte buffer when encode a message from a message encoder provided by a {@link org.lolaf.staffix.api.codec.FixMessageEncodersPool}
     */
    @Builder.Default
    private boolean pooledMessageEncodersDirectByteBuffers = true;

    /**
     * COD settings for initiator or acceptor session, see {@link FixApplication#onCancelOnDisconnectTriggered(FixSession, CancelOnDisconnectType)}
     */
    @Getter
    @SuperBuilder(toBuilder = true)
    @EqualsAndHashCode
    public static class CancelOnDisconnectSettings {

        /**
         * Toggles Cancel On Disconnect functionality.
         * For an initiator it will indicate to send the COD fields during logon sequence.
         * For an acceptor session it will enable COD fields handling during the logon sequence
         */
        @Builder.Default
        boolean enabled = false;

        /**
         * For an initiator session this is the COD setting to be sent during logon.
         * For acceptor and the initiator logon message does not contain any COD fields, it will use this value
         * to trigger a COD, a null value will disable COD, this allows to have COD functionality even if not specified by initiator in a logon message
         */
        @Builder.Default
        CancelOnDisconnectType cancelOnDisconnectType = CancelOnDisconnectType.CANCEL_ON_DISCONNECT_OR_LOGOUT;

        /**
         * COD enum FIX enum mapping
         */
        @Builder.Default
        Map<CancelOnDisconnectType, Character> cancelOnDisconnectTypeFieldCodes = Map.of(
                CancelOnDisconnectType.DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT, '0',
                CancelOnDisconnectType.CANCEL_ON_DISCONNECT_ONLY, '1',
                CancelOnDisconnectType.CANCEL_ON_LOGOUT_ONLY, '2',
                CancelOnDisconnectType.CANCEL_ON_DISCONNECT_OR_LOGOUT, '3');

        /**
         * COD FIX field mapping in logon message
         */
        @Builder.Default
        int cancelOnDisconnectTypeFieldCode = 35002;

        /**
         * COD timeout window FIX field mapping in a logon message
         */
        @Builder.Default
        int codTimeoutWindowFieldCode = 35003;

        /**
         * For an initiator session, this will be the COD timeout windows sent in the logon message
         * For an acceptor session, this is the received allowed minimum COD timeout window value.
         * <p>
         * window before triggering {@link FixApplication#onCancelOnDisconnectTriggered(FixSession, CancelOnDisconnectType)}
         */
        @Builder.Default
        Duration codTimeoutWindow = Duration.ofSeconds(10);
        /**
         * Scale of the codTimeoutWindow to be sent in the logon message,
         * I.E with TimeUnit.MILLISECONDS and codTimeoutWindow set to 10s , 10,000 will be sent in the logon message
         */
        @Builder.Default
        TimeUnit codTimeoutWindowScale = TimeUnit.MILLISECONDS;
    }

    /**
     * Heartbeat interval settings
     */
    @Getter
    @SuperBuilder(toBuilder = true)
    @EqualsAndHashCode
    public static class HeartbeatInterval {

        /**
         * The heartbeat interval used by the initiator on logon requests
         */
        @Builder.Default
        Duration initiatorInterval = Duration.ofSeconds(10);
        /**
         * For acceptors, the lower bound interval that will be accepted by an initiator
         */
        @Builder.Default
        Duration acceptorLowerBoundInterval = Duration.ofSeconds(1);
        /**
         * For acceptors, the upper bound interval that will be accepted by an initiator
         */
        @Builder.Default
        Duration acceptorUpperBoundInterval = Duration.ofSeconds(20);
    }

    /**
     * Messages validation settings
     */
    @Getter
    @SuperBuilder(toBuilder = true)
    @EqualsAndHashCode
    public static class ValidationSettings {

        /**
         * Validate received messages checksums, enabling it has slight impact on performance
         */
        @Builder.Default
        private boolean validateChecksum = true;
        /**
         * Ensure fields have always a value set, disabling it will allow message with fields such as
         * {@code $field=\001} to not be rejected
         */
        @Builder.Default
        private boolean validateFieldsHaveValues = true;
        /**
         * Allows user defined fields not defined in the data dictionary, a user defined tag being one the standard
         * does not own - see {@link org.lolaf.staffix.api.fields.FixField#isUserDefined(int)}
         */
        @Builder.Default
        private boolean allowUserDefinedFields = false;
        /**
         * Allows message fields outside the user defined range - tags the standard owns, which this dictionary does
         * not define - see {@link org.lolaf.staffix.api.fields.FixField#isUserDefined(int)}. A peer speaking a later
         * FIX version reaches here: the tags the Global Technical Committee has allocated from 40000 up are standard
         * ones, so a session on an older dictionary takes them through this setting rather than through
         * {@link #allowUserDefinedFields}
         */
        @Builder.Default
        private boolean allowUnknownFields = false;
        /**
         * Automatically reject message that are missing required fields defined in the used data dictionary,
         * enabling it has slight impact on performance
         */
        @Builder.Default
        private boolean validateRequiredFields = false;

        /**
         * Reject messages whose fields appear out of the order defined by the data dictionary. This covers both
         * fields breaching the header/body/trailer section ordering (rejected with
         * TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER) and repeating-group fields appearing out of their declared order
         * (rejected with REPEATING_GROUP_FIELDS_OUT_OF_ORDER). Enabling it has a slight impact on performance.
         */
        @Builder.Default
        private boolean validateFieldsOutOfOrder = false;
        /**
         * Reject messages where the same tag appears more than once at the same level (message body, or within a
         * single repeating-group entry), rejected with TAG_APPEARS_MORE_THAN_ONCE. Enabling it has a slight impact
         * on performance.
         */
        @Builder.Default
        private boolean validateDuplicateTags = false;
        /**
         * Automatically reject message that contains valid tags, but not defined for the given message in the
         * used data dictionary, disabling it has slight impact on performance
         */
        @Builder.Default
        private boolean allowUndefinedTagsForMessage = true;
        /**
         * Validates that received message TARGET_COMP_ID and SENDER_COMP_ID fields match configured values for
         * session, enabling it has slight impact on performance
         */
        @Builder.Default
        private boolean validateCompId = false;

        /**
         * CompIDs this session accepts in the OnBehalfOfCompID(115) field of received messages, i.e. the firms the
         * peer is allowed to send on behalf of when the session carries third party routing (section 6.2 of the FIX
         * Session Layer specification). A message carrying a value outside the set is rejected with
         * SessionRejectReason(373) 9, CompID problem, and RefTagID(371) 115; the session itself is left up, a routing
         * error being about one message rather than about who the peer is.
         * <p>
         * Null or empty, the default, leaves the field unchecked and costs nothing. Note that only the value is
         * checked: the specification also asks that a session using third party routing use it on every application
         * message it sends, which is not enforced here.
         */
        @Builder.Default
        private Set<String> expectedOnBehalfOfCompIds = null;

        /**
         * CompIDs this session accepts in the DeliverToCompID(128) field of received messages, i.e. the firms the peer
         * is allowed to address through it. Behaves exactly as {@link #expectedOnBehalfOfCompIds}, RefTagID(371) being
         * 128 on the reject, and is likewise unchecked by default.
         */
        @Builder.Default
        private Set<String> expectedDeliverToCompIds = null;

        /**
         * Validate that the BeginString(8) of each received message matches the session's FIX version, disconnecting
         * with a Logout referencing the offending value otherwise. Disabled by default: enabling it has a slight impact
         * on latency
         */
        @Builder.Default
        private boolean validateBeginString = false;

        /**
         * Detect the garbled message conditions of section 4.5.2 of the FIX Session Layer specification that need
         * dedicated checks: BeginString(8), BodyLength(9) and MsgType(35) not being the first three fields of the
         * message, and a BeginString(8) that is not a defined FIX session profile identifier. Disabled by default:
         * enabling it checks the position of every header field of every received message and hashes their
         * BeginString(8), which has a slight impact on latency.
         * <p>
         * Note that the remaining condition, an incorrect BodyLength(9) byte count, is always detected whatever this
         * setting: it comes for free from a comparison the parser makes anyway.
         * <p>
         * A garbled message is disregarded: it is not answered, it does not increment NextNumIn and it does not reset
         * the heartbeat interval timer, so the peer's next message surfaces as a sequence gap and drives the usual
         * message recovery.
         */
        @Builder.Default
        private boolean detectGarbledMessages = false;

        /**
         * Tolerance allowed between a received message's SendingTime(52) and this session's own clock, in either
         * direction — the SendingTimeThreshold of section 4.2.3 of the FIX Session Layer specification. A message
         * whose SendingTime(52) is older than {@code now - maxSendingTime}, or dated later than
         * {@code now + maxSendingTime}, is rejected with SessionRejectReason(373) 10, SendingTime accuracy problem,
         * and the session is then logged out, as the specification prescribes.
         * <p>
         * Null, the default, disables the check and leaves SendingTime(52) unparsed on the message path, which is
         * the faster path: enabling it decodes tag 52 of every received message.
         */
        private Duration maxSendingTime;

        /**
         * Maximum message length in octets this session is able to receive. When set, it is advertised to the peer in
         * the MaxMessageSize(383) field of the Logon(35=A) message and is used to reject oversized inbound messages.
         * Set to null to disable it.
         */
        @Builder.Default
        private Integer maxMessageSize = null;

        /**
         * Minimum MaxMessageSize(383) this session requires the peer to advertise on its Logon(35=A), i.e. the largest
         * message we may need to send it. If the peer advertises a smaller value the FIX session is terminated with a
         * Logout(35=5) carrying Text(58) {@code "MaxMessageSize(383) = <peer value> < required message size <M>"}, as
         * described in the FIX session layer specification. Applies to both acceptor and initiator sessions. Set to
         * null (the default) to require no minimum.
         */
        @Builder.Default
        private Integer requiredPeerMaxMessageSize = null;
    }

    /**
     * Settings for continuous RTT and remote clock-offset measurement.
     * Disabled by default; set {@link #probeInterval} to a positive {@link Duration} to enable.
     */
    @Getter
    @SuperBuilder(toBuilder = true)
    @EqualsAndHashCode
    public static class RttMeasurementSettings {

        /**
         * Interval between TestRequest probes used to measure RTT.
         * Null or zero disables continuous probing. Heartbeat-driven TestRequests issued by the
         * standard heartbeat machinery (when the peer goes silent) still feed the estimator.
         */
        @Builder.Default
        private Duration probeInterval = null;

        /**
         * EMA time window for smoothing RTT and clock-offset samples.
         * A longer window absorbs more jitter at the cost of slower convergence.
         */
        @Builder.Default
        private Duration emaTimeWindow = Duration.ofSeconds(30);

        /**
         * Samples whose measured RTT exceeds this threshold are discarded as outliers
         * (e.g. GC pauses, scheduling spikes, network blips). Set to {@code null} to accept any RTT.
         */
        @Builder.Default
        private Duration maxAcceptedRtt = Duration.ofSeconds(2);

        /**
         * Prefix for the TestReqID of probe messages, used to distinguish them in logs from
         * heartbeat-driven TestRequests.
         */
        @Builder.Default
        private String probeTestReqIdPrefix = "RTT-measurement-";

        /**
         * Empirical bias subtracted from each raw clock-offset sample before it is fed to the EMA.
         * Models the delay between the moment the remote stamps SendingTime (tag 52) into the
         * outgoing byte buffer and the moment those bytes actually leave the wire — encode tail,
         * write syscall, kernel queueing, NIC handoff. Because the NTP-style offset formula
         * {@code R − (T1 + T2) / 2} assumes the remote's SendingTime coincides with the actual
         * send instant, this delay shows up as a persistent positive offset even when the two
         * peers share the same clock (e.g. on localhost). Subtracting it removes that systemic
         * bias and leaves only the genuine wall-clock skew between the peers.
         *
         * <p>The right value is implementation- and deployment-specific: measure it on a loopback
         * run (where the true offset is zero) and use the steady-state EMA you observe. A few
         * microseconds is typical for a Java FIX engine on a tuned host; high-latency or busy
         * systems may see more. Set to {@link Duration#ZERO} or {@code null} to disable the
         * correction.
         */
        @Builder.Default
        private Duration sendingTimeToWireDelay = Duration.ofNanos(1500L);
    }

    @Getter
    @Builder(toBuilder = true)
    @EqualsAndHashCode
    public static class SessionScheduleSettings {

        /**
         * Trigger delay to call {@link FixApplication#onPreOutsideSessionTime(FixSession, Duration)}
         */
        @Builder.Default
        private Duration outsideSessionTimePreTriggerDelay = Duration.ofSeconds(2);

        /**
         * The zone the schedule's times are read in - the venue's, not the engine's.
         * <p>
         * Defaults to the JVM's, which is rarely what is wanted for a venue in another country, and never what is
         * wanted if the two observe daylight saving on different dates.
         */
        @Builder.Default
        private TimeZone timeZone = TimeZone.getDefault();

        /**
         * How often the schedule is re-evaluated, which bounds how late an open or close can be acted on.
         */
        @Builder.Default
        private Duration withinSessionTimeCheckInterval = Duration.ofSeconds(1);

        /**
         * The days the session keeps trading windows: it opens at {@link ScheduleEntry#startTime} and closes at
         * {@link ScheduleEntry#endTime}, logging out and staying down until the next open.
         * <p>
         * A day covered by a {@link NonStopScheduleEntry} must not be covered here - a day is either windowed or
         * non-stop, never both.
         */
        @Singular
        private List<ScheduleEntry> sessionSchedules;

        /**
         * The days the session runs non-stop: it is up for the whole of the day and restarts its sequence numbers at
         * {@link NonStopScheduleEntry#sequenceResetTime} through the section 4.4.2 exchange - both ends back to 1
         * over the live connection, with nothing logged out and no connection dropped.
         * <p>
         * Deliberately not spelled as a {@link ScheduleEntry} whose start and end are equal, which is how some
         * engines say it: staffix rejects such an entry as a zero-duration typo, and it would read as a mistake
         * rather than as a session that never closes. A day listed here has no open and no close, only a point at
         * which the numbering starts again.
         * <p>
         * Consecutive non-stop days are contiguous - midnight between them is not a close - and a non-stop day is
         * contiguous with a neighbouring window that runs up to or starts at that midnight. A day named by neither
         * list is a day the session is down. Both lists empty is the default: a session with no schedule at all,
         * always up and never resetting on a timer.
         */
        @Singular
        private List<NonStopScheduleEntry> nonStopSchedules;

        @Value
        @Builder
        public static class ScheduleEntry {

            @NonNull
            DayOfWeek startDay;
            @NonNull
            DayOfWeek endDay;
            @NonNull
            LocalTime startTime;
            @NonNull
            LocalTime endTime;

        }

        /**
         * One day on which the session stays up around the clock, optionally restarting its sequence numbers at
         * {@link #sequenceResetTime} through the section 4.4.2 exchange.
         * <p>
         * Held per day rather than once per session so that counterparties who agreed on a different roll time for
         * different days - or on the roles changing hands between them, or on rolling on some days and not others -
         * can say so.
         */
        @Value
        @Builder
        public static class NonStopScheduleEntry {

            /**
             * The day this entry describes. At most one entry per day.
             */
            @NonNull
            DayOfWeek dayOfWeek;

            /**
             * The time of day, in the schedule's {@link SessionScheduleSettings#timeZone}, at which the numbering
             * restarts, or null on a day that is non-stop and does not roll.
             * <p>
             * Nullable because the two things an entry says are independent: that the day never closes, and that the
             * numbering restarts on it. A session rolling weekly rather than daily - up around the clock all week,
             * resetting on the Sunday alone - has six days of the first without the second, and there is no time of
             * day that expresses "no reset". Naming an hour the deployment is quiet is not the same statement: it
             * still rolls, it just rolls where nobody was looking.
             * <p>
             * Null and {@link #initiatesReset} go together: an entry states both or neither, which
             * {@code FixSessionSettingsValidator} enforces. A day with a time and no role would have no one to
             * perform the roll it asks for, and a role with no time is the shape a forgotten time leaves behind.
             */
            LocalTime sequenceResetTime;

            /**
             * True when this end sends the section 4.4.2 Logon(35=A) at the appointed time, false when it waits for
             * the peer's and only acknowledges, and null on a day that does not roll at all.
             * <p>
             * Boxed rather than primitive so that "not stated" is distinguishable from "awaits": a primitive would
             * default to false and quietly leave a rolling day that nobody rolls. Mandatory wherever
             * {@link #sequenceResetTime} is set, because section 4.4.2 has the counterparties agree between
             * themselves which peer initiates and nothing about that agreement is visible on the wire, so staffix has
             * nothing to derive a default from: both ends initiating puts two resets on the wire at once, and neither
             * initiating leaves the numbering running for ever.
             */
            Boolean initiatesReset;
        }
    }
}