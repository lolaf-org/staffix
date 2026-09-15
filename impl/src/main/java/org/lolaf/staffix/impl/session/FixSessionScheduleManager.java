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

import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalField;
import java.util.*;
import java.util.function.Supplier;

/**
 * Answers when the session is up and when its sequence numbers restart on a timer.
 * <p>
 * Both questions are settled against one representation: the week reduced to a set of half-open intervals of
 * milliseconds-since-Monday-00:00 during which the session is up. A {@link
 * FixSessionSettings.SessionScheduleSettings.ScheduleEntry} contributes the interval between its open and its close;
 * a {@link FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry} contributes its whole day. Merging those
 * intervals is what makes a run of non-stop days contiguous - midnight between two of them is not a close, because
 * the two intervals touch and become one - and what lets a non-stop day sit flush against a window that ends at, or
 * starts at, that midnight.
 * <p>
 * Time arithmetic here is wall-clock in the configured zone rather than elapsed: on the two days a year the zone
 * changes offset, the reported time left until a close differs from true elapsed time by the offset change. It does
 * not move the close itself, which is a local time, nor the instant the pre-trigger fires, which is reached from a
 * few seconds out with the transition already behind it.
 */
public class FixSessionScheduleManager {

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;
    private static final long WEEK_MILLIS = 7 * DAY_MILLIS;

    private final ZonedDateTimeProvider zonedDateTimeProvider;
    private final boolean enabled;
    /**
     * The intervals of the week during which the session is up, sorted, merged and normalised into [0, WEEK_MILLIS).
     * Empty when the session has no schedule at all, which means always up rather than never.
     */
    private final long[] upIntervalStarts;
    private final long[] upIntervalEnds;
    /**
     * True when the merged intervals cover the whole week, so the session never closes even though it is scheduled.
     * A week of non-stop days is the case that gets here.
     */
    private final boolean coversWholeWeek;
    /**
     * The non-stop entry for each day, indexed by {@link DayOfWeek#getValue()}, or null on a day that is not
     * non-stop. At most one entry per day, so a crossing is consumed against a single date.
     */
    private final FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry[] nonStopByDay =
            new FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry[DayOfWeek.values().length + 1];
    /**
     * The date this manager last reported a sequence reset as due, so that one crossing of the reset time produces
     * one reset rather than one per check - the check runs every second by default.
     */
    private LocalDate lastSequenceResetDate;

    public FixSessionScheduleManager(FixSessionSettings settings, Clock clock) {
        FixSessionSettings.SessionScheduleSettings scheduleSettings = settings.getSessionScheduleSettings();
        zonedDateTimeProvider = new ZonedDateTimeProvider(scheduleSettings.getTimeZone(), clock);
        validateScheduleSettings(scheduleSettings);

        for (FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry entry : scheduleSettings.getNonStopSchedules()) {
            nonStopByDay[entry.getDayOfWeek().getValue()] = entry;
        }
        // a non-stop schedule has no windows but still needs the periodic check to run, since that is what notices
        // the reset time going by
        enabled = !scheduleSettings.getSessionSchedules().isEmpty() || !scheduleSettings.getNonStopSchedules().isEmpty();

        List<long[]> merged = mergeIntervals(collectIntervals(scheduleSettings));
        upIntervalStarts = new long[merged.size()];
        upIntervalEnds = new long[merged.size()];
        for (int i = 0; i < merged.size(); i++) {
            upIntervalStarts[i] = merged.get(i)[0];
            upIntervalEnds[i] = merged.get(i)[1];
        }
        coversWholeWeek = merged.size() == 1 && merged.get(0)[0] == 0 && merged.get(0)[1] == WEEK_MILLIS;

        ZonedDateTime now = zonedDateTimeProvider.get();
        FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry today = nonStopByDay[now.getDayOfWeek().getValue()];
        if (today != null && today.getSequenceResetTime() != null
                && !now.toLocalTime().isBefore(today.getSequenceResetTime())) {
            // a session brought up after today's reset time has already missed it: treating it as due would perform a
            // reset the counterparties never agreed to, moments after the session came up
            lastSequenceResetDate = now.toLocalDate();
        }
    }

    /**
     * Turns every entry into a half-open interval of milliseconds-since-Monday-00:00, splitting the ones that run
     * over the end of the week so that everything downstream deals in plain ordered intervals.
     */
    private static List<long[]> collectIntervals(FixSessionSettings.SessionScheduleSettings scheduleSettings) {
        List<long[]> intervals = new ArrayList<>();
        for (FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry : scheduleSettings.getSessionSchedules()) {
            long start = millisOfWeek(entry.getStartDay(), entry.getStartTime());
            addInterval(intervals, start, entryLength(entry));
        }
        for (FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry entry : scheduleSettings.getNonStopSchedules()) {
            addInterval(intervals, millisOfWeek(entry.getDayOfWeek(), LocalTime.MIDNIGHT), DAY_MILLIS);
        }
        return intervals;
    }

    /**
     * How long a window entry stays open, which is where its three shapes are told apart:
     * <ul>
     *     <li>the same day with the start before the end - Monday 09:00 to Monday 17:00 - is those hours;</li>
     *     <li>the same day with the start after the end - Monday 22:00 to Monday 02:00 - runs over one midnight into
     *     the next day, and is deliberately not read as running for the rest of the week;</li>
     *     <li>different days - Monday 09:00 to Friday 17:00, or Friday 18:00 to Monday 08:00 - runs to that day's
     *     end time, over the end of the week if the end day comes first.</li>
     * </ul>
     * The same day with equal times is a zero-duration entry, rejected before this is reached.
     */
    private static long entryLength(FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry) {
        long startOfDay = entry.getStartTime().toNanoOfDay() / 1_000_000;
        long endOfDay = entry.getEndTime().toNanoOfDay() / 1_000_000;
        if (entry.getStartDay() == entry.getEndDay()) {
            return startOfDay < endOfDay ? endOfDay - startOfDay : DAY_MILLIS - startOfDay + endOfDay;
        }
        long start = millisOfWeek(entry.getStartDay(), entry.getStartTime());
        long end = millisOfWeek(entry.getEndDay(), entry.getEndTime());
        return end > start ? end - start : WEEK_MILLIS - start + end;
    }

    /**
     * Every day a window entry touches, which is what says whether it collides with a day claimed as non-stop. A
     * window ending exactly at a midnight does not touch the day it ends on, the interval being half-open.
     */
    static Set<DayOfWeek> coveredDays(FixSessionSettings.SessionScheduleSettings.ScheduleEntry entry) {
        long start = millisOfWeek(entry.getStartDay(), entry.getStartTime());
        long end = start + entryLength(entry);
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (long at = start; at < end; at = (at / DAY_MILLIS + 1) * DAY_MILLIS) {
            days.add(DayOfWeek.of((int) (at % WEEK_MILLIS / DAY_MILLIS) + 1));
        }
        return days;
    }

    private static void addInterval(List<long[]> intervals, long start, long length) {
        long end = start + length;
        if (end <= WEEK_MILLIS) {
            intervals.add(new long[]{start, end});
        } else {
            // runs over the end of the week: the tail becomes an interval of its own at the start of the week
            intervals.add(new long[]{start, WEEK_MILLIS});
            intervals.add(new long[]{0, end - WEEK_MILLIS});
        }
    }

    /**
     * Sorts and merges, joining intervals that overlap and intervals that merely touch. Touching is the case that
     * matters: it is what makes Monday-non-stop followed by Tuesday-non-stop one uninterrupted run rather than two
     * days with a close at the midnight between them.
     */
    private static List<long[]> mergeIntervals(List<long[]> intervals) {
        intervals.sort(Comparator.comparingLong(interval -> interval[0]));
        List<long[]> merged = new ArrayList<>(intervals.size());
        for (long[] interval : intervals) {
            if (!merged.isEmpty() && interval[0] <= merged.get(merged.size() - 1)[1]) {
                long[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], interval[1]);
            } else {
                merged.add(new long[]{interval[0], interval[1]});
            }
        }
        return merged;
    }

    private static long millisOfWeek(ZonedDateTime time) {
        return millisOfWeek(time.getDayOfWeek(), time.toLocalTime());
    }

    private static long millisOfWeek(DayOfWeek day, LocalTime time) {
        return (day.getValue() - 1) * DAY_MILLIS + time.toNanoOfDay() / 1_000_000;
    }

    /**
     * The sequence reset that has just fallen due, or null when none has.
     * <p>
     * Answers non-null once per crossing of a day's reset time: the caller is expected to perform the reset, so
     * answering twice for the same boundary would reset twice. Calling it consumes the crossing whether or not the
     * entry has this end initiating - the end that waits has nothing to do with the crossing but must not
     * accumulate it either.
     */
    FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry dueSequenceReset() {
        ZonedDateTime now = zonedDateTimeProvider.get();
        FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry entry = nonStopByDay[now.getDayOfWeek().getValue()];
        if (entry == null || entry.getSequenceResetTime() == null
                || now.toLocalTime().isBefore(entry.getSequenceResetTime())) {
            // no entry, or a day that is non-stop without rolling, so there is no crossing to consume
            return null;
        }
        LocalDate today = now.toLocalDate();
        if (today.equals(lastSequenceResetDate)) {
            return null;
        }
        lastSequenceResetDate = today;
        return entry;
    }

    private void validateScheduleSettings(FixSessionSettings.SessionScheduleSettings scheduleSettings) {
        for (String violation : FixSessionSettingsValidator.scheduleViolations(scheduleSettings)) {
            throw new IllegalArgumentException("Invalid schedule: " + violation);
        }
    }

    boolean isEnabled() {
        return enabled;
    }

    /**
     * Milliseconds until the session next closes, {@link Long#MAX_VALUE} when it never does, and 0 when it is
     * closed right now.
     */
    long getSessionTimeLeft() {
        if (!enabled || coversWholeWeek) {
            // an unscheduled session and a session non-stop all week are both never near an end, which is what keeps
            // onPreOutsideSessionTime and onOutsideSessionTime - and so the logout they lead to - from ever firing
            return Long.MAX_VALUE;
        }
        long nowMillisOfWeek = millisOfWeek(zonedDateTimeProvider.get());
        for (int i = 0; i < upIntervalStarts.length; i++) {
            if (nowMillisOfWeek >= upIntervalStarts[i] && nowMillisOfWeek < upIntervalEnds[i]) {
                long end = upIntervalEnds[i];
                if (end == WEEK_MILLIS && upIntervalStarts[0] == 0) {
                    // the last interval of the week runs into the first: the close is that one's end, a week boundary
                    // away, not Sunday midnight
                    end = WEEK_MILLIS + upIntervalEnds[0];
                }
                return end - nowMillisOfWeek;
            }
        }
        // not within any interval: the session is outside its timeframe
        return 0;
    }

    boolean isWithinSessionTime() {
        return getSessionTimeLeft() > 0;
    }

    @RequiredArgsConstructor
    private static class ZonedDateTimeProvider implements TemporalAccessor, Supplier<ZonedDateTime> {

        private final TimeZone timeZone;
        private final Clock clock;
        private UTCTime utcTime;

        @Override
        public ZonedDateTime get() {
            utcTime = clock.now();
            return ZonedDateTime.from(this);
        }

        @Override
        public boolean isSupported(TemporalField field) {
            if (field == ChronoField.OFFSET_SECONDS) {
                return true;
            }
            if (field == ChronoField.INSTANT_SECONDS) {
                return true;
            }
            return field == ChronoField.NANO_OF_SECOND;
        }

        @Override
        public long getLong(TemporalField field) {
            if (field == ChronoField.OFFSET_SECONDS) {
                return timeZone.getOffset(utcTime.toEpochMillis()) / 1000;
            }
            if (field == ChronoField.INSTANT_SECONDS) {
                return utcTime.getEpochSeconds();
            }
            if (field == ChronoField.NANO_OF_SECOND) {
                return utcTime.getNanosOfSecond();
            }
            throw new IllegalStateException("Unexpected call: " + field);
        }
    }
}
