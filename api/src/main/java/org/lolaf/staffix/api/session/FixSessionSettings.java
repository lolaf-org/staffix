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
     * The session's identity on the wire: FIX version and CompIDs. Required, and what an inbound Logon is
     * matched against.
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
     * Settings the application declares it needs, passed to it untouched; see
     * {@link FixApplication#getRequiredFixSessionSettings()}.
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
     * Whether the Logon names this engine and its version. Off by default: some venues record it, most ignore
     * it.
     */
    @Builder.Default
    private boolean advertiseEngineOnLogon = false;
    /**
     * Whether the Logon names the application and its version, as distinct from the engine's.
     */
    @Builder.Default
    private boolean advertiseApplicationOnLogon = false;
    /**
     * Whether the Logon carries NextExpectedMsgSeqNum(789), so a sequence gap is settled by the Logon exchange itself
     * rather than by a ResendRequest afterwards (section 4.4.1). On by default; turn it off for a counterparty that
     * rejects the field.
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
     * Whether sequence numbers restart at 1 on logon. True resets on every logon, false refuses a counterparty's reset
     * with a Logout (section 4.4.3), and null, the default, follows whatever the counterparty asks for.
     */
    @Builder.Default
    private Boolean resetSeqNumOnLogon = null;
    /**
     * The state the session holds itself in. {@link FixSessionState#LOGGED_IN} by default, so it logs on and stays on;
     * {@link FixSessionState#LOGGED_OUT} takes a session out of service without removing its configuration.
     */
    @Builder.Default
    private FixSessionState desiredSessionState = FixSessionState.LOGGED_IN;

    /**
     * How long a Logon or Logout may go unanswered before the connection is dropped.
     */
    @Builder.Default
    private Duration logInOrOutResponseTimeout = Duration.ofSeconds(10);

    /**
     * How long a ResendRequest(35=2) this session sent may go without progress before it is repeated, then given up
     * on with a logout. Without it, an answer lost to a garbled message stalls the session forever while heartbeats
     * keep flowing. Measured from the last message that advanced the recovery, so a slow but progressing resend never
     * trips it. {@link Duration#ZERO} waits forever.
     */
    @Builder.Default
    private Duration resendRequestResponseTimeout = Duration.ofSeconds(30);

    /**
     * How many messages received ahead of a sequence gap are kept until the gap is filled, as section 4.5 requires.
     * Past it the session logs out, rather than grow the queue for a peer that never answers the ResendRequest. 0
     * removes the limit, not the queueing.
     */
    @Builder.Default
    private int maxOutOfSequenceMessagesQueued = 10_000;

    /**
     * How many application messages are held back while a resend this session asked for is under way, so both sides
     * are in sync before new traffic (section 4.3.11). Past it, a send fails through its
     * {@link FixSession.MessageSendOperationCallback} rather than being held. 0 holds nothing and sends straight
     * through.
     */
    @Builder.Default
    private int maxOutgoingMessagesHeldDuringRecovery = 1_000;

    /**
     * How many messages at most are replayed for one ResendRequest(35=2) from the peer, which otherwise decides how
     * much work this engine does. The rest of the range is gap filled, which section 4.8.5 allows, so the peer still
     * ends up in sync. 0 removes the limit.
     */
    @Builder.Default
    private int maxMessagesResentPerRequest = 10_000;

    /**
     * How EndSeqNo(16) is filled in on the ResendRequests this session sends: {@link ResendRequestRange#CLOSED}, the
     * default, asks for exactly the missing messages, {@link ResendRequestRange#OPEN_ENDED} for everything from
     * BeginSeqNo(7) on. Both are valid (section 4.8.2); some peers only answer one. QuickFIX/J's default,
     * ClosedResendInterval=N, is OPEN_ENDED.
     */
    @Builder.Default
    private ResendRequestRange resendRequestRange = ResendRequestRange.CLOSED;

    /**
     * Instance id of the application factory this session uses; see
     * {@link FixApplicationFactorySettings#getInstanceId()}.
     */
    @Builder.Default
    private String fixApplicationFactoryInstanceId = InstanceProvider.DEFAULT_INSTANCE_ID;
    /**
     * Instance id of the message store this session uses; see {@link FixMessagesStoreSettings#getInstanceId()}.
     */
    @Builder.Default
    private String fixMessageStoreInstanceId = InstanceProvider.DEFAULT_INSTANCE_ID;
    /**
     * Instance id of the message logger this session uses; see {@link FixMessagesLoggerSettings#getInstanceId()}.
     */
    @Builder.Default
    private String fixMessageLoggerInstanceId = InstanceProvider.DEFAULT_INSTANCE_ID;

    /**
     * Instance id of the application this session is bound to, within the selected factory; see
     * {@link FixApplicationFactory#getInstance(String)}.
     */
    @Builder.Default
    private String fixApplicationInstanceId = InstanceProvider.DEFAULT_INSTANCE_ID;

    /**
     * The plugin instance each plugin type uses for this session, keyed by the plugin class; see
     * {@link FixEngineBuilder#getFixSessionsPlugins()}.
     */
    @Singular
    private Map<Class<? extends FixSessionsPlugin<?>>, String> fixSessionPluginsInstanceIds;

    /**
     * What this session checks on every inbound message. Each check costs something on the message path, which
     * is why most are off.
     */
    @Builder.Default
    private ValidationSettings validationSettings = ValidationSettings.builder().build();

    /**
     * Whether the Logon sets TestMessageIndicator(464), for FIX 4.3 and later.
     */
    @Builder.Default
    private boolean testingMode = false;

    /**
     * How long a disconnect waits for messages already queued to be sent before the socket is closed.
     */
    @Builder.Default
    private Duration disconnectMessagesFlushDeadline = Duration.ofSeconds(2);

    /**
     * When the session is expected to be up, in the venue's own time zone.
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
     * What the counterparty should do with resting orders when this session goes away. An initiator sends it on its
     * Logon; an acceptor checks the Logon it receives against it.
     */
    @Builder.Default
    private CancelOnDisconnectSettings cancelOnDisconnectSettings = CancelOnDisconnectSettings.builder().build();

    /**
     * Whether removing these settings from their {@link FixSessionsSettingsStore} disconnects the live session. On
     * by default. Off makes the removal a configuration change only: the session keeps running until it drops on its
     * own, which is what a venue asking you to stop reconnecting after the trading day needs.
     */
    @Builder.Default
    private boolean disconnectOnRemove = true;

    /**
     * Whether updating these settings in their {@link FixSessionsSettingsStore} restarts the live session, so the
     * change takes effect now. On by default, since an update that never reaches the running session is the worse
     * surprise. Off, the new settings apply the next time the session connects. Read from the settings the session is
     * running under, not the new ones.
     */
    @Builder.Default
    private boolean restartLiveSessionOnUpdate = true;

    /**
     * Whether the session's own encoders encode into direct byte buffers.
     */
    @Builder.Default
    private boolean messageEncodersDirectByteBuffers = true;

    /**
     * Whether encoders borrowed from a {@link org.lolaf.staffix.api.codec.FixMessageEncodersPool} encode into direct
     * byte buffers.
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
         * Whether the CheckSum(10) of each received message is verified.
         */
        @Builder.Default
        private boolean validateChecksum = true;
        /**
         * Whether a received message with an empty field is rejected.
         */
        @Builder.Default
        private boolean validateFieldsHaveValues = true;
        /**
         * Accepts user defined tags, the ones the standard does not own, that the data dictionary does not define; see
         * {@link org.lolaf.staffix.api.fields.FixField#isUserDefined(int)}.
         */
        @Builder.Default
        private boolean allowUserDefinedFields = false;
        /**
         * Accepts standard tags the data dictionary does not define. That is how a session on an older dictionary takes
         * tags a later FIX version has allocated, from 40000 up, rather than through {@link #allowUserDefinedFields}.
         */
        @Builder.Default
        private boolean allowUnknownFields = false;
        /**
         * Whether a received message missing a field the data dictionary requires is rejected. Off by default, since it
         * costs a little on every message.
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
         * Accepts tags the data dictionary defines but not for the received message type. Turning it off rejects them
         * with TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE, at a slight cost in performance.
         */
        @Builder.Default
        private boolean allowUndefinedTagsForMessage = true;
        /**
         * Whether SenderCompID(49) and TargetCompID(56) of each received message must match the session's. Off by
         * default, since it costs a little on every message.
         */
        @Builder.Default
        private boolean validateCompId = false;

        /**
         * CompIDs the peer may send on behalf of, in OnBehalfOfCompID(115), for third party routing (section 6.2). A
         * message with another value is rejected with SessionRejectReason(373) 9, and the session stays up. Null or
         * empty, the default, the field is not checked.
         */
        @Builder.Default
        private Set<String> expectedOnBehalfOfCompIds = null;

        /**
         * CompIDs the peer may deliver to, in DeliverToCompID(128). Works as {@link #expectedOnBehalfOfCompIds}.
         */
        @Builder.Default
        private Set<String> expectedDeliverToCompIds = null;

        /**
         * Whether a received message whose BeginString(8) does not match the session's FIX version ends the session
         * with a Logout. Off by default, since it costs a little on every message.
         */
        @Builder.Default
        private boolean validateBeginString = false;

        /**
         * Whether the garbled message checks of section 4.5.2 that cost extra are run: BeginString(8), BodyLength(9)
         * and MsgType(35) out of place, or an unknown BeginString(8). A wrong BodyLength(9) is always caught. A garbled
         * message is ignored, so the next one shows as a sequence gap and drives the usual recovery. Off by default.
         */
        @Builder.Default
        private boolean detectGarbledMessages = false;

        /**
         * How far a received SendingTime(52) may be from this session's clock, either way, before the message is
         * rejected and the session logged out (section 4.2.3). Null, the default, turns the check off and leaves
         * SendingTime unparsed.
         */
        private Duration maxSendingTime;

        /**
         * The largest message, in bytes, this session accepts. When set it is advertised in the Logon's
         * MaxMessageSize(383) and larger inbound messages are rejected. Null, the default, sets no limit.
         */
        @Builder.Default
        private Integer maxMessageSize = null;

        /**
         * The smallest MaxMessageSize(383) the peer must advertise on its Logon, i.e. the largest message this session
         * may need to send it. A peer advertising less is logged out. Null, the default, requires nothing.
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
         * outgoing byte buffer and the moment those bytes actually leave the wire: encode tail,
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

    /**
     * When a session is expected to be up: trading windows or non-stop days, in the venue's time zone.
     */
    @Getter
    @Builder(toBuilder = true)
    @EqualsAndHashCode
    public static class SessionScheduleSettings {

        /**
         * How long before the close {@link FixApplication#onPreOutsideSessionTime(FixSession, Duration)} is called, so
         * the application can wind down rather than be cut off.
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

        /**
         * One trading window, from a day and time to a later day and time in the schedule's zone. It may span midnight,
         * or the weekend.
         */
        @Value
        @Builder
        public static class ScheduleEntry {

            /**
             * The day the window opens on.
             */
            @NonNull
            DayOfWeek startDay;
            /**
             * The day it closes on.
             */
            @NonNull
            DayOfWeek endDay;
            /**
             * The open.
             */
            @NonNull
            LocalTime startTime;
            /**
             * The close.
             */
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