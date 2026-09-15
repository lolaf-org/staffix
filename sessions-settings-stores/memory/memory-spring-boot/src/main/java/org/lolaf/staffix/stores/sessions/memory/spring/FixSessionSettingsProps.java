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
package org.lolaf.staffix.stores.sessions.memory.spring;

import org.springframework.boot.context.properties.NestedConfigurationProperty;
import lombok.Data;
import org.lolaf.staffix.api.session.CancelOnDisconnectType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.api.session.ResendRequestRange;
import org.lolaf.staffix.spring.boot.spi.FixSessionIdProps;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

import java.time.DayOfWeek;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * The {@code staffix.*} properties for the in-memory session settings store.
 */
@Data
@ConfigurationPropertiesSource
public class FixSessionSettingsProps {

    /**
     * The session's identity on the wire - FIX version and CompIDs. Required, and what an inbound Logon is
     * matched against.
     */
    @NestedConfigurationProperty
    private FixSessionIdProps fixSessionId;

    /**
     * Whether this session is an {@code INITIATOR} or an {@code ACCEPTOR}. Required.
     */
    private FixSession.FixSessionType type;

    /**
     * Which dictionary's encoders this session uses, for a counterparty whose messages differ from the
     * standard. Defaults to the one every generated FIX package registers.
     */
    private String dictionaryId;

    /**
     * HeartBtInt(108): how often a quiet session proves it is alive, and how long it waits before asking.
     */
    @NestedConfigurationProperty
    private HeartbeatProps heartbeat;

    /**
     * The precision SendingTime(52) is written to. Microseconds by default; a counterparty that cannot parse
     * sub-second digits needs {@link TimeUnit#SECONDS}.
     */
    private TimeUnit sendingTimeAccuracy;

    /**
     * Settings the application itself declares a need for, passed through untouched.
     */
    private Map<String, String> applicationSessionSettings = new LinkedHashMap<>();

    /**
     * Whether the Logon lists the message types this session sends and accepts, in MsgTypeGrp(384). <p> Off by
     * default: it makes the Logon considerably larger and few counterparties read it.
     */
    private boolean advertiseMsgTypeGrpOnLogon;
    /**
     * Whether the Logon names this engine and its version. Off by default - some venues record it, most ignore
     * it.
     */
    private boolean advertiseEngineOnLogon;
    /**
     * Whether the Logon names the application and its version, as distinct from the engine's.
     */
    private boolean advertiseApplicationOnLogon;
    /**
     * Whether the Logon carries NextExpectedMsgSeqNum(789), telling the peer what we expect next so a gap is
     * resolved by the Logon exchange itself rather than by a ResendRequest afterwards (section 4.4.1). <p> On
     * by default. A counterparty that does not support it will reject the field, in which case turn it off.
     */
    private Boolean enabledLogonNextExpectedMsgSeqNum;

    /**
     * Addresses an acceptor will accept this session from. Empty means anywhere. <p> Checked before the Logon
     * is processed, so a connection from elsewhere is dropped without the session ever starting.
     */
    private List<String> allowedAddresses = new ArrayList<>();

    /**
     * Flag to reset sequence number on logon, if not specified, the counterparty manages the sequence reset
     * flag on session establishment
     */
    private Boolean resetSeqNumOnLogon;

    /**
     * The state the session should hold itself in. {@link FixSessionState#LOGGED_IN} by default, so it logs on
     * and stays on. <p> Set it to {@link FixSessionState#LOGGED_OUT} to keep a configured session from
     * connecting - which is how a session is taken out of service without removing its configuration.
     */
    private FixSessionState desiredSessionState;

    /**
     * Login or logout response timeout, follows FIX specs and should probably not be changed
     */
    private Duration logInOrOutResponseTimeout;
    /**
     * How long a ResendRequest(35=2) this session sent may go without making progress before it is asked for
     * again, and then given up on. <p> A retransmission drives everything that follows it: the messages
     * received on top of the gap are only delivered once the request completes, new gaps found meanwhile are
     * queued without a request of their own, and outgoing application messages are held back (see {@link
     * #maxOutgoingMessagesHeldDuringRecovery}). A request that can never complete therefore stops the session
     * dead while heartbeats keep flowing on both sides - which is what an answer that goes missing leaves
     * behind, a garbled message inside the range being the ordinary way there: it is disregarded as section
     * 4.8 requires, and nothing after it can close the range. <p> The delay is measured from the last message
     * that advanced the recovery, not from the request, so a long but progressing retransmission never trips
     * it. On expiry the missing part of the range is asked for once more; if that goes unanswered too, the
     * session is logged out rather than left silently stalled. <p> Set to {@link Duration#ZERO} to wait
     * forever, which is how the engine behaved before this existed. <p> Costs nothing on a healthy session: it
     * is looked at once a second by the heartbeat task, and only while a retransmission this session asked for
     * is outstanding.
     */
    private Duration resendRequestResponseTimeout;
    /**
     * How many messages received with a MsgSeqNum(34) higher than expected the session holds on to while it
     * waits for the gap ahead of them to be filled. <p> Section 4.5 of the FIX Session Layer specification
     * (state table rows 11 and 12) requires those messages to be queued rather than dropped, so that they can
     * be processed in order once the ResendRequest(35=2) has been answered. This bounds that queue: a peer
     * that keeps sending while never answering the ResendRequest would otherwise grow it without end, so the
     * session is logged out instead once the limit is reached. <p> Set to 0 for no limit, queueing whatever
     * arrives for as long as the gap stays open. As with {@link #maxMessagesResentPerRequest}, 0 turns the
     * <em>restriction</em> off rather than the queueing, which is required behaviour and cannot be disabled.
     * <p> Costs nothing on a healthy session - the queue only ever receives messages on the out of sequence
     * branch.
     */
    private Integer maxOutOfSequenceMessagesQueued;
    /**
     * How many application messages the session holds back while a retransmission it asked for is still under
     * way. <p> Section 4.3.11 of the FIX Session Layer specification recommends waiting "a short period of
     * time following receipt of the Logon(35=A) message from the counterparty before transmitting queued or
     * new application messages to permit both sides to synchronize the FIX session". Holding them means their
     * MsgSeqNum(34) is spent once the recovery is over rather than in the middle of it. <p> Past this many, a
     * message is refused rather than held: its {@link FixSession.MessageSendOperationCallback} is called with
     * the failure and nothing is sent, leaving the application to decide what to do with a message the session
     * could not take. Sending it on regardless would defeat the synchronization the holding is for, and
     * dropping it silently would lose it. <p> Set to 0 to never hold anything, which sends application
     * messages straight through as this engine did before the holding existed. <p> Costs nothing when no
     * retransmission is under way, which is when the holding list does not exist at all.
     */
    private Integer maxOutgoingMessagesHeldDuringRecovery;
    /**
     * How many messages at most are actually put back on the wire in answer to a single peer
     * ResendRequest(35=2). <p> The requested range is chosen by the peer, so without a bound it decides how
     * much work this engine does and how long its IO thread spends doing it. Past this many, the rest of the
     * range is covered by a SequenceReset(35=4) gap fill instead of being replayed, which section 4.8.5
     * explicitly allows - "the resender may choose to gap fill rather than retransmit" - so the peer still
     * ends up correctly synchronized, just without the messages the session declined to send. It also stops
     * the store being read past that point at all. <p> Set to 0 for no limit, retransmitting whatever the peer
     * asks for however wide the range. Note this is the opposite sense to {@link
     * #maxOutgoingMessagesHeldDuringRecovery}, where 0 turns the holding off: here 0 turns the
     * <em>restriction</em> off, since a resend with no cap is the unrestricted behaviour. <p> Only ever
     * consulted while answering a ResendRequest.
     */
    private Integer maxMessagesResentPerRequest;
    /**
     * How EndSeqNo(16) is filled in on a ResendRequest(35=2) this session sends: with the last message of the
     * gap, or with the "infinity" form that asks for everything from BeginSeqNo(7) onwards. <p> Section 4.8.2
     * allows a request to name "a single message, a range of messages or all messages", so both are correct
     * and the choice is the counterparty's to drive: some peers only answer one of the two forms. This engine
     * defaults to {@link ResendRequestRange#CLOSED}, which asks for exactly the messages that are missing;
     * QuickFIX/J's {@code ClosedResendInterval=N} - its default - is {@link ResendRequestRange#OPEN_ENDED}
     * here. <p> Only affects requests this session sends. What it accepts is not configurable: an incoming
     * EndSeqNo(16) of 0 or 999999 is answered as open ended whatever this says, since the peer chose that
     * form.
     */
    private ResendRequestRange resendRequestRange;

    /**
     * Specify a target fix application factory to use with the session {@link
     * org.lolaf.staffix.api.application.FixApplicationFactorySettings#getInstanceId()}
     */
    private String fixApplicationFactoryInstanceId;
    /**
     * Specify the target FixApplication instance id this session is bound to within the selected
     * the application factory, see {@link org.lolaf.staffix.api.application.FixApplicationFactory#getInstance(String)}
     */
    private String fixApplicationInstanceId;
    /**
     * Specify a target fix message store to use with the session {@link
     * org.lolaf.staffix.api.stores.FixMessagesStoreSettings#getInstanceId()}
     */
    private String fixMessageStoreInstanceId;
    /**
     * Specify a target fix message logger to use with the session {@link
     * org.lolaf.staffix.api.logging.FixMessagesLoggerSettings#getInstanceId()}
     */
    private String fixMessageLoggerInstanceId;

    /**
     * Map of FIX session plugin instance ids keyed by the plugin class FQCN
     * (e.g. {@code org.lolaf.staffix.tracing.otlp.OtelTracing}).
     */
    private Map<String, String> fixSessionPluginsInstanceIds = new LinkedHashMap<>();

    /**
     * Use direct byte buffer when encode a message
     */
    private Boolean messageEncodersDirectByteBuffers;
    /**
     * Use direct byte buffer when encode a message from a message encoder provided by a {@link
     * org.lolaf.staffix.api.codec.FixMessageEncodersPool}
     */
    private Boolean pooledMessageEncodersDirectByteBuffers;
    /**
     * Whether removing these settings from their {@link org.lolaf.staffix.api.session.FixSessionsSettingsStore} disconnects the session when
     * it is live. <p> On by default, which is what removing a session usually means: the engine stops managing
     * it and the session goes down. Turn it off to make the removal a configuration change only - the settings
     * stop being managed and the live session is left running until it drops for a reason of its own, which is
     * what a venue asking you to stop reconnecting a session <em>after</em> the trading day needs. <p> Read
     * from the settings being removed, i.e. the ones the running session was started under. @see
     * FixSessionsSettingsStore.Listener#onRemovedSession(FixSessionSettings)
     */
    private Boolean disconnectOnRemove;
    /**
     * Whether updating these settings in their {@link org.lolaf.staffix.api.session.FixSessionsSettingsStore} restarts the session when it
     * is live, so that the new settings take effect immediately. <p> On by default: an update that does not
     * reach the running session is an update that silently did not happen, and that is the worse surprise of
     * the two. Turn it off when the session matters more than the promptness of the change - the new settings
     * are managed straight away and take effect the next time the session is created, rather than now. <p>
     * "When it is live" is the whole of the condition: a session that is not connected needs no restart, and
     * its new settings are simply picked up when it next connects. <p> <b>Read from the settings the session
     * is currently running under</b> - the old ones - not from the ones replacing them. The flag describes how
     * <em>this</em> session may be treated, and it is this session that a restart would disturb; a new policy
     * governs the update after it. @see FixSessionsSettingsStore.Listener#onUpdatedSession(FixSessionSettings,
     * FixSessionSettings)
     */
    private Boolean restartLiveSessionOnUpdate;

    /**
     * What this session checks on every inbound message. Each check costs something on the message path, which
     * is why most are off.
     */
    @NestedConfigurationProperty
    private ValidationProps validation;

    /**
     * Setting to enable testing mode on the session (Logon TestMessageIndicator field)
     */
    private Boolean testingMode;

    /**
     * Deadline for flushing messages when an established FIX session must disconnect
     */
    private Duration disconnectMessagesFlushDeadline;

    /**
     * When the session is expected to be up, in the venue's own time zone.
     */
    @NestedConfigurationProperty
    private ScheduleProps schedule;

    /**
     * What the counterparty should do with resting orders when this session goes away.
     */
    @NestedConfigurationProperty
    private CodProps cancelOnDisconnect;

    /**
     * Round-trip measurement against the counterparty, off unless configured.
     */
    @NestedConfigurationProperty
    private RttMeasurementProps rttMeasurement;

    @Data
    public static class HeartbeatProps {
        /**
         * The interval an initiator proposes at Logon.
         */
        private Duration initiatorInterval;
        /**
         * The shortest interval an acceptor will accept from an initiator.
         */
        private Duration acceptorLowerBoundInterval;
        /**
         * The longest interval an acceptor will accept.
         */
        private Duration acceptorUpperBoundInterval;
    }

    @Data
    public static class ValidationProps {
        /**
         * Validate received messages checksums, enabling it has slight impact on performance
         */
        private Boolean validateChecksum;
        /**
         * Ensure fields have always a value set, disabling it will allow message with fields such as {@code
         * $field=\001} to not be rejected
         */
        private Boolean validateFieldsHaveValues;
        /**
         * Reject messages whose fields appear out of the order defined by the data dictionary. This covers
         * both fields breaching the header/body/trailer section ordering (rejected with
         * TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER) and repeating-group fields appearing out of their declared
         * order (rejected with REPEATING_GROUP_FIELDS_OUT_OF_ORDER). Enabling it has a slight impact on
         * performance.
         */
        private Boolean validateFieldsOutOfOrder;
        /**
         * Reject messages where the same tag appears more than once at the same level (message body, or within
         * a single repeating-group entry), rejected with TAG_APPEARS_MORE_THAN_ONCE. Enabling it has a slight
         * impact on performance.
         */
        private Boolean validateDuplicateTags;
        /**
         * Allows user defined fields not defined in the data dictionary, a user defined tag being one the
         * standard does not own - see {@link org.lolaf.staffix.api.fields.FixField#isUserDefined(int)}
         */
        private Boolean allowUserDefinedFields;
        /**
         * Allows message fields outside the user defined range - tags the standard owns, which this dictionary
         * does not define - see {@link org.lolaf.staffix.api.fields.FixField#isUserDefined(int)}. A peer
         * speaking a later FIX version reaches here: the tags the Global Technical Committee has allocated
         * from 40000 up are standard ones, so a session on an older dictionary takes them through this setting
         * rather than through {@link #allowUserDefinedFields}
         */
        private Boolean allowUnknownFields;
        /**
         * Automatically reject message that are missing required fields defined in the used data dictionary,
         * enabling it has slight impact on performance
         */
        private Boolean validateRequiredFields;
        /**
         * Automatically reject message that contains valid tags, but not defined for the given message in the
         * used data dictionary, disabling it has slight impact on performance
         */
        private Boolean allowUndefinedTagsForMessage;
        /**
         * Validates that received message TARGET_COMP_ID and SENDER_COMP_ID fields match configured values for
         * session, enabling it has slight impact on performance
         */
        private Boolean validateCompId;
        /**
         * CompIDs this session accepts in the OnBehalfOfCompID(115) field of received messages, i.e. the firms
         * the peer is allowed to send on behalf of when the session carries third party routing (section 6.2
         * of the FIX Session Layer specification). A message carrying a value outside the set is rejected with
         * SessionRejectReason(373) 9, CompID problem, and RefTagID(371) 115; the session itself is left up, a
         * routing error being about one message rather than about who the peer is. <p> Null or empty, the
         * default, leaves the field unchecked and costs nothing. Note that only the value is checked: the
         * specification also asks that a session using third party routing use it on every application message
         * it sends, which is not enforced here.
         */
        private Set<String> expectedOnBehalfOfCompIds;
        /**
         * CompIDs this session accepts in the DeliverToCompID(128) field of received messages, i.e. the firms
         * the peer is allowed to address through it. Behaves exactly as {@link #expectedOnBehalfOfCompIds},
         * RefTagID(371) being 128 on the reject, and is likewise unchecked by default.
         */
        private Set<String> expectedDeliverToCompIds;
        /**
         * Validate that the BeginString(8) of each received message matches the session's FIX version,
         * disconnecting with a Logout referencing the offending value otherwise. Disabled by default: enabling
         * it has a slight impact on latency
         */
        private Boolean validateBeginString;
        /**
         * Detect the garbled message conditions of section 4.5.2 of the FIX Session Layer specification that
         * need dedicated checks: BeginString(8), BodyLength(9) and MsgType(35) not being the first three
         * fields of the message, and a BeginString(8) that is not a defined FIX session profile identifier.
         * Disabled by default: enabling it checks the position of every header field of every received message
         * and hashes their BeginString(8), which has a slight impact on latency. <p> Note that the remaining
         * condition, an incorrect BodyLength(9) byte count, is always detected whatever this setting: it comes
         * for free from a comparison the parser makes anyway. <p> A garbled message is disregarded: it is not
         * answered, it does not increment NextNumIn and it does not reset the heartbeat interval timer, so the
         * peer's next message surfaces as a sequence gap and drives the usual message recovery.
         */
        private Boolean detectGarbledMessages;
        /**
         * Tolerance allowed between a received message's SendingTime(52) and this session's own clock, in
         * either direction — the SendingTimeThreshold of section 4.2.3 of the FIX Session Layer specification.
         * A message whose SendingTime(52) is older than {@code now - maxSendingTime}, or dated later than
         * {@code now + maxSendingTime}, is rejected with SessionRejectReason(373) 10, SendingTime accuracy
         * problem, and the session is then logged out, as the specification prescribes. <p> Null, the default,
         * disables the check and leaves SendingTime(52) unparsed on the message path, which is the faster
         * path: enabling it decodes tag 52 of every received message.
         */
        private Duration maxSendingTime;
        /**
         * Maximum message length in octets this session is able to receive. When set, it is advertised to the
         * peer in the MaxMessageSize(383) field of the Logon(35=A) message and is used to reject oversized
         * inbound messages. Set to null to disable it.
         */
        private Integer maxMessageSize;
        /**
         * Minimum MaxMessageSize(383) this session requires the peer to advertise on its Logon(35=A), i.e. the
         * largest message we may need to send it. If the peer advertises a smaller value the FIX session is
         * terminated with a Logout(35=5) carrying Text(58) {@code "MaxMessageSize(383) = <peer value> <
         * required message size <M>"}, as described in the FIX session layer specification. Applies to both
         * acceptor and initiator sessions. Set to null (the default) to require no minimum.
         */
        private Integer requiredPeerMaxMessageSize;
    }

    @Data
    public static class ScheduleProps {
        /**
         * How long before the close the application is warned, so it can wind down rather than be cut off.
         */
        private Duration outsideSessionTimePreTriggerDelay;
        /**
         * The zone the times below are read in - the venue's, not the engine's. Defaults to the JVM's, which
         * is rarely right for a venue abroad.
         */
        private TimeZone timeZone;
        /**
         * How often the schedule is re-evaluated, which bounds how late an open or close can be acted on.
         */
        private Duration withinSessionTimeCheckInterval;
        /**
         * The trading windows. A day is either windowed here or non-stop, never both.
         */
        private List<ScheduleEntryProps> sessionSchedules = new ArrayList<>();
        /**
         * The days the session runs non-stop, restarting its numbering at the entry's time, section 4.4.2. A day
         * named here must not be covered by {@link #sessionSchedules}.
         */
        private List<NonStopScheduleEntryProps> nonStopSchedules = new ArrayList<>();

        @Data
        public static class ScheduleEntryProps {
            /**
             * The day the window opens on.
             */
            private DayOfWeek startDay;
            /**
             * The day it closes on, which may be a later day for a window spanning midnight.
             */
            private DayOfWeek endDay;
            /**
             * The open, as HH:mm:ss in the zone above.
             */
            private String startTime;
            /**
             * The close, as HH:mm:ss in the zone above.
             */
            private String endTime;
        }

        @Data
        public static class NonStopScheduleEntryProps {
            /**
             * The day this entry describes. At most one entry per day.
             */
            private DayOfWeek dayOfWeek;
            /**
             * Time of day, in the schedule's time zone, at which the numbering restarts. Unset on a day that is
             * non-stop and does not roll - a session resetting weekly rather than daily has six such days, and no
             * time of day says "no reset".
             */
            private String sequenceResetTime;
            /**
             * True when this end sends the reset, false when it awaits the peer's, unset on a day that does not roll.
             * Required alongside {@link #sequenceResetTime}: section 4.4.2 has the counterparties agree it between
             * themselves, so there is no default to fall back on.
             */
            private Boolean initiatesReset;
        }
    }

    @Data
    public static class RttMeasurementProps {
        /**
         * How often a probe is sent. Each one is a TestRequest, so this is traffic the counterparty sees.
         */
        private Duration probeInterval;
        /**
         * The window the measurement is averaged over.
         */
        private Duration emaTimeWindow;
        /**
         * Above this a probe is discarded as an outlier rather than skewing the average.
         */
        private Duration maxAcceptedRtt;
        /**
         * Prefix marking a TestRequest as a probe, so its Heartbeat is recognised as the reply.
         */
        private String probeTestReqIdPrefix;
        /**
         * Subtracted from each measurement to account for the time between stamping SendingTime and reaching
         * the wire.
         */
        private Duration sendingTimeToWireDelay;
    }

    @Data
    public static class CodProps {
        /**
         * Whether cancel-on-disconnect is advertised at all.
         */
        private boolean enabled;
        /**
         * What the counterparty should cancel on: a disconnect, a logout, or both.
         */
        private CancelOnDisconnectType cancelOnDisconnectType;
        /**
         * The character each type is sent as, for a venue whose values differ from the defaults.
         */
        private Map<CancelOnDisconnectType, Character> cancelOnDisconnectTypeFieldCodes;
        /**
         * The tag the type is sent in - there is no standard one, so each venue names its own.
         */
        private Integer cancelOnDisconnectTypeFieldCode;
        /**
         * The tag the timeout window is sent in.
         */
        private Integer codTimeoutWindowFieldCode;
        /**
         * How long the counterparty should wait before cancelling, for a venue that supports a grace period.
         */
        private Duration codTimeoutWindow;
        /**
         * The unit the window is expressed in, which some venues specify as part of the field.
         */
        private TimeUnit codTimeoutWindowScale;
    }
}
