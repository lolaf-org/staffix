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
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.util.LinkedHashSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Turns a {@link YamlFixSessionSettings} into the settings the engine uses, resolving placeholders and applying
 * defaults.
 */
@UtilityClass
public class FromYamlFixSessionSettingsTransformer {

    /**
     * Merges the given default settings into a single session settings, leaving null fields of {@code settings}
     * populated from {@code defaultSettings}. Returns {@code settings} unchanged if {@code defaultSettings} is null.
     */
    public static YamlFixSessionSettings mergeWithDefault(YamlFixSessionSettings defaultSettings, YamlFixSessionSettings settings) {
        if (defaultSettings == null) {
            return settings;
        }
        try {
            return ObjectMerger.merge(defaultSettings, settings);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to merge settings with defaults", e);
        }
    }

    private <T> void mergeIfNeeded(Supplier<T> source, Function<T, ?> target) {
        T value = source.get();
        if (value != null) {
            target.apply(value);
        }
    }

    public static FixSessionSettings toFixSessionSettings(YamlFixSessionSettings settings) {
        FixSessionSettings.FixSessionSettingsBuilder<?, ?> builder = FixSessionSettings.builder();
        mergeFixSessionId(settings, builder);
        mergeIfNeeded(settings::getFixSessionType, builder::fixSessionType);
        mergeIfNeeded(settings::getDictionaryId, builder::dictionaryId);
        mergeHeartbeatIntervalSettings(settings, builder);

        mergeIfNeeded(settings::getSendingTimeAccuracy, builder::sendingTimeAccuracy);
        if (settings.getFixApplicationSessionSettings() != null) {
            settings.getFixApplicationSessionSettings()
                    .forEach((k, v) -> builder.fixApplicationSessionSetting(FixApplicationSessionSettingDescriptor.of(k), v));
        }
        mergeIfNeeded(settings::isAdvertiseMsgTypeGrpOnLogon, builder::advertiseMsgTypeGrpOnLogon);
        mergeIfNeeded(settings::isAdvertiseEngineOnLogon, builder::advertiseEngineOnLogon);
        mergeIfNeeded(settings::isAdvertiseApplicationOnLogon, builder::advertiseApplicationOnLogon);
        mergeIfNeeded(settings::getEnabledLogonNextExpectedMsgSeqNum, builder::enabledLogonNextExpectedMsgSeqNum);
        if (settings.getAllowedAddresses() != null) {
            settings.getAllowedAddresses().forEach(builder::allowedAddress);
        }
        mergeAllowedCertificates(settings, builder);
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
        if (settings.getFixSessionPluginsInstanceIds() != null) {
            settings.getFixSessionPluginsInstanceIds().forEach((k, v) -> {
                try {
                    Class<? extends FixSessionsPlugin<?>> pluginClass = (Class<? extends FixSessionsPlugin<?>>) Class.forName(k);
                    builder.fixSessionPluginsInstanceId(pluginClass, v);
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException(e);
                }
            });
        }
        mergeValidationSettings(settings, builder);
        mergeIfNeeded(settings::getTestingMode, builder::testingMode);
        mergeIfNeeded(settings::getDisconnectMessagesFlushDeadline, builder::disconnectMessagesFlushDeadline);
        mergeCancelOnDisconnectSettings(settings, builder);
        mergeRttMeasurementSettings(settings, builder);
        mergeIfNeeded(settings::isMessageEncodersDirectByteBuffers, builder::messageEncodersDirectByteBuffers);
        mergeIfNeeded(settings::isPooledMessageEncodersDirectByteBuffers, builder::pooledMessageEncodersDirectByteBuffers);
        mergeIfNeeded(settings::getDisconnectOnRemove, builder::disconnectOnRemove);
        mergeIfNeeded(settings::getRestartLiveSessionOnUpdate, builder::restartLiveSessionOnUpdate);
        mergeSessionScheduleSettings(settings, builder);
        return builder.build();
    }

    private static void mergeCancelOnDisconnectSettings(YamlFixSessionSettings settings, FixSessionSettings.FixSessionSettingsBuilder<?, ?> builder) {
        YamlFixSessionSettings.CancelOnDisconnectSettings ycodSettings = settings.getCancelOnDisconnectSettings();
        if (ycodSettings != null) {
            FixSessionSettings.CancelOnDisconnectSettings.CancelOnDisconnectSettingsBuilder<?, ?> codBuilder = FixSessionSettings.CancelOnDisconnectSettings.builder();
            mergeIfNeeded(ycodSettings::isEnabled, codBuilder::enabled);
            mergeIfNeeded(ycodSettings::getCancelOnDisconnectType, codBuilder::cancelOnDisconnectType);
            mergeIfNeeded(ycodSettings::getCancelOnDisconnectTypeFieldCodes, codBuilder::cancelOnDisconnectTypeFieldCodes);
            mergeIfNeeded(ycodSettings::getCancelOnDisconnectTypeFieldCode, codBuilder::cancelOnDisconnectTypeFieldCode);
            mergeIfNeeded(ycodSettings::getCodTimeoutWindowFieldCode, codBuilder::codTimeoutWindowFieldCode);
            mergeIfNeeded(ycodSettings::getCodTimeoutWindow, codBuilder::codTimeoutWindow);
            mergeIfNeeded(ycodSettings::getCodTimeoutWindowScale, codBuilder::codTimeoutWindowScale);
            builder.cancelOnDisconnectSettings(codBuilder.build());
        }
    }

    private static void mergeRttMeasurementSettings(YamlFixSessionSettings settings, FixSessionSettings.FixSessionSettingsBuilder<?, ?> builder) {
        YamlFixSessionSettings.RttMeasurementSettings yrtt = settings.getRttMeasurementSettings();
        if (yrtt != null) {
            FixSessionSettings.RttMeasurementSettings.RttMeasurementSettingsBuilder<?, ?> rb = FixSessionSettings.RttMeasurementSettings.builder();
            mergeIfNeeded(yrtt::getProbeInterval, rb::probeInterval);
            mergeIfNeeded(yrtt::getEmaTimeWindow, rb::emaTimeWindow);
            mergeIfNeeded(yrtt::getMaxAcceptedRtt, rb::maxAcceptedRtt);
            mergeIfNeeded(yrtt::getProbeTestReqIdPrefix, rb::probeTestReqIdPrefix);
            mergeIfNeeded(yrtt::getSendingTimeToWireDelay, rb::sendingTimeToWireDelay);
            builder.rttMeasurementSettings(rb.build());
        }
    }

    private static void mergeFixSessionId(YamlFixSessionSettings settings, FixSessionSettings.FixSessionSettingsBuilder<?, ?> builder) {
        FixRegularVersion fixRegularVersion = FixRegularVersion.fromString(settings.getFixSessionId().getFixVersion()).orElseThrow();
        FixSessionId.FixSessionIdBuilder b = FixSessionId.FixSessionIdBuilder.builder()
                .id(settings.getFixSessionId().getId())
                .group(settings.getFixSessionId().getGroup())
                .senderCompID(settings.getFixSessionId().getSenderCompID())
                .senderSubID(settings.getFixSessionId().getSenderSubID())
                .senderLocationID(settings.getFixSessionId().getSenderLocationID())
                .targetCompID(settings.getFixSessionId().getTargetCompID())
                .targetSubID(settings.getFixSessionId().getTargetSubID())
                .targetLocationID(settings.getFixSessionId().getTargetLocationID())
                .build();

        builder.fixSessionId(settings.getFixSessionId().isFixTSession()
                ? FixSessionId.ofFIXT11(FixApplVerID.forFixVersion(fixRegularVersion), b)
                : FixSessionId.of(fixRegularVersion, b));
    }

    private static void mergeHeartbeatIntervalSettings(YamlFixSessionSettings settings, FixSessionSettings.FixSessionSettingsBuilder<?, ?> builder) {
        if (settings.getHeartBeatInterval() != null) {
            FixSessionSettings.HeartbeatInterval.HeartbeatIntervalBuilder<?, ?> hbb = FixSessionSettings.HeartbeatInterval.builder();
            YamlFixSessionSettings.HeartbeatInterval yhbi = settings.getHeartBeatInterval();
            mergeIfNeeded(yhbi::getInitiatorInterval, hbb::initiatorInterval);
            mergeIfNeeded(yhbi::getAcceptorUpperBoundInterval, hbb::acceptorUpperBoundInterval);
            mergeIfNeeded(yhbi::getAcceptorLowerBoundInterval, hbb::acceptorLowerBoundInterval);
            builder.heartBeatInterval(hbb.build());
        }
    }

    private static void mergeAllowedCertificates(YamlFixSessionSettings settings, FixSessionSettings.FixSessionSettingsBuilder<?, ?> builder) {
        if (settings.getAllowedCertificates() != null) {
            settings.getAllowedCertificates().forEach(cert -> {
                try (InputStream certStream = new FileInputStream(cert.getFilePath())) {
                    CertificateFactory certFactory = CertificateFactory.getInstance(cert.getType());
                    builder.allowedCertificate(certFactory.generateCertificate(certStream));
                } catch (Exception ex) {
                    throw new IllegalArgumentException("Unable to create " + cert.getType() + " certificate with file " + cert.getFilePath(), ex);
                }
            });
        }
    }

    private static void mergeValidationSettings(YamlFixSessionSettings settings, FixSessionSettings.FixSessionSettingsBuilder<?, ?> builder) {
        if (settings.getValidationSettings() != null) {
            FixSessionSettings.ValidationSettings.ValidationSettingsBuilder<?, ?> vsb = FixSessionSettings.ValidationSettings.builder();
            YamlFixSessionSettings.ValidationSettings yvs = settings.getValidationSettings();
            mergeIfNeeded(yvs::getValidateChecksum, vsb::validateChecksum);
            mergeIfNeeded(yvs::getValidateFieldsHaveValues, vsb::validateFieldsHaveValues);
            mergeIfNeeded(yvs::getAllowUserDefinedFields, vsb::allowUserDefinedFields);
            mergeIfNeeded(yvs::getAllowUnknownFields, vsb::allowUnknownFields);
            mergeIfNeeded(yvs::getValidateRequiredFields, vsb::validateRequiredFields);
            mergeIfNeeded(yvs::getAllowUndefinedTagsForMessage, vsb::allowUndefinedTagsForMessage);
            mergeIfNeeded(yvs::getValidateCompId, vsb::validateCompId);
            // not mergeIfNeeded: the settings hold sets while YAML can only give a list
            if (yvs.getExpectedOnBehalfOfCompIds() != null) {
                vsb.expectedOnBehalfOfCompIds(new LinkedHashSet<>(yvs.getExpectedOnBehalfOfCompIds()));
            }
            if (yvs.getExpectedDeliverToCompIds() != null) {
                vsb.expectedDeliverToCompIds(new LinkedHashSet<>(yvs.getExpectedDeliverToCompIds()));
            }
            mergeIfNeeded(yvs::getValidateBeginString, vsb::validateBeginString);
            mergeIfNeeded(yvs::getDetectGarbledMessages, vsb::detectGarbledMessages);
            mergeIfNeeded(yvs::getMaxSendingTime, vsb::maxSendingTime);
            mergeIfNeeded(yvs::getMaxMessageSize, vsb::maxMessageSize);
            mergeIfNeeded(yvs::getRequiredPeerMaxMessageSize, vsb::requiredPeerMaxMessageSize);
            mergeIfNeeded(yvs::getValidateFieldsOutOfOrder, vsb::validateFieldsOutOfOrder);
            mergeIfNeeded(yvs::getValidateDuplicateTags, vsb::validateDuplicateTags);
            builder.validationSettings(vsb.build());
        }
    }

    private static void mergeSessionScheduleSettings(YamlFixSessionSettings settings, FixSessionSettings.FixSessionSettingsBuilder<?, ?> builder) {
        if (settings.getSessionScheduleSettings() != null) {
            FixSessionSettings.SessionScheduleSettings.SessionScheduleSettingsBuilder ssb = FixSessionSettings.SessionScheduleSettings.builder();
            YamlFixSessionSettings.SessionScheduleSettings yssSettings = settings.getSessionScheduleSettings();
            mergeIfNeeded(yssSettings::getOutsideSessionTimePreTriggerDelay, ssb::outsideSessionTimePreTriggerDelay);
            mergeIfNeeded(yssSettings::getTimeZone, ssb::timeZone);
            mergeIfNeeded(yssSettings::getWithinSessionCheckInterval, ssb::withinSessionTimeCheckInterval);
            if (yssSettings.getSessionSchedules() != null) {
                yssSettings.getSessionSchedules().forEach(yamlEntry -> {
                    FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry = FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                            .startDay(yamlEntry.getStartDay())
                            .endDay(yamlEntry.getEndDay())
                            .startTime(yamlEntry.getStartTime())
                            .endTime(yamlEntry.getEndTime())
                            .build();
                    ssb.sessionSchedule(entry);
                });
            }
            if (yssSettings.getNonStopSchedules() != null) {
                yssSettings.getNonStopSchedules().forEach(nonStop -> ssb.nonStopSchedule(
                        FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry.builder()
                                .dayOfWeek(nonStop.getDayOfWeek())
                                .sequenceResetTime(nonStop.getSequenceResetTime())
                                .initiatesReset(nonStop.getInitiatesReset())
                                .build()));
            }
            builder.sessionScheduleSettings(ssb.build());
        }
    }
}