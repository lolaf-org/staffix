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

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.api.version.FixVersion;
import org.lolaf.staffix.api.version.FixtVersion;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toMap;

/**
 * The reverse, for writing a session back out - what lets a session added at runtime be persisted as a file.
 */
@UtilityClass
public class ToYamlFixSessionSettingsTransformer {

    private static <T> void mergeIfNeeded(Supplier<T> source, Function<T, ?> target) {
        T value = source.get();
        if (value != null) {
            target.apply(value);
        }
    }

    public static YamlFixSessionSettings toYamlFixSessionSettings(FixSessionSettings settings) {
        YamlFixSessionSettings.YamlFixSessionSettingsBuilder builder = YamlFixSessionSettings.builder();
        mergeFixSessionId(settings, builder);
        builder.fixSessionType(settings.getFixSessionType());
        builder.dictionaryId(settings.getDictionaryId());
        mergeHeartbeatInterval(settings, builder);
        mergeIfNeeded(settings::getSendingTimeAccuracy, builder::sendingTimeAccuracy);

        // Convert fixApplicationSessionSettings from Map<FixApplicationSessionSettingDescriptor, String> to Map<String, String>
        if (settings.getFixApplicationSessionSettings() != null && !settings.getFixApplicationSessionSettings().isEmpty()) {
            Map<String, String> yamlAppSettings = settings.getFixApplicationSessionSettings().entrySet().stream()
                    .collect(toMap(e -> e.getKey().getId(), Map.Entry::getValue));
            builder.fixApplicationSessionSettings(yamlAppSettings);
        }

        mergeIfNeeded(settings::isAdvertiseMsgTypeGrpOnLogon, builder::advertiseMsgTypeGrpOnLogon);
        mergeIfNeeded(settings::isAdvertiseEngineOnLogon, builder::advertiseEngineOnLogon);
        mergeIfNeeded(settings::isAdvertiseApplicationOnLogon, builder::advertiseApplicationOnLogon);
        mergeIfNeeded(settings::isEnabledLogonNextExpectedMsgSeqNum, builder::enabledLogonNextExpectedMsgSeqNum);
        if (settings.getAllowedAddresses() != null) {
            builder.allowedAddresses(settings.getAllowedAddresses());
        }
        // allowedCertificates are intentionally NOT serialized for the time being: a parsed Certificate cannot be
        // reverse-mapped to its originating file path/type (that metadata is not retained on the Certificate object).
        // Deserialization is supported; see YamlFixSessionSettings#allowedCertificates.

        mergeIfNeeded(settings::getResetSeqNumOnLogon, builder::resetSeqNumOnLogon);
        mergeIfNeeded(settings::getDesiredSessionState, builder::desiredSessionState);
        mergeIfNeeded(settings::getLogInOrOutResponseTimeout, builder::logInOrOutResponseTimeout);
        mergeIfNeeded(settings::getResendRequestResponseTimeout, builder::resendRequestResponseTimeout);
        mergeIfNeeded(settings::getMaxOutOfSequenceMessagesQueued, builder::maxOutOfSequenceMessagesQueued);
        mergeIfNeeded(settings::getMaxOutgoingMessagesHeldDuringRecovery, builder::maxOutgoingMessagesHeldDuringRecovery);
        mergeIfNeeded(settings::getMaxMessagesResentPerRequest, builder::maxMessagesResentPerRequest);
        mergeIfNeeded(settings::getResendRequestRange, builder::resendRequestRange);
        mergeIfNeeded(settings::getFixApplicationFactoryInstanceId, builder::fixApplicationFactoryInstanceId);
        mergeIfNeeded(settings::getFixApplicationInstanceId, builder::fixApplicationInstanceId);
        mergeIfNeeded(settings::getFixMessageStoreInstanceId, builder::fixMessageStoreInstanceId);
        mergeIfNeeded(settings::getFixMessageLoggerInstanceId, builder::fixMessageLoggerInstanceId);
        if (settings.getFixSessionPluginsInstanceIds() != null && !settings.getFixSessionPluginsInstanceIds().isEmpty()) {
            builder.fixSessionPluginsInstanceIds(settings.getFixSessionPluginsInstanceIds().entrySet().stream()
                    .collect(toMap(e -> e.getKey().getName(), Map.Entry::getValue)));
        }
        mergeValidationSettings(settings, builder);
        mergeIfNeeded(settings::isTestingMode, builder::testingMode);
        mergeIfNeeded(settings::getDisconnectMessagesFlushDeadline, builder::disconnectMessagesFlushDeadline);
        mergeCancelOnDisconnectSettings(settings, builder);
        mergeRttMeasurementSettings(settings, builder);
        mergeSessionScheduleSettings(settings, builder);
        mergeIfNeeded(settings::isMessageEncodersDirectByteBuffers, builder::messageEncodersDirectByteBuffers);
        mergeIfNeeded(settings::isPooledMessageEncodersDirectByteBuffers, builder::pooledMessageEncodersDirectByteBuffers);
        mergeIfNeeded(settings::isDisconnectOnRemove, builder::disconnectOnRemove);
        mergeIfNeeded(settings::isRestartLiveSessionOnUpdate, builder::restartLiveSessionOnUpdate);

        return builder.build();
    }

    private static void mergeFixSessionId(FixSessionSettings settings, YamlFixSessionSettings.YamlFixSessionSettingsBuilder builder) {
        YamlFixSessionSettings.FixSessionId.FixSessionIdBuilder yamlFixSessionId = YamlFixSessionSettings.FixSessionId.builder();
        FixSessionId fixSessionId = settings.getFixSessionId();
        FixVersion fixVersion = fixSessionId.getFixVersion();
        if (fixSessionId.getFixVersion().equals(FixtVersion.FIXT_11)) {
            fixVersion = FixApplVerID.getFixVersionForCode(fixSessionId.getDefaultApplVerID().getCode());
        }
        yamlFixSessionId.id(fixSessionId.getId());
        yamlFixSessionId.fixVersion(fixVersion.toString());
        yamlFixSessionId.fixTSession(fixSessionId.getFixVersion().equals(FixtVersion.FIXT_11));
        mergeIfNeeded(fixSessionId::getGroup, yamlFixSessionId::group);
        mergeIfNeeded(() -> getValueOrNull(fixSessionId.getSenderCompID()), yamlFixSessionId::senderCompID);
        mergeIfNeeded(() -> getValueOrNull(fixSessionId.getSenderSubID()), yamlFixSessionId::senderSubID);
        mergeIfNeeded(() -> getValueOrNull(fixSessionId.getSenderLocationID()), yamlFixSessionId::senderLocationID);
        mergeIfNeeded(() -> getValueOrNull(fixSessionId.getTargetCompID()), yamlFixSessionId::targetCompID);
        mergeIfNeeded(() -> getValueOrNull(fixSessionId.getTargetSubID()), yamlFixSessionId::targetSubID);
        mergeIfNeeded(() -> getValueOrNull(fixSessionId.getTargetLocationID()), yamlFixSessionId::targetLocationID);
        builder.fixSessionId(yamlFixSessionId.build());
    }

    private static String getValueOrNull(FixSessionId.FieldAndValuePair pair) {
        return pair != null ? pair.getValue() : null;
    }

    private static void mergeHeartbeatInterval(FixSessionSettings settings, YamlFixSessionSettings.YamlFixSessionSettingsBuilder builder) {
        if (settings.getHeartBeatInterval() != null) {
            YamlFixSessionSettings.HeartbeatInterval.HeartbeatIntervalBuilder hbb = YamlFixSessionSettings.HeartbeatInterval.builder();
            FixSessionSettings.HeartbeatInterval hbi = settings.getHeartBeatInterval();
            mergeIfNeeded(hbi::getInitiatorInterval, hbb::initiatorInterval);
            mergeIfNeeded(hbi::getAcceptorLowerBoundInterval, hbb::acceptorLowerBoundInterval);
            mergeIfNeeded(hbi::getAcceptorUpperBoundInterval, hbb::acceptorUpperBoundInterval);
            builder.heartBeatInterval(hbb.build());
        }
    }

    private static void mergeValidationSettings(FixSessionSettings settings, YamlFixSessionSettings.YamlFixSessionSettingsBuilder builder) {
        if (settings.getValidationSettings() != null) {
            YamlFixSessionSettings.ValidationSettings.ValidationSettingsBuilder vsb = YamlFixSessionSettings.ValidationSettings.builder();
            FixSessionSettings.ValidationSettings vs = settings.getValidationSettings();
            mergeIfNeeded(vs::isValidateChecksum, vsb::validateChecksum);
            mergeIfNeeded(vs::isValidateFieldsHaveValues, vsb::validateFieldsHaveValues);
            mergeIfNeeded(vs::isAllowUserDefinedFields, vsb::allowUserDefinedFields);
            mergeIfNeeded(vs::isAllowUnknownFields, vsb::allowUnknownFields);
            mergeIfNeeded(vs::isValidateRequiredFields, vsb::validateRequiredFields);
            mergeIfNeeded(vs::isAllowUndefinedTagsForMessage, vsb::allowUndefinedTagsForMessage);
            mergeIfNeeded(vs::isValidateCompId, vsb::validateCompId);
            // not mergeIfNeeded: the settings hold sets while YAML can only take a list
            if (vs.getExpectedOnBehalfOfCompIds() != null) {
                vsb.expectedOnBehalfOfCompIds(new ArrayList<>(vs.getExpectedOnBehalfOfCompIds()));
            }
            if (vs.getExpectedDeliverToCompIds() != null) {
                vsb.expectedDeliverToCompIds(new ArrayList<>(vs.getExpectedDeliverToCompIds()));
            }
            mergeIfNeeded(vs::isValidateBeginString, vsb::validateBeginString);
            mergeIfNeeded(vs::isDetectGarbledMessages, vsb::detectGarbledMessages);
            mergeIfNeeded(vs::getMaxSendingTime, vsb::maxSendingTime);
            mergeIfNeeded(vs::getMaxMessageSize, vsb::maxMessageSize);
            mergeIfNeeded(vs::getRequiredPeerMaxMessageSize, vsb::requiredPeerMaxMessageSize);
            mergeIfNeeded(vs::isValidateFieldsOutOfOrder, vsb::validateFieldsOutOfOrder);
            mergeIfNeeded(vs::isValidateDuplicateTags, vsb::validateDuplicateTags);
            builder.validationSettings(vsb.build());
        }
    }

    private static void mergeCancelOnDisconnectSettings(FixSessionSettings settings, YamlFixSessionSettings.YamlFixSessionSettingsBuilder builder) {
        if (settings.getCancelOnDisconnectSettings() != null) {
            YamlFixSessionSettings.CancelOnDisconnectSettings.CancelOnDisconnectSettingsBuilder codBuilder = YamlFixSessionSettings.CancelOnDisconnectSettings.builder();
            FixSessionSettings.CancelOnDisconnectSettings cod = settings.getCancelOnDisconnectSettings();
            mergeIfNeeded(cod::isEnabled, codBuilder::enabled);
            mergeIfNeeded(cod::getCancelOnDisconnectType, codBuilder::cancelOnDisconnectType);
            mergeIfNeeded(cod::getCancelOnDisconnectTypeFieldCodes, codBuilder::cancelOnDisconnectTypeFieldCodes);
            mergeIfNeeded(cod::getCancelOnDisconnectTypeFieldCode, codBuilder::cancelOnDisconnectTypeFieldCode);
            mergeIfNeeded(cod::getCodTimeoutWindowFieldCode, codBuilder::codTimeoutWindowFieldCode);
            mergeIfNeeded(cod::getCodTimeoutWindow, codBuilder::codTimeoutWindow);
            mergeIfNeeded(cod::getCodTimeoutWindowScale, codBuilder::codTimeoutWindowScale);
            builder.cancelOnDisconnectSettings(codBuilder.build());
        }
    }

    private static void mergeRttMeasurementSettings(FixSessionSettings settings, YamlFixSessionSettings.YamlFixSessionSettingsBuilder builder) {
        if (settings.getRttMeasurementSettings() != null) {
            YamlFixSessionSettings.RttMeasurementSettings.RttMeasurementSettingsBuilder rb = YamlFixSessionSettings.RttMeasurementSettings.builder();
            FixSessionSettings.RttMeasurementSettings rtt = settings.getRttMeasurementSettings();
            mergeIfNeeded(rtt::getProbeInterval, rb::probeInterval);
            mergeIfNeeded(rtt::getEmaTimeWindow, rb::emaTimeWindow);
            mergeIfNeeded(rtt::getMaxAcceptedRtt, rb::maxAcceptedRtt);
            mergeIfNeeded(rtt::getProbeTestReqIdPrefix, rb::probeTestReqIdPrefix);
            mergeIfNeeded(rtt::getSendingTimeToWireDelay, rb::sendingTimeToWireDelay);
            builder.rttMeasurementSettings(rb.build());
        }
    }

    private static void mergeSessionScheduleSettings(FixSessionSettings settings, YamlFixSessionSettings.YamlFixSessionSettingsBuilder builder) {
        if (settings.getSessionScheduleSettings() != null) {
            YamlFixSessionSettings.SessionScheduleSettings.SessionScheduleSettingsBuilder ssb = YamlFixSessionSettings.SessionScheduleSettings.builder();
            FixSessionSettings.SessionScheduleSettings sss = settings.getSessionScheduleSettings();
            mergeIfNeeded(sss::getOutsideSessionTimePreTriggerDelay, ssb::outsideSessionTimePreTriggerDelay);
            mergeIfNeeded(sss::getTimeZone, ssb::timeZone);
            mergeIfNeeded(sss::getWithinSessionTimeCheckInterval, ssb::withinSessionCheckInterval);
            if (sss.getSessionSchedules() != null) {
                sss.getSessionSchedules().forEach(entry -> {
                    YamlFixSessionSettings.SessionScheduleSettings.ScheduleEntry yamlEntry = YamlFixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                            .startDay(entry.getStartDay())
                            .endDay(entry.getEndDay())
                            .startTime(entry.getStartTime())
                            .endTime(entry.getEndTime())
                            .build();
                    ssb.sessionSchedule(yamlEntry);
                });
            }
            if (sss.getNonStopSchedules() != null) {
                sss.getNonStopSchedules().forEach(nonStop -> ssb.nonStopSchedule(
                        YamlFixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry.builder()
                                .dayOfWeek(nonStop.getDayOfWeek())
                                .sequenceResetTime(nonStop.getSequenceResetTime())
                                .initiatesReset(nonStop.getInitiatesReset())
                                .build()));
            }
            builder.sessionScheduleSettings(ssb.build());
        }
    }

}