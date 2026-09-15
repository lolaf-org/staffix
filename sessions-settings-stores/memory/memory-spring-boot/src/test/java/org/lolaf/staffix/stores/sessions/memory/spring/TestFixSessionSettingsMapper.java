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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.spring.boot.spi.FixSessionIdProps;

import java.time.DayOfWeek;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class TestFixSessionSettingsMapper {

    private static FixSessionSettingsProps.ScheduleProps.NonStopScheduleEntryProps nonStopProps(
            DayOfWeek day, String resetTime, boolean initiatesReset) {
        FixSessionSettingsProps.ScheduleProps.NonStopScheduleEntryProps props =
                new FixSessionSettingsProps.ScheduleProps.NonStopScheduleEntryProps();
        props.setDayOfWeek(day);
        props.setSequenceResetTime(resetTime);
        props.setInitiatesReset(initiatesReset);
        return props;
    }

    private static FixSessionSettings.SessionScheduleSettings mapSchedule(FixSessionSettingsProps.ScheduleProps schedule) {
        FixSessionIdProps sessionId = new FixSessionIdProps();
        sessionId.setId("SENDER-TARGET");
        sessionId.setSenderCompId("SENDER");
        sessionId.setTargetCompId("TARGET");
        sessionId.setFixVersion("FIX.4.4");

        FixSessionSettingsProps props = new FixSessionSettingsProps();
        props.setFixSessionId(sessionId);
        props.setType(FixSession.FixSessionType.INITIATOR);
        props.setSchedule(schedule);

        return FixSessionSettingsMapper.toSettings(props).getSessionScheduleSettings();
    }

    /**
     * Two non-stop days rolling at different times, which is the whole reason the entries are held per day rather
     * than once per session.
     */
    @Test
    void mapsNonStopDaysWithTheirOwnResetTimes() {
        FixSessionSettingsProps.ScheduleProps schedule = new FixSessionSettingsProps.ScheduleProps();
        schedule.getNonStopSchedules().add(nonStopProps(DayOfWeek.MONDAY, "17:00", true));
        schedule.getNonStopSchedules().add(nonStopProps(DayOfWeek.TUESDAY, "18:30", false));

        FixSessionSettings.SessionScheduleSettings mapped = mapSchedule(schedule);

        assertThat(mapped.getSessionSchedules()).isEmpty();
        assertThat(mapped.getNonStopSchedules()).satisfiesExactly(
                monday -> {
                    assertThat(monday.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
                    assertThat(monday.getSequenceResetTime()).isEqualTo(LocalTime.of(17, 0));
                    assertThat(monday.getInitiatesReset()).isTrue();
                },
                tuesday -> {
                    assertThat(tuesday.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
                    assertThat(tuesday.getSequenceResetTime()).isEqualTo(LocalTime.of(18, 30));
                    assertThat(tuesday.getInitiatesReset()).isFalse();
                });
    }

    @Test
    void mapsTradingWindowsAndLeavesTheNonStopSchedulesEmpty() {
        FixSessionSettingsProps.ScheduleProps.ScheduleEntryProps entry =
                new FixSessionSettingsProps.ScheduleProps.ScheduleEntryProps();
        entry.setStartDay(DayOfWeek.MONDAY);
        entry.setEndDay(DayOfWeek.FRIDAY);
        entry.setStartTime("08:00");
        entry.setEndTime("18:00");

        FixSessionSettingsProps.ScheduleProps schedule = new FixSessionSettingsProps.ScheduleProps();
        schedule.getSessionSchedules().add(entry);

        FixSessionSettings.SessionScheduleSettings mapped = mapSchedule(schedule);

        assertThat(mapped.getNonStopSchedules()).isEmpty();
        assertThat(mapped.getSessionSchedules()).singleElement().satisfies(mappedEntry -> {
            assertThat(mappedEntry.getStartDay()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(mappedEntry.getEndDay()).isEqualTo(DayOfWeek.FRIDAY);
            assertThat(mappedEntry.getStartTime()).isEqualTo(LocalTime.of(8, 0));
            assertThat(mappedEntry.getEndTime()).isEqualTo(LocalTime.of(18, 0));
        });
    }
}
