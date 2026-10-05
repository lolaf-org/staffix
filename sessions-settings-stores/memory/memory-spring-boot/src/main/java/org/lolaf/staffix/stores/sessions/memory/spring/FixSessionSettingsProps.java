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
 * One session's settings as properties. Anything left unset takes the store instance's defaults, then the engine's.
 */
@Data
@ConfigurationPropertiesSource
public class FixSessionSettingsProps {

    /**
     * The session's identity on the wire: FIX version and CompIDs. Required, and what an inbound Logon is
     * matched against.
     */
    @NestedConfigurationProperty
    private FixSessionIdProps fixSessionId;

    /**
     * Whether this session is an INITIATOR or an ACCEPTOR. Required.
     */
    private FixSession.FixSessionType type;

    /**
     * HeartBtInt(108): how often a quiet session proves it is alive, and how long it waits before asking.
     */
    @NestedConfigurationProperty
    private HeartbeatProps heartbeat;

    /**
     * The precision SendingTime(52) is written to. Microseconds by default; a counterparty that cannot parse
     * sub-second digits needs SECONDS.
     */
    private TimeUnit sendingTimeAccuracy;

    /**
     * Settings the application itself declares a need for, passed through untouched.
     */
    private Map<String, String> applicationSessionSettings = new LinkedHashMap<>();

    /**
     * Whether the Logon lists the message types this session sends and accepts, in MsgTypeGrp(384). Off by
     * default: it makes the Logon considerably larger and few counterparties read it.
     */
    private boolean advertiseMsgTypeGrpOnLogon;
    /**
     * Whether the Logon names this engine and its version. Off by default: some venues record it, most ignore
     * it.
     */
    private boolean advertiseEngineOnLogon;
    /**
     * Whether the Logon names the application and its version, as distinct from the engine's.
     */
    private boolean advertiseApplicationOnLogon;
    /**
     * Whether the Logon carries NextExpectedMsgSeqNum(789), so a sequence gap is settled by the Logon exchange itself
     * rather than by a ResendRequest afterwards (section 4.4.1). On by default; turn it off for a counterparty that
     * rejects the field.
     */
    private Boolean enabledLogonNextExpectedMsgSeqNum;

    /**
     * Addresses an acceptor will accept this session from. Empty means anywhere. Checked before the Logon
     * is processed, so a connection from elsewhere is dropped without the session ever starting.
     */
    private List<String> allowedAddresses = new ArrayList<>();

    /**
     * Whether sequence numbers restart at 1 on logon. True resets on every logon, false refuses a counterparty's reset
     * with a Logout (section 4.4.3), and unset, the default, follows whatever the counterparty asks for.
     */
    private Boolean resetSeqNumOnLogon;

    /**
     * The state the session holds itself in. LOGGED_IN by default, so it logs on and stays on; LOGGED_OUT takes a
     * session out of service without removing its configuration.
     */
    private FixSessionState desiredSessionState;

    /**
     * How long a Logon or Logout may go unanswered before the connection is dropped. 10 seconds by default.
     */
    private Duration logInOrOutResponseTimeout;
    /**
     * How long a ResendRequest(35=2) this session sent may go without progress before it is repeated, then given up
     * on with a logout. Without it, an answer lost to a garbled message stalls the session forever while heartbeats
     * keep flowing. Measured from the last message that advanced the recovery, so a slow but progressing resend never
     * trips it. 30 seconds by default; 0 waits forever.
     */
    private Duration resendRequestResponseTimeout;
    /**
     * How many messages received ahead of a sequence gap are kept until the gap is filled, as section 4.5 requires.
     * Past it the session logs out, rather than grow the queue for a peer that never answers the ResendRequest.
     * 10000 by default; 0 removes the limit, not the queueing.
     */
    private Integer maxOutOfSequenceMessagesQueued;
    /**
     * How many application messages are held back while a resend this session asked for is under way, so both sides
     * are in sync before new traffic (section 4.3.11). Past it, a send fails through its callback rather than being
     * held. 1000 by default; 0 holds nothing and sends straight through.
     */
    private Integer maxOutgoingMessagesHeldDuringRecovery;
    /**
     * How many messages at most are replayed for one ResendRequest(35=2) from the peer, which otherwise decides how
     * much work this engine does. The rest of the range is gap filled, which section 4.8.5 allows, so the peer still
     * ends up in sync. 10000 by default; 0 removes the limit.
     */
    private Integer maxMessagesResentPerRequest;
    /**
     * How EndSeqNo(16) is filled in on the ResendRequests this session sends: CLOSED, the default, asks for exactly
     * the missing messages, OPEN_ENDED for everything from BeginSeqNo(7) on. Both are valid (section 4.8.2); some
     * peers only answer one. QuickFIX/J's default, ClosedResendInterval=N, is OPEN_ENDED.
     */
    private ResendRequestRange resendRequestRange;

    /**
     * Instance id of the FIX application factory this session uses.
     */
    private String fixApplicationFactoryInstanceId;
    /**
     * Instance id of the FixApplication this session is bound to, within the selected application factory.
     */
    private String fixApplicationInstanceId;
    /**
     * Instance id of the FIX message store this session uses.
     */
    private String fixMessageStoreInstanceId;
    /**
     * Instance id of the FIX message logger this session uses.
     */
    private String fixMessageLoggerInstanceId;

    /**
     * The plugin instance each plugin type uses for this session, keyed by the plugin's class name, for example
     * org.lolaf.staffix.tracing.otlp.OtelTracing.
     */
    private Map<String, String> fixSessionPluginsInstanceIds = new LinkedHashMap<>();

    /**
     * Whether the session's own encoders encode into direct byte buffers. True by default.
     */
    private Boolean messageEncodersDirectByteBuffers;
    /**
     * Whether encoders borrowed from a FixMessageEncodersPool encode into direct byte buffers. True by default.
     */
    private Boolean pooledMessageEncodersDirectByteBuffers;
    /**
     * Whether removing these settings from their store disconnects the live session. On by default. Off makes the
     * removal a configuration change only: the session keeps running until it drops on its own, which is what a venue
     * asking you to stop reconnecting after the trading day needs.
     */
    private Boolean disconnectOnRemove;
    /**
     * Whether updating these settings in their store restarts the live session, so the change takes effect now. On by
     * default, since an update that never reaches the running session is the worse surprise. Off, the new settings
     * apply the next time the session connects. Read from the settings the session is running under, not the new
     * ones.
     */
    private Boolean restartLiveSessionOnUpdate;

    /**
     * What this session checks on every inbound message. Each check costs something on the message path, which
     * is why most are off.
     */
    @NestedConfigurationProperty
    private ValidationProps validation;

    /**
     * Whether the Logon sets TestMessageIndicator(464), for FIX 4.3 and later. Off by default.
     */
    private Boolean testingMode;

    /**
     * How long a disconnect waits for messages already queued to be sent before the socket is closed. 2 seconds by
     * default.
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
         * Whether the CheckSum(10) of each received message is verified. On by default.
         */
        private Boolean validateChecksum;
        /**
         * Whether a received message with an empty field is rejected. On by default.
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
         * Accepts user defined tags, the ones the standard does not own, that the data dictionary does not define. Off
         * by default.
         */
        private Boolean allowUserDefinedFields;
        /**
         * Accepts standard tags the data dictionary does not define. That is how a session on an older dictionary takes
         * tags a later FIX version has allocated, from 40000 up. Off by default.
         */
        private Boolean allowUnknownFields;
        /**
         * Whether a received message missing a field the data dictionary requires is rejected. Off by default, since it
         * costs a little on every message.
         */
        private Boolean validateRequiredFields;
        /**
         * Accepts tags the data dictionary defines but not for the received message type. Turning it off rejects them
         * with TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE, at a slight cost in performance.
         */
        private Boolean allowUndefinedTagsForMessage;
        /**
         * Whether SenderCompID(49) and TargetCompID(56) of each received message must match the session's. Off by
         * default, since it costs a little on every message.
         */
        private Boolean validateCompId;
        /**
         * CompIDs the peer may send on behalf of, in OnBehalfOfCompID(115), for third party routing (section 6.2). A
         * message with another value is rejected, and the session stays up. Unset, the default, the field is not
         * checked.
         */
        private Set<String> expectedOnBehalfOfCompIds;
        /**
         * CompIDs the peer may deliver to, in DeliverToCompID(128). Works as expected-on-behalf-of-comp-ids.
         */
        private Set<String> expectedDeliverToCompIds;
        /**
         * Whether a received message whose BeginString(8) does not match the session's FIX version ends the session
         * with a Logout. Off by default, since it costs a little on every message.
         */
        private Boolean validateBeginString;
        /**
         * Whether the garbled message checks of section 4.5.2 that cost extra are run: BeginString(8), BodyLength(9)
         * and MsgType(35) out of place, or an unknown BeginString(8). A wrong BodyLength(9) is always caught. A garbled
         * message is ignored, so the next one shows as a sequence gap and drives the usual recovery. Off by default.
         */
        private Boolean detectGarbledMessages;
        /**
         * How far a received SendingTime(52) may be from this session's clock, either way, before the message is
         * rejected and the session logged out (section 4.2.3). Unset, the default, the check is off and SendingTime is
         * not even parsed.
         */
        private Duration maxSendingTime;
        /**
         * The largest message, in bytes, this session accepts. When set it is advertised in the Logon's
         * MaxMessageSize(383) and larger inbound messages are rejected. Unset by default.
         */
        private Integer maxMessageSize;
        /**
         * The smallest MaxMessageSize(383) the peer must advertise on its Logon, i.e. the largest message this session
         * may need to send it. A peer advertising less is logged out. Unset by default.
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
         * named here must not be covered by session-schedules.
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
             * Required alongside sequence-reset-time: section 4.4.2 has the counterparties agree it between
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
