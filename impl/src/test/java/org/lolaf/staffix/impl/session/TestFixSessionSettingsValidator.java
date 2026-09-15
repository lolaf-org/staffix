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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFixSessionSettingsValidator {

    private final FixSessionId fixSessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");

    private static FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry nonStop(DayOfWeek day, LocalTime resetTime) {
        return FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry.builder()
                .dayOfWeek(day)
                .sequenceResetTime(resetTime)
                .initiatesReset(true)
                .build();
    }

    private FixSessionSettings.FixSessionSettingsBuilder<?, ?> validSettings() {
        return FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR);
    }

    @Test
    void defaultSettingsAreValid() {
        assertThatCode(() -> FixSessionSettingsValidator.validate(validSettings().build()))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsNonStopDaysOnTheirOwn() {
        FixSessionSettings settings = validSettings()
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .nonStopSchedule(nonStop(DayOfWeek.MONDAY, LocalTime.of(17, 0)))
                        .nonStopSchedule(nonStop(DayOfWeek.TUESDAY, LocalTime.of(18, 30)))
                        .build())
                .build();

        assertThatCode(() -> FixSessionSettingsValidator.validate(settings)).doesNotThrowAnyException();
    }

    /**
     * The case the whole rework is for: a week that is non-stop from Monday through Thursday and keeps a window on
     * the Friday it closes on, with the two lists dividing the days between them.
     */
    @Test
    void acceptsNonStopDaysAlongsideWindowsOnOtherDays() {
        FixSessionSettings settings = validSettings()
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .nonStopSchedule(nonStop(DayOfWeek.MONDAY, LocalTime.of(17, 0)))
                        .nonStopSchedule(nonStop(DayOfWeek.TUESDAY, LocalTime.of(17, 0)))
                        .nonStopSchedule(nonStop(DayOfWeek.WEDNESDAY, LocalTime.of(17, 0)))
                        .nonStopSchedule(nonStop(DayOfWeek.THURSDAY, LocalTime.of(17, 0)))
                        .sessionSchedule(FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                                .startDay(DayOfWeek.FRIDAY).startTime(LocalTime.MIDNIGHT)
                                .endDay(DayOfWeek.FRIDAY).endTime(LocalTime.of(17, 0))
                                .build())
                        .build())
                .build();

        assertThatCode(() -> FixSessionSettingsValidator.validate(settings)).doesNotThrowAnyException();
    }

    @Test
    void rejectsADayThatIsBothWindowedAndNonStop() {
        // the two say contradictory things about that day - a window has a close, a non-stop day has none - and
        // honouring both would mean silently picking one
        FixSessionSettings settings = validSettings()
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .nonStopSchedule(nonStop(DayOfWeek.WEDNESDAY, LocalTime.of(17, 0)))
                        .sessionSchedule(FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                                .startDay(DayOfWeek.MONDAY).startTime(LocalTime.of(9, 0))
                                .endDay(DayOfWeek.FRIDAY).endTime(LocalTime.of(17, 0))
                                .build())
                        .build())
                .build();

        assertThatThrownBy(() -> FixSessionSettingsValidator.validate(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("covers WEDNESDAY, which nonStopSchedules also claims");
    }

    /**
     * A weekly reset rather than a daily one: six days that never close and never roll, and one that does.
     */
    @Test
    void acceptsANonStopDayThatStatesNeitherAResetTimeNorARole() {
        FixSessionSettings settings = validSettings()
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .nonStopSchedule(FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry.builder()
                                .dayOfWeek(DayOfWeek.MONDAY)
                                .build())
                        .nonStopSchedule(nonStop(DayOfWeek.SUNDAY, LocalTime.of(17, 0)))
                        .build())
                .build();

        assertThatCode(() -> FixSessionSettingsValidator.validate(settings)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAResetTimeWithoutARole() {
        FixSessionSettings settings = validSettings()
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .nonStopSchedule(FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry.builder()
                                .dayOfWeek(DayOfWeek.MONDAY)
                                .sequenceResetTime(LocalTime.of(17, 0))
                                .build())
                        .build())
                .build();

        assertThatThrownBy(() -> FixSessionSettingsValidator.validate(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sets a sequenceResetTime without initiatesReset");
    }

    /**
     * The shape a forgotten reset time leaves behind, which would otherwise be a day that silently never rolls.
     */
    @Test
    void rejectsARoleWithoutAResetTime() {
        FixSessionSettings settings = validSettings()
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .nonStopSchedule(FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry.builder()
                                .dayOfWeek(DayOfWeek.MONDAY)
                                .initiatesReset(true)
                                .build())
                        .build())
                .build();

        assertThatThrownBy(() -> FixSessionSettingsValidator.validate(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sets initiatesReset without a sequenceResetTime");
    }

    @Test
    void rejectsTheSameDayAppearingTwiceInTheNonStopSchedules() {
        FixSessionSettings settings = validSettings()
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .nonStopSchedule(nonStop(DayOfWeek.MONDAY, LocalTime.of(17, 0)))
                        .nonStopSchedule(nonStop(DayOfWeek.MONDAY, LocalTime.of(18, 0)))
                        .build())
                .build();

        assertThatThrownBy(() -> FixSessionSettingsValidator.validate(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repeats MONDAY");
    }

    @Test
    void rejectsHeartbeatLowerBoundGreaterThanUpperBound() {
        FixSessionSettings settings = validSettings()
                .heartBeatInterval(FixSessionSettings.HeartbeatInterval.builder()
                        .acceptorLowerBoundInterval(Duration.ofSeconds(30))
                        .acceptorUpperBoundInterval(Duration.ofSeconds(10))
                        .build())
                .build();

        assertThatThrownBy(() -> FixSessionSettingsValidator.validate(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acceptorLowerBoundInterval");
    }

    @Test
    void rejectsNonPositiveHeartbeatInterval() {
        FixSessionSettings settings = validSettings()
                .heartBeatInterval(FixSessionSettings.HeartbeatInterval.builder()
                        .initiatorInterval(Duration.ZERO)
                        .build())
                .build();

        assertThatThrownBy(() -> FixSessionSettingsValidator.validate(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartBeatInterval.initiatorInterval");
    }

    @Test
    void rejectsNonPositiveMaxMessageSize() {
        FixSessionSettings settings = validSettings()
                .validationSettings(FixSessionSettings.ValidationSettings.builder()
                        .maxMessageSize(0)
                        .build())
                .build();

        assertThatThrownBy(() -> FixSessionSettingsValidator.validate(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxMessageSize");
    }

    @Test
    void rejectsZeroMaxSendingTime() {
        FixSessionSettings settings = validSettings()
                .validationSettings(FixSessionSettings.ValidationSettings.builder()
                        .maxSendingTime(Duration.ZERO)
                        .build())
                .build();

        assertThatThrownBy(() -> FixSessionSettingsValidator.validate(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSendingTime");
    }

    @Test
    void rejectsPreTriggerDelaySmallerThanCheckInterval() {
        FixSessionSettings settings = validSettings()
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .outsideSessionTimePreTriggerDelay(Duration.ofMillis(500))
                        .withinSessionTimeCheckInterval(Duration.ofSeconds(1))
                        .build())
                .build();

        assertThatThrownBy(() -> FixSessionSettingsValidator.validate(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outsideSessionTimePreTriggerDelay");
    }

    @Test
    void rejectsZeroDurationScheduleEntry() {
        FixSessionSettings settings = validSettings()
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .sessionSchedule(FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                                .startDay(DayOfWeek.MONDAY)
                                .endDay(DayOfWeek.MONDAY)
                                .startTime(LocalTime.of(9, 0))
                                .endTime(LocalTime.of(9, 0))
                                .build())
                        .build())
                .build();

        assertThatThrownBy(() -> FixSessionSettingsValidator.validate(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero duration");
    }

    @Test
    void rejectsNonPositiveLogInOrOutResponseTimeout() {
        FixSessionSettings settings = validSettings()
                .logInOrOutResponseTimeout(Duration.ofSeconds(-1))
                .build();

        assertThatThrownBy(() -> FixSessionSettingsValidator.validate(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logInOrOutResponseTimeout");
    }

    @Test
    void rejectsNegativeResendRequestResponseTimeout() {
        FixSessionSettings settings = validSettings()
                .resendRequestResponseTimeout(Duration.ofSeconds(-1))
                .build();

        assertThatThrownBy(() -> FixSessionSettingsValidator.validate(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resendRequestResponseTimeout");
    }

    @Test
    void acceptsAZeroResendRequestResponseTimeoutWhichTurnsItOff() {
        FixSessionSettings settings = validSettings()
                .resendRequestResponseTimeout(Duration.ZERO)
                .build();

        assertThatCode(() -> FixSessionSettingsValidator.validate(settings)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveCodTimeoutWindow() {
        FixSessionSettings settings = validSettings()
                .cancelOnDisconnectSettings(FixSessionSettings.CancelOnDisconnectSettings.builder()
                        .codTimeoutWindow(Duration.ZERO)
                        .build())
                .build();

        assertThatThrownBy(() -> FixSessionSettingsValidator.validate(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("codTimeoutWindow");
    }

    @Test
    void rejectsZeroProbeIntervalWhenSet() {
        FixSessionSettings settings = validSettings()
                .rttMeasurementSettings(FixSessionSettings.RttMeasurementSettings.builder()
                        .probeInterval(Duration.ZERO)
                        .build())
                .build();

        assertThatThrownBy(() -> FixSessionSettingsValidator.validate(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probeInterval");
    }

    @Test
    void reportsAllViolationsAtOnce() {
        FixSessionSettings settings = validSettings()
                .logInOrOutResponseTimeout(Duration.ZERO)
                .validationSettings(FixSessionSettings.ValidationSettings.builder()
                        .maxMessageSize(-5)
                        .build())
                .build();

        assertThatThrownBy(() -> FixSessionSettingsValidator.validate(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logInOrOutResponseTimeout")
                .hasMessageContaining("maxMessageSize");
    }
}
