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
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.tests.TestingClock;

import java.time.*;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TestFixSessionScheduleManager {

    private final FixSessionId fixSessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");

    private static Clock getTestClock(int dayOfMonth, int hour, int minute) {
        ZonedDateTime time1 = ZonedDateTime.of(2026, 3, dayOfMonth, hour, minute, 0, 0, ZoneId.of("UTC"));
        return new TestingClock.FixedClock(time1.toInstant());
    }

    @Test
    void testGetSessionTimeLeft_WithinSession() {
        // Create a schedule: Monday 09:00 to Monday 17:00
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.MONDAY)
                        .endDay(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build();

        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .sessionSchedule(entry)
                        .build())
                .build();

        // Set current time to Monday 12:00 UTC (within session)
        Clock clock = getTestClock(9, 12, 0);

        FixSessionScheduleManager schedule = new FixSessionScheduleManager(settings, clock);

        // Should return 5 hours (until 17:00)
        long timeLeft = schedule.getSessionTimeLeft();
        long expectedMillis = Duration.ofHours(5).toMillis();

        assertThat(timeLeft).isCloseTo(expectedMillis, within(1000L));
    }

    @Test
    void testGetSessionTimeLeft_OutsideSession() {
        // Create a schedule: Monday 09:00 to Monday 17:00
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.MONDAY)
                        .endDay(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build();

        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .sessionSchedule(entry)
                        .build())
                .build();

        // Set current time to Monday 20:00 UTC (outside session)
        Clock clock = getTestClock(9, 20, 0);

        FixSessionScheduleManager schedule = new FixSessionScheduleManager(settings, clock);

        // Should return 0 (outside session)
        long timeLeft = schedule.getSessionTimeLeft();

        assertThat(timeLeft).isZero();
    }

    @Test
    void testGetSessionTimeLeft_WeekWrapAround() {
        // Create a schedule: Friday 18:00 to Monday 08:00 (wraps around weekend)
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.FRIDAY)
                        .endDay(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(18, 0))
                        .endTime(LocalTime.of(8, 0))
                        .build();

        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .sessionSchedule(entry)
                        .build())
                .build();

        // Set current time to Saturday 12:00 UTC (within wrapped session)
        Clock clock = getTestClock(7, 12, 0);

        FixSessionScheduleManager schedule = new FixSessionScheduleManager(settings, clock);

        // Should return time until Monday 08:00
        // From Saturday 12:00 to Monday 08:00 = 1 day 20 hours = 44 hours
        long timeLeft = schedule.getSessionTimeLeft();
        long expectedMillis = Duration.ofHours(44).toMillis();

        assertThat(timeLeft).isCloseTo(expectedMillis, within(1000L));
    }

    @Test
    void testGetSessionTimeLeft_WeekWrapAround_OnFriday() {
        // Create a schedule: Friday 18:00 to Monday 08:00 (wraps around weekend)
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.FRIDAY)
                        .endDay(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(18, 0))
                        .endTime(LocalTime.of(8, 0))
                        .build();

        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .sessionSchedule(entry)
                        .build())
                .build();

        // Set current time to Friday 20:00 UTC (within session, after start)
        Clock clock = getTestClock(6, 20, 0);

        FixSessionScheduleManager schedule = new FixSessionScheduleManager(settings, clock);

        // Should return time until Monday 08:00
        // From Friday 20:00 to Monday 08:00 = 2 days 12 hours = 60 hours
        long timeLeft = schedule.getSessionTimeLeft();
        long expectedMillis = Duration.ofHours(60).toMillis();

        assertThat(timeLeft).isCloseTo(expectedMillis, within(1000L));
    }

    @Test
    void testGetSessionTimeLeft_EmptySchedule() {
        // Create settings with no schedule entries
        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .build())
                .build();

        Clock clock = getTestClock(9, 12, 0);

        FixSessionScheduleManager schedule = new FixSessionScheduleManager(settings, clock);

        // Should return Long.max (no schedule configured)
        long timeLeft = schedule.getSessionTimeLeft();

        assertThat(timeLeft).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void testGetSessionTimeLeft_MultipleScheduleEntries() {
        // Create two schedule entries: Monday 09:00-17:00 and Wednesday 09:00-17:00
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry1 =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.MONDAY)
                        .endDay(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build();

        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry2 =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.WEDNESDAY)
                        .endDay(DayOfWeek.WEDNESDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build();

        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .sessionSchedule(entry1)
                        .sessionSchedule(entry2)
                        .build())
                .build();

        // Set current time to Wednesday 14:00 UTC (within second session)
        Clock clock = getTestClock(11, 14, 0);

        FixSessionScheduleManager schedule = new FixSessionScheduleManager(settings, clock);

        // Should return 3 hours (until 17:00 on Wednesday)
        long timeLeft = schedule.getSessionTimeLeft();
        long expectedMillis = Duration.ofHours(3).toMillis();

        assertThat(timeLeft).isCloseTo(expectedMillis, within(1000L));
    }

    // ==================== Validation Tests ====================

    @Test
    void testGetSessionTimeLeft_SameDayAcrossMidnight() {
        // Create a schedule: startDay=MONDAY endDay=MONDAY, startTime=22:00 endTime=02:00
        // Semantic meaning: Starts Monday 22:00, ends TUESDAY 02:00 (next calendar day, 4 hours total)
        // This is a "same day-of-week" entry where startTime > endTime means wrap to next calendar day
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.MONDAY)
                        .endDay(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(22, 0))
                        .endTime(LocalTime.of(2, 0))
                        .build();

        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .sessionSchedule(entry)
                        .build())
                .build();

        // Test case 1: Current time Monday 22:30 (30 minutes after start, before midnight)
        Clock clock1 = getTestClock(9, 22, 30);
        FixSessionScheduleManager schedule1 = new FixSessionScheduleManager(settings, clock1);
        long timeLeft1 = schedule1.getSessionTimeLeft();
        assertThat(timeLeft1).isCloseTo(Duration.ofHours(3).plusMinutes(30).toMillis(), within(1000L));

        // Test case 2: Current time Monday 23:00 (1 hour after start, before midnight)
        Clock clock2 = getTestClock(9, 23, 0);
        FixSessionScheduleManager schedule2 = new FixSessionScheduleManager(settings, clock2);
        long timeLeft2 = schedule2.getSessionTimeLeft();
        assertThat(timeLeft2).isCloseTo(Duration.ofHours(3).toMillis(), within(1000L));

        // Test case 3: Current time Tuesday 00:30 (after midnight, 2.5 hours after start)
        Clock clock3 = getTestClock(10, 0, 30);
        FixSessionScheduleManager schedule3 = new FixSessionScheduleManager(settings, clock3);
        long timeLeft3 = schedule3.getSessionTimeLeft();
        assertThat(timeLeft3).isCloseTo(Duration.ofHours(1).plusMinutes(30).toMillis(), within(1000L));

        // Test case 4: Current time Tuesday 01:30 (30 minutes before end)
        Clock clock4 = getTestClock(10, 1, 30);
        FixSessionScheduleManager schedule4 = new FixSessionScheduleManager(settings, clock4);
        long timeLeft4 = schedule4.getSessionTimeLeft();
        assertThat(timeLeft4).isCloseTo(Duration.ofMinutes(30).toMillis(), within(1000L));

        // Test case 5: Current time Tuesday 03:00 (outside session, after end)
        Clock clock5 = getTestClock(10, 3, 0);
        FixSessionScheduleManager schedule5 = new FixSessionScheduleManager(settings, clock5);
        long timeLeft5 = schedule5.getSessionTimeLeft();
        assertThat(timeLeft5).isZero();

        // Test case 6: Current time Monday 21:00 (outside session, before start)
        Clock clock6 = getTestClock(9, 21, 0);
        FixSessionScheduleManager schedule6 = new FixSessionScheduleManager(settings, clock6);
        long timeLeft6 = schedule6.getSessionTimeLeft();
        assertThat(timeLeft6).isZero();
    }

    @Test
    void testValidation_InvalidSameDayWithEqualTimes() {
        // Create an invalid schedule: Monday 09:00 to Monday 09:00 (zero duration)
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.MONDAY)
                        .endDay(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(9, 0))
                        .build();

        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .sessionSchedule(entry)
                        .build())
                .build();

        Clock clock = getTestClock(9, 12, 0);

        // Should throw IllegalArgumentException
        Exception exception = null;
        try {
            new FixSessionScheduleManager(settings, clock);
        } catch (IllegalArgumentException e) {
            exception = e;
        }

        assertThat(exception).isNotNull()
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(exception.getMessage()).contains("same day MONDAY")
                .contains("equal start and end times")
                .contains("zero duration");
    }

    @Test
    void testValidation_ValidWeekWrapWithAnyTimes() {
        // Week wrap is always valid regardless of times (Friday to Monday with any times)
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry1 =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.FRIDAY)
                        .endDay(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(18, 0))
                        .endTime(LocalTime.of(9, 0))
                        .build();

        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry2 =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.FRIDAY)
                        .endDay(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(18, 0))
                        .build();

        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .sessionSchedule(entry1)
                        .sessionSchedule(entry2)
                        .build())
                .build();

        Clock clock = getTestClock(9, 12, 0);

        // Should not throw - both configurations are valid for week wraps
        FixSessionScheduleManager schedule = new FixSessionScheduleManager(settings, clock);
        assertThat(schedule).isNotNull();
    }

    @Test
    void testValidation_ValidSameDayNormalSession() {
        // Same day with start before end is valid (normal daily session)
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.MONDAY)
                        .endDay(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build();

        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .sessionSchedule(entry)
                        .build())
                .build();

        Clock clock = getTestClock(9, 12, 0);

        // Should not throw - valid configuration
        FixSessionScheduleManager schedule = new FixSessionScheduleManager(settings, clock);
        assertThat(schedule).isNotNull();
    }

    @Test
    void testValidation_ValidSameDayMidnightWrap() {
        // Same day with start after end is valid (midnight wrap)
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.MONDAY)
                        .endDay(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(22, 0))
                        .endTime(LocalTime.of(2, 0))
                        .build();

        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .sessionSchedule(entry)
                        .build())
                .build();

        Clock clock = getTestClock(9, 12, 0);

        // Should not throw - valid midnight wrap configuration
        FixSessionScheduleManager schedule = new FixSessionScheduleManager(settings, clock);
        assertThat(schedule).isNotNull();
    }

    @Test
    void testValidation_ValidMultiDayWithProperTimes() {
        // Multi-day with start time before end time is valid
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.MONDAY)
                        .endDay(DayOfWeek.FRIDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build();

        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .sessionSchedule(entry)
                        .build())
                .build();

        Clock clock = getTestClock(9, 12, 0);

        // Should not throw - valid configuration
        FixSessionScheduleManager schedule = new FixSessionScheduleManager(settings, clock);
        assertThat(schedule).isNotNull();
    }

    @Test
    void testValidation_ValidMultiDayWithReversedTimes() {
        // Multi-day with start time AFTER end time is also valid (e.g., Monday 18:00 to Friday 09:00)
        // This means session starts Monday evening and ends Friday morning
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.MONDAY)
                        .endDay(DayOfWeek.FRIDAY)
                        .startTime(LocalTime.of(18, 0))  // Start Monday evening
                        .endTime(LocalTime.of(9, 0))     // End Friday morning
                        .build();

        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .sessionSchedule(entry)
                        .build())
                .build();

        Clock clock = getTestClock(9, 12, 0);

        // Should not throw - valid configuration for multi-day sessions
        FixSessionScheduleManager schedule = new FixSessionScheduleManager(settings, clock);
        assertThat(schedule).isNotNull();
    }

    @Test
    void testGetSessionTimeLeft_DifferentTimezones() {
        // Create a schedule in New York timezone: Monday 09:00 to Monday 17:00 EST/EDT
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.MONDAY)
                        .endDay(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build();

        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("America/New_York"))  // EST/EDT
                        .sessionSchedule(entry)
                        .build())
                .build();

        // Test case 1: Current time is 14:00 UTC on Monday (March 9, 2026)
        // In America/New_York this is 10:00 EDT (UTC-4, daylight saving time)
        // Session: 09:00-17:00 EDT, so we're 1 hour in, 7 hours remaining
        Clock clock1 = getTestClock(9, 14, 0);
        FixSessionScheduleManager schedule1 = new FixSessionScheduleManager(settings, clock1);
        long timeLeft1 = schedule1.getSessionTimeLeft();
        // Should be 7 hours until 17:00 EDT (21:00 UTC)
        assertThat(timeLeft1).isCloseTo(Duration.ofHours(7).toMillis(), within(1000L));

        // Test case 2: Current time is 19:00 UTC on Monday
        // In America/New_York this is 15:00 EDT
        // Session: 09:00-17:00 EDT, so 2 hours remaining
        Clock clock2 = getTestClock(9, 19, 0);
        FixSessionScheduleManager schedule2 = new FixSessionScheduleManager(settings, clock2);
        long timeLeft2 = schedule2.getSessionTimeLeft();
        // Should be 2 hours until 17:00 EDT (21:00 UTC)
        assertThat(timeLeft2).isCloseTo(Duration.ofHours(2).toMillis(), within(1000L));

        // Test case 3: Current time is 22:00 UTC on Monday
        // In America/New_York this is 18:00 EDT (outside session)
        Clock clock3 = getTestClock(9, 22, 0);
        FixSessionScheduleManager schedule3 = new FixSessionScheduleManager(settings, clock3);
        long timeLeft3 = schedule3.getSessionTimeLeft();
        // Should be 0 (outside session)
        assertThat(timeLeft3).isZero();

        // Test case 4: Using Tokyo timezone - Monday 18:00 JST (09:00 UTC)
        // Schedule configured for Tokyo time (JST, UTC+9)
        FixSessionSettings settingsTokyo = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("Asia/Tokyo"))  // JST
                        .sessionSchedule(entry)  // Same entry: 09:00-17:00 in local time
                        .build())
                .build();

        // Current time: 09:00 UTC = 18:00 JST (outside session, after 17:00 JST)
        Clock clock4 = getTestClock(9, 9, 0);
        FixSessionScheduleManager schedule4 = new FixSessionScheduleManager(settingsTokyo, clock4);
        long timeLeft4 = schedule4.getSessionTimeLeft();
        // Should be 0 (outside session)
        assertThat(timeLeft4).isZero();

        // Test case 5: Current time is 01:00 UTC = 10:00 JST (within session)
        Clock clock5 = getTestClock(9, 1, 0);
        FixSessionScheduleManager schedule5 = new FixSessionScheduleManager(settingsTokyo, clock5);
        long timeLeft5 = schedule5.getSessionTimeLeft();
        // Should be 7 hours until 17:00 JST (08:00 UTC)
        assertThat(timeLeft5).isCloseTo(Duration.ofHours(7).toMillis(), within(1000L));
    }

    private FixSessionScheduleManager scheduleWith(FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry, Clock clock) {
        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(fixSessionId)
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .sessionSchedule(entry)
                        .build())
                .build();
        return new FixSessionScheduleManager(settings, clock);
    }

    @Test
    void testGetSessionTimeLeft_MultiDayRegularSession_MidSession() {
        // Non-wrapping multi-day session: Monday 09:00 to Friday 17:00 (startDay < endDay)
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.MONDAY)
                        .endDay(DayOfWeek.FRIDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build();

        // Current time: Wednesday 12:00 UTC (March 11, 2026)
        FixSessionScheduleManager schedule = scheduleWith(entry, getTestClock(11, 12, 0));

        // From Wednesday 12:00 to Friday 17:00 = 2 days 5 hours = 53 hours
        assertThat(schedule.getSessionTimeLeft()).isCloseTo(Duration.ofHours(53).toMillis(), within(1000L));
    }

    @Test
    void testGetSessionTimeLeft_MultiDayRegularSession_LastDay() {
        // Non-wrapping multi-day session: Monday 09:00 to Friday 17:00
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.MONDAY)
                        .endDay(DayOfWeek.FRIDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build();

        // Current time: Friday 16:00 UTC (March 13, 2026), one hour before the end
        FixSessionScheduleManager schedule = scheduleWith(entry, getTestClock(13, 16, 0));

        assertThat(schedule.getSessionTimeLeft()).isCloseTo(Duration.ofHours(1).toMillis(), within(1000L));
    }

    @Test
    void testGetSessionTimeLeft_MultiDayRegularSession_OutsideAfterEnd() {
        // Non-wrapping multi-day session: Monday 09:00 to Friday 17:00
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.MONDAY)
                        .endDay(DayOfWeek.FRIDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build();

        // Current time: Saturday 12:00 UTC (March 14, 2026), outside the session
        FixSessionScheduleManager schedule = scheduleWith(entry, getTestClock(14, 12, 0));

        assertThat(schedule.getSessionTimeLeft()).isZero();
    }

    @Test
    void testGetSessionTimeLeft_StartTimeIsInclusive() {
        // Same-day session: Monday 09:00 to Monday 17:00
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.MONDAY)
                        .endDay(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build();

        // Current time exactly at the start (Monday 09:00:00) -> inside, full 8 hours left
        FixSessionScheduleManager schedule = scheduleWith(entry, getTestClock(9, 9, 0));

        assertThat(schedule.isWithinSessionTime()).isTrue();
        assertThat(schedule.getSessionTimeLeft()).isCloseTo(Duration.ofHours(8).toMillis(), within(1000L));
    }

    @Test
    void testGetSessionTimeLeft_EndTimeIsExclusive() {
        // Same-day session: Monday 09:00 to Monday 17:00
        FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry =
                FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(DayOfWeek.MONDAY)
                        .endDay(DayOfWeek.MONDAY)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(17, 0))
                        .build();

        // Current time exactly at the end (Monday 17:00:00) -> outside (end is exclusive)
        FixSessionScheduleManager schedule = scheduleWith(entry, getTestClock(9, 17, 0));

        assertThat(schedule.isWithinSessionTime()).isFalse();
        assertThat(schedule.getSessionTimeLeft()).isZero();
    }

}