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

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.spring.boot.spi.StaffixApplicationFactoryConstants;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalTime;

/**
 * Turns a session declared in {@code staffix.*} properties into settings the engine can use, resolving
 * placeholders from the Spring Environment on the way.
 */
@UtilityClass
public class FixSessionSettingsMapper {

    public static FixSessionSettings toSettings(FixSessionSettingsProps p) {
        if (p.getFixSessionId() == null) {
            throw new IllegalArgumentException("session-id is required");
        }
        if (p.getType() == null) {
            throw new IllegalArgumentException("type is required");
        }
        FixSessionSettings.FixSessionSettingsBuilder<?, ?> b = FixSessionSettings.builder()
                .fixSessionId(p.getFixSessionId().toFixSessionId())
                .fixSessionType(p.getType())
                // Tentative default; overridden by applyTo if the prop is set.
                .fixApplicationFactoryInstanceId(StaffixApplicationFactoryConstants.SPRING_FACTORY_INSTANCE_ID);
        return applyTo(p, b).build();
    }

    /**
     * Applies non-null fields from {@code p} onto {@code b}. Skips {@code sessionId} (handled by callers that need
     * required-field validation) so this method can be reused for partial defaults props.
     */
    public static FixSessionSettings.FixSessionSettingsBuilder<?, ?> applyTo(
            FixSessionSettingsProps p, FixSessionSettings.FixSessionSettingsBuilder<?, ?> b) {
        if (p.getDictionaryId() != null) {
            b.dictionaryId(p.getDictionaryId());
        }
        if (p.getHeartbeat() != null) {
            FixSessionSettings.HeartbeatInterval.HeartbeatIntervalBuilder<?, ?> hb = FixSessionSettings.HeartbeatInterval.builder();
            if (p.getHeartbeat().getInitiatorInterval() != null)
                hb.initiatorInterval(p.getHeartbeat().getInitiatorInterval());
            if (p.getHeartbeat().getAcceptorLowerBoundInterval() != null)
                hb.acceptorLowerBoundInterval(p.getHeartbeat().getAcceptorLowerBoundInterval());
            if (p.getHeartbeat().getAcceptorUpperBoundInterval() != null)
                hb.acceptorUpperBoundInterval(p.getHeartbeat().getAcceptorUpperBoundInterval());
            b.heartBeatInterval(hb.build());
        }
        if (p.getSendingTimeAccuracy() != null) {
            b.sendingTimeAccuracy(p.getSendingTimeAccuracy());
        }
        if (p.getApplicationSessionSettings() != null) {
            p.getApplicationSessionSettings().forEach((k, v) -> b.fixApplicationSessionSetting(FixApplicationSessionSettingDescriptor.of(k), v));
        }
        b.advertiseMsgTypeGrpOnLogon(p.isAdvertiseMsgTypeGrpOnLogon());
        b.advertiseEngineOnLogon(p.isAdvertiseEngineOnLogon());
        b.advertiseApplicationOnLogon(p.isAdvertiseApplicationOnLogon());
        if (p.getEnabledLogonNextExpectedMsgSeqNum() != null) {
            b.enabledLogonNextExpectedMsgSeqNum(p.getEnabledLogonNextExpectedMsgSeqNum());
        }
        if (p.getAllowedAddresses() != null) {
            for (String addr : p.getAllowedAddresses()) {
                try {
                    b.allowedAddress(InetAddress.getByName(addr));
                } catch (UnknownHostException e) {
                    throw new IllegalArgumentException("Invalid allowed-address '" + addr + "'", e);
                }
            }
        }
        if (p.getResetSeqNumOnLogon() != null) {
            b.resetSeqNumOnLogon(p.getResetSeqNumOnLogon());
        }
        if (p.getDesiredSessionState() != null) {
            b.desiredSessionState(p.getDesiredSessionState());
        }
        if (p.getLogInOrOutResponseTimeout() != null) {
            b.logInOrOutResponseTimeout(p.getLogInOrOutResponseTimeout());
        }
        if (p.getResendRequestResponseTimeout() != null) {
            b.resendRequestResponseTimeout(p.getResendRequestResponseTimeout());
        }
        if (p.getMaxOutOfSequenceMessagesQueued() != null) {
            b.maxOutOfSequenceMessagesQueued(p.getMaxOutOfSequenceMessagesQueued());
        }
        if (p.getMaxOutgoingMessagesHeldDuringRecovery() != null) {
            b.maxOutgoingMessagesHeldDuringRecovery(p.getMaxOutgoingMessagesHeldDuringRecovery());
        }
        if (p.getMaxMessagesResentPerRequest() != null) {
            b.maxMessagesResentPerRequest(p.getMaxMessagesResentPerRequest());
        }
        if (p.getResendRequestRange() != null) {
            b.resendRequestRange(p.getResendRequestRange());
        }
        if (p.getFixApplicationFactoryInstanceId() != null) {
            b.fixApplicationFactoryInstanceId(p.getFixApplicationFactoryInstanceId());
        }
        if (p.getFixApplicationInstanceId() != null) {
            b.fixApplicationInstanceId(p.getFixApplicationInstanceId());
        }
        if (p.getFixMessageStoreInstanceId() != null) {
            b.fixMessageStoreInstanceId(p.getFixMessageStoreInstanceId());
        }
        if (p.getFixMessageLoggerInstanceId() != null) {
            b.fixMessageLoggerInstanceId(p.getFixMessageLoggerInstanceId());
        }
        if (p.getFixSessionPluginsInstanceIds() != null) {
            p.getFixSessionPluginsInstanceIds().forEach((className, instanceId) -> {
                Class<?> raw;
                try {
                    raw = Class.forName(className);
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("Unknown FixSessionsPlugin class '" + className + "'", e);
                }
                if (!FixSessionsPlugin.class.isAssignableFrom(raw)) {
                    throw new IllegalArgumentException("Class '" + className + "' is not a FixSessionsPlugin");
                }
                b.fixSessionPluginsInstanceId((Class<? extends FixSessionsPlugin<?>>) raw, instanceId);
            });
        }
        if (p.getMessageEncodersDirectByteBuffers() != null) {
            b.messageEncodersDirectByteBuffers(p.getMessageEncodersDirectByteBuffers());
        }
        if (p.getPooledMessageEncodersDirectByteBuffers() != null) {
            b.pooledMessageEncodersDirectByteBuffers(p.getPooledMessageEncodersDirectByteBuffers());
        }
        if (p.getDisconnectOnRemove() != null) {
            b.disconnectOnRemove(p.getDisconnectOnRemove());
        }
        if (p.getRestartLiveSessionOnUpdate() != null) {
            b.restartLiveSessionOnUpdate(p.getRestartLiveSessionOnUpdate());
        }
        if (p.getValidation() != null) {
            FixSessionSettings.ValidationSettings.ValidationSettingsBuilder<?, ?> vb = FixSessionSettings.ValidationSettings.builder();
            FixSessionSettingsProps.ValidationProps v = p.getValidation();
            if (v.getValidateChecksum() != null) vb.validateChecksum(v.getValidateChecksum());
            if (v.getValidateFieldsHaveValues() != null) vb.validateFieldsHaveValues(v.getValidateFieldsHaveValues());
            if (v.getAllowUserDefinedFields() != null) vb.allowUserDefinedFields(v.getAllowUserDefinedFields());
            if (v.getAllowUnknownFields() != null) vb.allowUnknownFields(v.getAllowUnknownFields());
            if (v.getValidateRequiredFields() != null) vb.validateRequiredFields(v.getValidateRequiredFields());
            if (v.getAllowUndefinedTagsForMessage() != null)
                vb.allowUndefinedTagsForMessage(v.getAllowUndefinedTagsForMessage());
            if (v.getValidateCompId() != null) vb.validateCompId(v.getValidateCompId());
            if (v.getExpectedOnBehalfOfCompIds() != null)
                vb.expectedOnBehalfOfCompIds(v.getExpectedOnBehalfOfCompIds());
            if (v.getExpectedDeliverToCompIds() != null) vb.expectedDeliverToCompIds(v.getExpectedDeliverToCompIds());
            if (v.getValidateBeginString() != null) vb.validateBeginString(v.getValidateBeginString());
            if (v.getDetectGarbledMessages() != null) vb.detectGarbledMessages(v.getDetectGarbledMessages());
            if (v.getMaxSendingTime() != null) vb.maxSendingTime(v.getMaxSendingTime());
            if (v.getMaxMessageSize() != null) vb.maxMessageSize(v.getMaxMessageSize());
            if (v.getRequiredPeerMaxMessageSize() != null)
                vb.requiredPeerMaxMessageSize(v.getRequiredPeerMaxMessageSize());
            if (v.getValidateFieldsOutOfOrder() != null) vb.validateFieldsOutOfOrder(v.getValidateFieldsOutOfOrder());
            if (v.getValidateDuplicateTags() != null) vb.validateDuplicateTags(v.getValidateDuplicateTags());
            b.validationSettings(vb.build());
        }
        if (p.getTestingMode() != null) {
            b.testingMode(p.getTestingMode());
        }
        if (p.getDisconnectMessagesFlushDeadline() != null) {
            b.disconnectMessagesFlushDeadline(p.getDisconnectMessagesFlushDeadline());
        }
        if (p.getCancelOnDisconnect() != null) {
            FixSessionSettings.CancelOnDisconnectSettings.CancelOnDisconnectSettingsBuilder<?, ?> cb = FixSessionSettings.CancelOnDisconnectSettings.builder();
            FixSessionSettingsProps.CodProps c = p.getCancelOnDisconnect();
            cb.enabled(c.isEnabled());
            if (c.getCancelOnDisconnectType() != null) cb.cancelOnDisconnectType(c.getCancelOnDisconnectType());
            if (c.getCancelOnDisconnectTypeFieldCodes() != null)
                cb.cancelOnDisconnectTypeFieldCodes(c.getCancelOnDisconnectTypeFieldCodes());
            if (c.getCancelOnDisconnectTypeFieldCode() != null)
                cb.cancelOnDisconnectTypeFieldCode(c.getCancelOnDisconnectTypeFieldCode());
            if (c.getCodTimeoutWindowFieldCode() != null)
                cb.codTimeoutWindowFieldCode(c.getCodTimeoutWindowFieldCode());
            if (c.getCodTimeoutWindow() != null) cb.codTimeoutWindow(c.getCodTimeoutWindow());
            if (c.getCodTimeoutWindowScale() != null) cb.codTimeoutWindowScale(c.getCodTimeoutWindowScale());
            b.cancelOnDisconnectSettings(cb.build());
        }
        if (p.getRttMeasurement() != null) {
            FixSessionSettings.RttMeasurementSettings.RttMeasurementSettingsBuilder<?, ?> rb = FixSessionSettings.RttMeasurementSettings.builder();
            FixSessionSettingsProps.RttMeasurementProps r = p.getRttMeasurement();
            if (r.getProbeInterval() != null) rb.probeInterval(r.getProbeInterval());
            if (r.getEmaTimeWindow() != null) rb.emaTimeWindow(r.getEmaTimeWindow());
            if (r.getMaxAcceptedRtt() != null) rb.maxAcceptedRtt(r.getMaxAcceptedRtt());
            if (r.getProbeTestReqIdPrefix() != null) rb.probeTestReqIdPrefix(r.getProbeTestReqIdPrefix());
            if (r.getSendingTimeToWireDelay() != null) rb.sendingTimeToWireDelay(r.getSendingTimeToWireDelay());
            b.rttMeasurementSettings(rb.build());
        }
        if (p.getSchedule() != null) {
            FixSessionSettings.SessionScheduleSettings.SessionScheduleSettingsBuilder ssb = FixSessionSettings.SessionScheduleSettings.builder();
            FixSessionSettingsProps.ScheduleProps s = p.getSchedule();
            if (s.getOutsideSessionTimePreTriggerDelay() != null)
                ssb.outsideSessionTimePreTriggerDelay(s.getOutsideSessionTimePreTriggerDelay());
            if (s.getTimeZone() != null) ssb.timeZone(s.getTimeZone());
            if (s.getWithinSessionTimeCheckInterval() != null)
                ssb.withinSessionTimeCheckInterval(s.getWithinSessionTimeCheckInterval());
            if (s.getSessionSchedules() != null) {
                for (FixSessionSettingsProps.ScheduleProps.ScheduleEntryProps e : s.getSessionSchedules()) {
                    ssb.sessionSchedule(FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                            .startDay(e.getStartDay())
                            .endDay(e.getEndDay())
                            .startTime(LocalTime.parse(e.getStartTime()))
                            .endTime(LocalTime.parse(e.getEndTime()))
                            .build());
                }
            }
            if (s.getNonStopSchedules() != null) {
                for (FixSessionSettingsProps.ScheduleProps.NonStopScheduleEntryProps nonStop : s.getNonStopSchedules()) {
                    ssb.nonStopSchedule(FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry.builder()
                            .dayOfWeek(nonStop.getDayOfWeek())
                            // absent on a non-stop day that does not roll, which is a day stating no time and no role
                            .sequenceResetTime(nonStop.getSequenceResetTime() == null
                                    ? null : LocalTime.parse(nonStop.getSequenceResetTime()))
                            .initiatesReset(nonStop.getInitiatesReset())
                            .build());
                }
            }
            b.sessionScheduleSettings(ssb.build());
        }
        return b;
    }
}
