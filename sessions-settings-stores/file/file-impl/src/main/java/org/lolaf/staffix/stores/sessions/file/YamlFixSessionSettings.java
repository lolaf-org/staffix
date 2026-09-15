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
package org.lolaf.staffix.stores.sessions.file;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;
import org.lolaf.staffix.api.session.*;

import java.net.InetAddress;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * A session as a YAML file states it, and the model the JSON schema is generated from.
 *
 * <p>Separate from {@code FixSessionSettings} on purpose: this is the shape a file may take, including the
 * placeholders and the loose forms a human writes, before any of it is resolved.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YamlFixSessionSettings {

    @Valid
    @NotNull
    private FixSessionId fixSessionId;
    @NotNull
    private FixSession.FixSessionType fixSessionType;
    private String dictionaryId;
    @Valid
    private HeartbeatInterval heartBeatInterval;
    private TimeUnit sendingTimeAccuracy;

    private Map<String, String> fixApplicationSessionSettings;
    private boolean advertiseMsgTypeGrpOnLogon;
    private boolean advertiseEngineOnLogon;
    private boolean advertiseApplicationOnLogon;
    private Boolean enabledLogonNextExpectedMsgSeqNum;

    private List<InetAddress> allowedAddresses;
    /**
     * Note: allowedCertificates are only supported on deserialization (read from disk): the source file path and type
     * are loaded into the resulting {@link FixSessionSettings#getAllowedCertificates()}. They are NOT covered on
     * serialization (write to disk) for the time being, because a parsed {@link java.security.cert.Certificate} cannot
     * be reverse-mapped to its originating file path/type. A session written back out therefore loses its certificates.
     */
    private List<Certificate> allowedCertificates;
    private Boolean resetSeqNumOnLogon;
    private FixSessionState desiredSessionState;
    private Duration logInOrOutResponseTimeout;
    private Duration resendRequestResponseTimeout;
    private Integer maxOutOfSequenceMessagesQueued;
    private Integer maxOutgoingMessagesHeldDuringRecovery;
    private Integer maxMessagesResentPerRequest;
    private ResendRequestRange resendRequestRange;
    private String fixApplicationFactoryInstanceId;
    private String fixApplicationInstanceId;
    private String fixMessageStoreInstanceId;
    private String fixMessageLoggerInstanceId;
    private Map<String, String> fixSessionPluginsInstanceIds;
    @Valid
    private ValidationSettings validationSettings;
    private Boolean testingMode;
    private Duration disconnectMessagesFlushDeadline;
    @Valid
    private CancelOnDisconnectSettings cancelOnDisconnectSettings;
    @Valid
    private RttMeasurementSettings rttMeasurementSettings;
    @Valid
    private SessionScheduleSettings sessionScheduleSettings;
    private boolean messageEncodersDirectByteBuffers;
    private boolean pooledMessageEncodersDirectByteBuffers;
    private Boolean disconnectOnRemove;
    private Boolean restartLiveSessionOnUpdate;


    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FixSessionId {

        @NotBlank
        private String id;
        @NotBlank
        private String fixVersion;
        private boolean fixTSession;
        private String group;
        @NotBlank
        private String senderCompID;
        private String senderSubID;
        private String senderLocationID;
        @NotBlank
        private String targetCompID;
        private String targetSubID;
        private String targetLocationID;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HeartbeatInterval {

        private Duration initiatorInterval;
        private Duration acceptorLowerBoundInterval;
        private Duration acceptorUpperBoundInterval;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationSettings {

        private Boolean validateChecksum;
        private Boolean validateFieldsHaveValues;
        private Boolean allowUserDefinedFields;
        private Boolean allowUnknownFields;
        private Boolean validateRequiredFields;
        private Boolean allowUndefinedTagsForMessage;
        private Boolean validateFieldsOutOfOrder;
        private Boolean validateDuplicateTags;
        private Boolean validateCompId;
        // lists rather than sets, YAML having no set notation, converted on the way into the settings
        private List<String> expectedOnBehalfOfCompIds;
        private List<String> expectedDeliverToCompIds;
        private Boolean validateBeginString;
        private Boolean detectGarbledMessages;
        private Duration maxSendingTime;

        @Positive
        private Integer maxMessageSize;

        @Positive
        private Integer requiredPeerMaxMessageSize;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Certificate {

        private String filePath;
        private String type;

    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CancelOnDisconnectSettings {

        boolean enabled;
        CancelOnDisconnectType cancelOnDisconnectType;
        Map<CancelOnDisconnectType, Character> cancelOnDisconnectTypeFieldCodes;
        int cancelOnDisconnectTypeFieldCode;
        int codTimeoutWindowFieldCode;
        Duration codTimeoutWindow;
        TimeUnit codTimeoutWindowScale;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RttMeasurementSettings {

        private Duration probeInterval;
        private Duration emaTimeWindow;
        private Duration maxAcceptedRtt;
        private String probeTestReqIdPrefix;
        private Duration sendingTimeToWireDelay;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionScheduleSettings {

        private Duration outsideSessionTimePreTriggerDelay;
        private TimeZone timeZone;
        private Duration withinSessionCheckInterval;
        @Singular
        private List<@Valid ScheduleEntry> sessionSchedules;
        @Singular
        private List<@Valid NonStopScheduleEntry> nonStopSchedules;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ScheduleEntry {

            @NotNull
            DayOfWeek startDay;
            @NotNull
            DayOfWeek endDay;
            @NotNull
            LocalTime startTime;
            @NotNull
            LocalTime endTime;

        }

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class NonStopScheduleEntry {

            @NotNull
            DayOfWeek dayOfWeek;
            LocalTime sequenceResetTime;
            Boolean initiatesReset;

        }
    }
}