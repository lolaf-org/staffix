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
package org.lolaf.staffix.impl.session;

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.session.FixSessionSettings;

import java.time.DayOfWeek;
import java.time.Duration;
import java.util.*;

/**
 * Validates the semantic, cross-field invariants of a {@link FixSessionSettings} regardless of where the settings come
 * from (programmatically built via the builder or loaded from a settings store). This is the single authoritative
 * validation choke point: the engine runs it for every configured session at start-up and for sessions resolved
 * on-the-fly when an acceptor receives a logon.
 * <p>
 * Shape-level constraints on the file representation (non-blank ids, non-null required fields, ...) remain the
 * responsibility of the file store's own Jakarta-based validator; this class focuses on value ranges and relationships
 * between settings that are not expressible there and that must also hold for programmatically built settings.
 * <p>
 * All detected problems are collected and reported together in a single {@link IllegalStateException} so a
 * misconfiguration surfaces all at once rather than one round-trip at a time.
 */
@UtilityClass
public class FixSessionSettingsValidator {

    public static void validate(FixSessionSettings settings) {
        List<String> violations = new ArrayList<>();

        validateValidationSettings(settings, violations);
        validateHeartbeatInterval(settings, violations);
        validateScheduleSettings(settings, violations);
        validateTimeouts(settings, violations);
        validateCancelOnDisconnect(settings, violations);
        validateRttMeasurement(settings, violations);

        if (!violations.isEmpty()) {
            String details = violations.stream().sorted().reduce("", (a, b) -> a + "\n  - " + b);
            throw new IllegalArgumentException("Invalid FIX session settings for " + settings.getFixSessionId() + ":" + details);
        }
    }

    private static void validateValidationSettings(FixSessionSettings settings, List<String> violations) {
        FixSessionSettings.ValidationSettings validationSettings = settings.getValidationSettings();

        Duration maxSendingTime = validationSettings.getMaxSendingTime();
        // null disables the check; any configured value must be strictly positive
        if (maxSendingTime != null && (maxSendingTime.isZero() || maxSendingTime.isNegative())) {
            violations.add("validationSettings.maxSendingTime must be greater than zero when set");
        }

        Integer maxMessageSize = validationSettings.getMaxMessageSize();
        // null disables the check; any configured value must be strictly positive
        if (maxMessageSize != null && maxMessageSize <= 0) {
            violations.add("validationSettings.maxMessageSize must be greater than zero when set");
        }
    }

    private static void validateHeartbeatInterval(FixSessionSettings settings, List<String> violations) {
        FixSessionSettings.HeartbeatInterval heartbeat = settings.getHeartBeatInterval();
        requirePositive(violations, heartbeat.getInitiatorInterval(), "heartBeatInterval.initiatorInterval");
        requirePositive(violations, heartbeat.getAcceptorLowerBoundInterval(), "heartBeatInterval.acceptorLowerBoundInterval");
        requirePositive(violations, heartbeat.getAcceptorUpperBoundInterval(), "heartBeatInterval.acceptorUpperBoundInterval");

        Duration lower = heartbeat.getAcceptorLowerBoundInterval();
        Duration upper = heartbeat.getAcceptorUpperBoundInterval();
        if (lower != null && upper != null && lower.compareTo(upper) > 0) {
            violations.add("heartBeatInterval.acceptorLowerBoundInterval (" + lower
                    + ") must not be greater than acceptorUpperBoundInterval (" + upper + ")");
        }
    }

    private static void validateScheduleSettings(FixSessionSettings settings, List<String> violations) {
        FixSessionSettings.SessionScheduleSettings schedule = settings.getSessionScheduleSettings();
        requirePositive(violations, schedule.getOutsideSessionTimePreTriggerDelay(), "sessionScheduleSettings.outsideSessionTimePreTriggerDelay");
        requirePositive(violations, schedule.getWithinSessionTimeCheckInterval(), "sessionScheduleSettings.withinSessionTimeCheckInterval");

        Duration preTrigger = schedule.getOutsideSessionTimePreTriggerDelay();
        Duration checkInterval = schedule.getWithinSessionTimeCheckInterval();
        if (preTrigger != null && checkInterval != null && preTrigger.compareTo(checkInterval) < 0) {
            violations.add("sessionScheduleSettings.outsideSessionTimePreTriggerDelay (" + preTrigger
                    + ") must be greater than withinSessionTimeCheckInterval (" + checkInterval + ")");
        }

        violations.addAll(scheduleViolations(schedule));
    }

    /**
     * The violations that concern the schedule entries themselves and how the two lists divide the week, kept apart
     * from the rest so that {@link FixSessionScheduleManager} - which is handed a schedule without necessarily
     * coming through {@link #validate} - can apply exactly the same rules.
     */
    static List<String> scheduleViolations(FixSessionSettings.SessionScheduleSettings schedule) {
        List<String> violations = new ArrayList<>();

        int index = 0;
        for (FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry : schedule.getSessionSchedules()) {
            int finalIndex = index++;
            scheduleEntryViolation(entry).ifPresent(
                    msg -> violations.add("sessionScheduleSettings.sessionSchedules[" + finalIndex + "] " + msg));
        }

        Set<DayOfWeek> nonStopDays = EnumSet.noneOf(DayOfWeek.class);
        index = 0;
        for (FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry entry : schedule.getNonStopSchedules()) {
            int finalIndex = index++;
            if (!nonStopDays.add(entry.getDayOfWeek())) {
                // one day restarts its numbering at one time: a second entry for it would either be ignored or reset
                // twice, and neither is a safe guess at what was meant
                violations.add("sessionScheduleSettings.nonStopSchedules[" + finalIndex + "] repeats "
                        + entry.getDayOfWeek() + ", which is already non-stop: a day may appear once");
            }
            // the two say one thing between them - when the day rolls and who rolls it - so a day states both or
            // neither. A time without a role has nobody to perform it; a role without a time is what a forgotten
            // time leaves behind, and would silently be a day that never rolls
            if (entry.getSequenceResetTime() != null && entry.getInitiatesReset() == null) {
                violations.add("sessionScheduleSettings.nonStopSchedules[" + finalIndex + "] sets a sequenceResetTime "
                        + "without initiatesReset: section 4.4.2 has the counterparties agree which end rolls, so "
                        + "there is no default to fall back on");
            }
            if (entry.getSequenceResetTime() == null && entry.getInitiatesReset() != null) {
                violations.add("sessionScheduleSettings.nonStopSchedules[" + finalIndex + "] sets initiatesReset "
                        + "without a sequenceResetTime: leave both unset for a non-stop day that does not roll");
            }
        }

        index = 0;
        for (FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry : schedule.getSessionSchedules()) {
            int finalIndex = index++;
            for (DayOfWeek day : FixSessionScheduleManager.coveredDays(entry)) {
                if (nonStopDays.contains(day)) {
                    // the two say contradictory things about that day: a window has a close, a non-stop day has none.
                    // Honouring both would mean choosing which one to ignore
                    violations.add("sessionScheduleSettings.sessionSchedules[" + finalIndex + "] covers " + day
                            + ", which nonStopSchedules also claims: a day either keeps trading windows or runs non-stop");
                }
            }
        }
        return violations;
    }

    private static void validateTimeouts(FixSessionSettings settings, List<String> violations) {
        requirePositive(violations, settings.getLogInOrOutResponseTimeout(), "logInOrOutResponseTimeout");
        requirePositive(violations, settings.getDisconnectMessagesFlushDeadline(), "disconnectMessagesFlushDeadline");
        requireNonNegative(violations, settings.getResendRequestResponseTimeout(), "resendRequestResponseTimeout");
    }

    private static void validateCancelOnDisconnect(FixSessionSettings settings, List<String> violations) {
        FixSessionSettings.CancelOnDisconnectSettings cod = settings.getCancelOnDisconnectSettings();
        requirePositive(violations, cod.getCodTimeoutWindow(), "cancelOnDisconnectSettings.codTimeoutWindow");
        if (cod.getCodTimeoutWindowScale() == null) {
            violations.add("cancelOnDisconnectSettings.codTimeoutWindowScale must be set");
        }
        if (cod.getCancelOnDisconnectTypeFieldCode() <= 0) {
            violations.add("cancelOnDisconnectSettings.cancelOnDisconnectTypeFieldCode must be a positive FIX tag");
        }
        if (cod.getCodTimeoutWindowFieldCode() <= 0) {
            violations.add("cancelOnDisconnectSettings.codTimeoutWindowFieldCode must be a positive FIX tag");
        }
    }

    private static void validateRttMeasurement(FixSessionSettings settings, List<String> violations) {
        FixSessionSettings.RttMeasurementSettings rtt = settings.getRttMeasurementSettings();
        // The estimator runs off heartbeat-driven TestRequests even when continuous probing is disabled, so the EMA
        // window is always required; the probe interval is only relevant when continuous probing is enabled.
        requirePositive(violations, rtt.getEmaTimeWindow(), "rttMeasurementSettings.emaTimeWindow");

        Duration probeInterval = rtt.getProbeInterval();
        if (probeInterval != null && (probeInterval.isZero() || probeInterval.isNegative())) {
            violations.add("rttMeasurementSettings.probeInterval must be greater than zero when set");
        }

        Duration maxAcceptedRtt = rtt.getMaxAcceptedRtt();
        // null accepts any RTT; any configured value must be strictly positive
        if (maxAcceptedRtt != null && (maxAcceptedRtt.isZero() || maxAcceptedRtt.isNegative())) {
            violations.add("rttMeasurementSettings.maxAcceptedRtt must be greater than zero when set");
        }
    }

    /**
     * Shared zero-duration check for a schedule entry, reused by {@link FixSessionScheduleManager} so there is a single
     * source of truth for what makes a schedule entry valid.
     *
     * @return a human-readable violation message, or empty if the entry is valid
     */
    static Optional<String> scheduleEntryViolation(FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry) {
        if (entry.getStartDay().getValue() == entry.getEndDay().getValue()
                && entry.getStartTime().equals(entry.getEndTime())) {
            return Optional.of(String.format(
                    "same day %s with equal start and end times %s. Session has zero duration.",
                    entry.getStartDay(), entry.getStartTime()));
        }
        return Optional.empty();
    }

    private static void requirePositive(List<String> violations, Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            violations.add(name + " must be set and greater than zero");
        }
    }

    /**
     * For the timeouts that {@link Duration#ZERO} turns off rather than leaves unset.
     */
    private static void requireNonNegative(List<String> violations, Duration value, String name) {
        if (value == null || value.isNegative()) {
            violations.add(name + " must be set and not negative");
        }
    }
}
