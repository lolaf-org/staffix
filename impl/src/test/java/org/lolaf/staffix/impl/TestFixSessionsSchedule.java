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
package org.lolaf.staffix.impl;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.tests.FixMessageFields;
import org.lolaf.staffix.tests.TestingLogger;

import java.time.*;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * These tests drive session-schedule transitions with a manually advanceable clock ({@link
 * org.lolaf.staffix.tests.TestingClock#toAdvanceableTime}) rather than relying on the real wall-clock crossing a window
 * boundary. Deriving windows from {@code LocalDateTime.now()} used to make them flaky: a run close to midnight wrapped
 * {@code minusSeconds}/{@code plusSeconds} into the next/previous day (flipping the entry into same-day-wrap semantics
 * with a mismatched day-of-week), and connect/logon could drift outside the narrow real-time window under load. Here the
 * window is a fixed instant (Monday noon UTC) and time only moves when the test advances the clock, which the running
 * session's periodic schedule check observes deterministically. Only the wall-clock is controlled; nanoTime() keeps
 * tracking real time so heartbeat/timeout scheduling is unaffected.
 *
 * <p>Every positive {@code verify} here is wrapped in an {@code await().untilAsserted(...)}, because what the test can
 * await - a session state change, or a line in the session logger - is not what it is verifying. The application
 * callbacks reach the mock afterwards, on the session's own threads and on whichever side of the socket owns them, so a
 * bare verify is racing them: under a parallel build this class failed on {@code onOutsideSessionTime} having seen only
 * {@code onPreOutsideSessionTime}, and on the acceptor's {@code onLogon} having seen only its {@code validateLogon}.
 * The {@code never()} verifications are deliberately left bare - they assert that nothing has happened yet, at the
 * point the test has reached.
 */
class TestFixSessionsSchedule extends AbstractFixTests {

    private static final ZonedDateTime SESSION_TIME = ZonedDateTime.of(2026, 3, 9, 12, 0, 0, 0, ZoneOffset.UTC); // Monday noon UTC
    private static final LocalTime BASE = SESSION_TIME.toLocalTime();
    /**
     * Named so a call site still says which end of the section 4.4.2 exchange it is configuring.
     */
    private static final boolean INITIATES = true;
    private static final boolean AWAITS = false;

    private static FixSessionSettings.SessionScheduleSettings scheduleWindow(LocalTime start, LocalTime end) {
        return FixSessionSettings.SessionScheduleSettings.builder()
                .timeZone(TimeZone.getTimeZone("UTC"))
                .sessionSchedule(FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(SESSION_TIME.getDayOfWeek())
                        .startTime(start)
                        .endDay(SESSION_TIME.getDayOfWeek())
                        .endTime(end)
                        .build())
                .build();
    }

    /**
     * How many Logon(35=A) messages carrying ResetSeqNumFlag(141)=Y a side has sent, which is one per section 4.4.2
     * reset it started.
     */
    private static long resetLogonsSentBy(TestingLogger logger) {
        return logger.getOutgoingMessages().stream()
                .filter(message -> FixMessageFields.hasFieldWithValue(message, CoreFields.MESSAGE_TYPE, CoreMessageType.LOGON))
                .filter(message -> FixMessageFields.hasFieldWithValue(message, CoreFields.RESET_NUM_FLAG, "Y"))
                .count();
    }

    /**
     * Every day non-stop, rolling at the same time: the merged days cover the whole week, so the session never
     * closes and the reset time is the only boundary it ever sees.
     */
    private static FixSessionSettings.SessionScheduleSettings nonStopEveryDay(
            LocalTime resetTime, boolean initiatesReset) {
        FixSessionSettings.SessionScheduleSettings.SessionScheduleSettingsBuilder builder =
                FixSessionSettings.SessionScheduleSettings.builder().timeZone(TimeZone.getTimeZone("UTC"));
        for (DayOfWeek day : DayOfWeek.values()) {
            builder.nonStopSchedule(FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry.builder()
                    .dayOfWeek(day)
                    .sequenceResetTime(resetTime)
                    .initiatesReset(initiatesReset)
                    .build());
        }
        return builder.build();
    }

    private static FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry nonStopDay(
            DayOfWeek day, LocalTime resetTime, boolean initiatesReset) {
        return FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry.builder()
                .dayOfWeek(day)
                .sequenceResetTime(resetTime)
                .initiatesReset(initiatesReset)
                .build();
    }

    /**
     * A day that never closes and never rolls, which is what a session resetting weekly rather than daily is made of.
     */
    private static FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry nonStopDayWithoutReset(DayOfWeek day) {
        return FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry.builder()
                .dayOfWeek(day)
                .build();
    }

    /**
     * A session non-stop every day is never out of time: the reset hour going by is a point at which the numbering
     * restarts, not a close, so nothing may log out at it, at a midnight, or anywhere after them.
     */
    @Test
    void testNonStopScheduleNeverLeavesSessionTime() {
        fixInitiatorClock.toAdvanceableTime(SESSION_TIME.toInstant());
        setupInitiatorSessionSettings(s -> s
                .sessionScheduleSettings(nonStopEveryDay(BASE.plusSeconds(4),
                        AWAITS))
                .desiredSessionState(FixSessionState.LOGGED_IN)
                .build());

        connectFixInitiatorAndAcceptor();
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());

        // straight past the reset hour and a long way beyond it
        fixInitiatorClock.advance(Duration.ofHours(30));
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));

        verify(fixInitiatorApplication, never()).onOutsideSessionTime(any(FixSession.class));
        assertThat(initiatorLogger.getEvents()).doesNotContain("FIX session outside of timeframe");
        assertThat(fixInitiatorSession.isLoggedIn()).isTrue();
    }

    /**
     * The roll itself, section 4.4.2: at the appointed time the end that agreed to start it resets over the live
     * connection and the peer follows, both ending at NextNumIn = 2 and NextNumOut = 2 without anything logging out.
     */
    @Test
    void testNonStopScheduleResetsTheSequenceAtTheAppointedTime() {
        fixInitiatorClock.toAdvanceableTime(SESSION_TIME.toInstant());
        setupInitiatorSessionSettings(s -> s
                .sessionScheduleSettings(nonStopEveryDay(BASE.plusSeconds(4),
                        INITIATES))
                // this is the one test here asserting exact sequence numbers, so nothing but the reset may be on the wire.
                .heartBeatInterval(FixSessionSettings.HeartbeatInterval.builder()
                        .initiatorInterval(Duration.ofSeconds(20))
                        .build())
                .desiredSessionState(FixSessionState.LOGGED_IN)
                .build());

        connectFixInitiatorAndAcceptor();
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());
        await().untilAsserted(() -> assertThat(fixAcceptorSession.isLoggedIn()).isTrue());

        fixInitiatorClock.advance(Duration.ofSeconds(5));

        // "upon completion of the session reset, both peers must have NextNumIn = 2 and NextNumOut = 2"
        await().untilAsserted(() -> assertThat(initiatorMessagesStore.getOutgoingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(initiatorMessagesStore.getIncomingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(acceptorMessagesStore.getOutgoingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(acceptorMessagesStore.getIncomingSeqNum()).isEqualTo(2));

        // and the connection carried it, rather than a logout and a fresh session
        verify(fixInitiatorApplication, never()).onOutsideSessionTime(any(FixSession.class));
        assertThat(fixInitiatorSession.isLoggedIn()).isTrue();
    }

    /**
     * The schedule check runs about once a second, so the crossing has to be consumed: one reset for the boundary,
     * not one per tick for as long as the clock stays past it.
     */
    @Test
    void testNonStopScheduleDoesNotResetTwiceForTheSameBoundary() {
        fixInitiatorClock.toAdvanceableTime(SESSION_TIME.toInstant());
        setupInitiatorSessionSettings(s -> s
                .sessionScheduleSettings(nonStopEveryDay(BASE.plusSeconds(4),
                        INITIATES))
                .desiredSessionState(FixSessionState.LOGGED_IN)
                .build());

        connectFixInitiatorAndAcceptor();
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());

        fixInitiatorClock.advance(Duration.ofSeconds(5));
        await().untilAsserted(() -> assertThat(resetLogonsSentBy(initiatorLogger)).isEqualTo(1));

        // several more checks go by with the clock still past the reset time
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(3));

        // counted rather than read off the sequence numbers: heartbeats are traffic too, so "still at 2" is a
        // statement about the session being idle rather than about the reset having happened once
        assertThat(resetLogonsSentBy(initiatorLogger))
                .as("crossing the reset time must produce one reset, not one per schedule check")
                .isEqualTo(1);
    }

    /**
     * The end that agreed to wait sends nothing of its own: its half of 4.4.2 is to answer the Logon when it comes.
     */
    @Test
    void testNonStopScheduleAwaitingTheResetDoesNotInitiateIt() {
        fixInitiatorClock.toAdvanceableTime(SESSION_TIME.toInstant());
        setupInitiatorSessionSettings(s -> s
                .sessionScheduleSettings(nonStopEveryDay(BASE.plusSeconds(4),
                        AWAITS))
                .desiredSessionState(FixSessionState.LOGGED_IN)
                .build());

        connectFixInitiatorAndAcceptor();
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());
        fixInitiatorClock.advance(Duration.ofSeconds(5));
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(3));

        // its half of 4.4.2 is to answer the peer's Logon, never to send one of its own
        assertThat(resetLogonsSentBy(initiatorLogger))
                .as("the end awaiting the reset must not initiate one")
                .isZero();
    }

    /**
     * A session brought up after the day's reset time has missed it, and must not perform one on the spot - the
     * counterparties agreed on a time, not on "whenever this session happens to start".
     */
    @Test
    void testNonStopScheduleStartedAfterTheResetTimeDoesNotResetImmediately() {
        // the clock starts an hour past the reset time
        fixInitiatorClock.toAdvanceableTime(SESSION_TIME.plusHours(1).toInstant());
        setupInitiatorSessionSettings(s -> s
                .sessionScheduleSettings(nonStopEveryDay(BASE,
                        INITIATES))
                .desiredSessionState(FixSessionState.LOGGED_IN)
                .build());

        connectFixInitiatorAndAcceptor();
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(3));

        // the Logon exchange spent number 1 on each side and nothing put it back
        assertThat(initiatorMessagesStore.getOutgoingSeqNum()).isEqualTo(2);
        assertThat(initiatorMessagesStore.getIncomingSeqNum()).isEqualTo(2);
    }

    /**
     * The case the two lists exist for: non-stop from Monday through Thursday, then a Friday that keeps a window and
     * closes at its end.
     * <p>
     * What the merging of the days buys is asserted a second before a midnight rather than after it: adjacent days
     * leave no instant uncovered either way, so a session that had not merged them would still be up on the far
     * side. It would however be one second from the end of its interval, and so would announce a close through
     * {@link FixApplication#onPreOutsideSessionTime} that never comes. Merged, the session is days from an end and
     * says nothing - at the midnights between non-stop days, and at the one where the Friday window takes over.
     */
    @Test
    void testNonStopDaysRunIntoEachOtherAndCloseOnAWindowedDay() {
        fixInitiatorClock.toAdvanceableTime(SESSION_TIME.toInstant());
        setupInitiatorSessionSettings(s -> s
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .nonStopSchedule(nonStopDay(DayOfWeek.MONDAY, BASE.plusSeconds(4), AWAITS))
                        .nonStopSchedule(nonStopDay(DayOfWeek.TUESDAY, BASE, AWAITS))
                        .nonStopSchedule(nonStopDay(DayOfWeek.WEDNESDAY, BASE, AWAITS))
                        .nonStopSchedule(nonStopDay(DayOfWeek.THURSDAY, BASE, AWAITS))
                        .sessionSchedule(FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                                .startDay(DayOfWeek.FRIDAY).startTime(LocalTime.MIDNIGHT)
                                .endDay(DayOfWeek.FRIDAY).endTime(LocalTime.of(17, 0))
                                .build())
                        .build())
                .desiredSessionState(FixSessionState.LOGGED_IN)
                .build());

        connectFixInitiatorAndAcceptor();
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());

        // one second before the Monday midnight, where an unmerged Monday would be one second from its end
        fixInitiatorClock.advance(Duration.ofHours(11).plusMinutes(59).plusSeconds(59));
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));

        verify(fixInitiatorApplication, never()).onPreOutsideSessionTime(any(FixSession.class), any(Duration.class));
        verify(fixInitiatorApplication, never()).onOutsideSessionTime(any(FixSession.class));
        assertThat(fixInitiatorSession.isLoggedIn()).isTrue();

        // and again a second before the Thursday midnight, the one where the Friday window takes over
        fixInitiatorClock.advance(Duration.ofDays(3));
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));

        verify(fixInitiatorApplication, never()).onPreOutsideSessionTime(any(FixSession.class), any(Duration.class));
        verify(fixInitiatorApplication, never()).onOutsideSessionTime(any(FixSession.class));
        assertThat(initiatorLogger.getEvents()).doesNotContain("FIX session outside of timeframe");
        assertThat(fixInitiatorSession.isLoggedIn()).isTrue();

        // past 17:00 on the Friday, where the window - and with it the week - genuinely ends
        fixInitiatorClock.advance(Duration.ofHours(18));

        await().untilAsserted(() -> assertThat(initiatorLogger.getEvents()).contains("FIX session outside of timeframe"));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onOutsideSessionTime(fixInitiatorSession));
    }

    /**
     * A day named by neither list is a day the session is down, which is what makes the two lists a division of the
     * week rather than a pair of overlapping hints.
     */
    @Test
    void testADayInNeitherListClosesTheSession() {
        fixInitiatorClock.toAdvanceableTime(SESSION_TIME.toInstant());
        setupInitiatorSessionSettings(s -> s
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .nonStopSchedule(nonStopDay(DayOfWeek.MONDAY, BASE.plusSeconds(4),
                                AWAITS))
                        .build())
                .desiredSessionState(FixSessionState.LOGGED_IN)
                .build());

        connectFixInitiatorAndAcceptor();
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());

        // Monday is the only day scheduled, so its midnight is the close
        fixInitiatorClock.advance(Duration.ofHours(13));

        await().untilAsserted(() -> assertThat(initiatorLogger.getEvents()).contains("FIX session outside of timeframe"));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onOutsideSessionTime(fixInitiatorSession));
    }

    /**
     * A session up around the clock all week that rolls on one day only - the weekly reset, which is inexpressible if
     * every non-stop day has to name a reset time. Monday's hour goes by and nothing rolls, because Monday says
     * nothing about rolling.
     */
    @Test
    void testANonStopDayWithoutAResetTimeDoesNotRoll() {
        fixInitiatorClock.toAdvanceableTime(SESSION_TIME.toInstant());
        FixSessionSettings.SessionScheduleSettings.SessionScheduleSettingsBuilder schedule =
                FixSessionSettings.SessionScheduleSettings.builder().timeZone(TimeZone.getTimeZone("UTC"));
        for (DayOfWeek day : DayOfWeek.values()) {
            // Sunday alone carries the roll; every other day is non-stop and silent about resetting
            schedule.nonStopSchedule(day.equals(DayOfWeek.SUNDAY)
                    ? nonStopDay(day, BASE, INITIATES)
                    : nonStopDayWithoutReset(day));
        }
        setupInitiatorSessionSettings(s -> s
                .sessionScheduleSettings(schedule.build())
                .desiredSessionState(FixSessionState.LOGGED_IN)
                .build());

        connectFixInitiatorAndAcceptor();
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());

        // well past the hour Sunday rolls at, but on a Monday, and on into the Tuesday
        fixInitiatorClock.advance(Duration.ofHours(30));
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));

        assertThat(resetLogonsSentBy(initiatorLogger))
                .as("a non-stop day that names no reset time must not roll")
                .isZero();
        assertThat(fixInitiatorSession.isLoggedIn()).isTrue();
    }

    /**
     * The one test in this class that lets the real wall-clock run, and it is deliberate.
     * <p>
     * Everything else here drives {@link org.lolaf.staffix.tests.TestingClock} so a boundary is crossed on demand,
     * which pins the schedule's semantics but stubs out the thing that makes them happen: the periodic check being
     * scheduled at {@code withinSessionTimeCheckInterval} and firing on its own. That wiring is what this covers, so
     * it asserts only that a reset occurred - the shape of the reset is settled by the driven-clock tests above.
     * <p>
     * All seven days carry the same reset time so the test cannot be broken by running across a midnight: the entry
     * that fires is then the next day's, which says the same thing.
     */
    @Test
    void testTheScheduleRollsOnTheRealClock() {
        // far enough ahead that logon lands first, close enough to keep the test short
        Duration margin = Duration.ofSeconds(3);
        ZonedDateTime resetAt = ZonedDateTime.now(ZoneOffset.UTC).plus(margin);
        FixSessionSettings.SessionScheduleSettings.SessionScheduleSettingsBuilder schedule =
                FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        // the floor is 50ms, see FixSessionImpl.calculateInitialSessionTimeCheckDelay
                        .withinSessionTimeCheckInterval(Duration.ofMillis(100));
        for (DayOfWeek day : DayOfWeek.values()) {
            schedule.nonStopSchedule(nonStopDay(day, resetAt.toLocalTime(), INITIATES));
        }
        setupInitiatorSessionSettings(s -> s
                .sessionScheduleSettings(schedule.build())
                .desiredSessionState(FixSessionState.LOGGED_IN)
                .build());

        connectFixInitiatorAndAcceptor();
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());
        await().untilAsserted(() -> assertThat(fixAcceptorSession.isLoggedIn()).isTrue());

        // A crossing is consumed whether or not the session is logged in, so a logon slower than the margin means the
        // roll is skipped and the failure below would read as "the schedule never fired" - a bug, not a slow machine.
        // Fail here instead, naming the cause, rather than leaving the next reader to find it.
        assertThat(ZonedDateTime.now(ZoneOffset.UTC))
                .as("logon took longer than the %s margin, so the crossing was consumed while logged out and this "
                        + "run cannot observe the roll - raise the margin rather than reading the assertion below", margin)
                .isBefore(resetAt);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(resetLogonsSentBy(initiatorLogger))
                .as("the periodic schedule check must reach the reset time on its own and roll once")
                .isEqualTo(1));

        await().untilAsserted(() -> assertThat(initiatorMessagesStore.getOutgoingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(initiatorMessagesStore.getIncomingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(acceptorMessagesStore.getOutgoingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(acceptorMessagesStore.getIncomingSeqNum()).isEqualTo(2));
    }

    /**
     * Two non-stop days rolling at times of their own, which is the whole reason the entries are held per day: one
     * reset on the Monday at its time, a second on the Tuesday at a different one.
     */
    @Test
    void testNonStopDaysRollAtTheirOwnTimes() {
        fixInitiatorClock.toAdvanceableTime(SESSION_TIME.toInstant());
        FixSessionSettings.SessionScheduleSettings.SessionScheduleSettingsBuilder schedule =
                FixSessionSettings.SessionScheduleSettings.builder().timeZone(TimeZone.getTimeZone("UTC"));
        for (DayOfWeek day : DayOfWeek.values()) {
            // every day is non-stop so the session never closes; Monday and Tuesday roll at times of their own, and
            // the rest do not roll at all rather than rolling at some hour picked for being out of the way
            if (day.equals(DayOfWeek.MONDAY)) {
                schedule.nonStopSchedule(nonStopDay(day, BASE.plusSeconds(4), INITIATES));
            } else if (day.equals(DayOfWeek.TUESDAY)) {
                schedule.nonStopSchedule(nonStopDay(day, LocalTime.of(6, 0), INITIATES));
            } else {
                schedule.nonStopSchedule(nonStopDayWithoutReset(day));
            }
        }
        setupInitiatorSessionSettings(s -> s
                .sessionScheduleSettings(schedule.build())
                .desiredSessionState(FixSessionState.LOGGED_IN)
                .build());

        connectFixInitiatorAndAcceptor();
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());

        fixInitiatorClock.advance(Duration.ofSeconds(5));
        await().untilAsserted(() -> assertThat(resetLogonsSentBy(initiatorLogger)).isEqualTo(1));

        // on to Tuesday 06:00, which is that day's own reset time and not Monday's
        fixInitiatorClock.advance(Duration.ofHours(18));
        await().untilAsserted(() -> assertThat(resetLogonsSentBy(initiatorLogger))
                .as("each non-stop day rolls at the time configured for it")
                .isEqualTo(2));
    }

    @Test
    void testAutomaticLogonLogoutTriggersSequenceResetIfNeeded() {
        // Pin the initiator clock inside the session window so the automatic logon happens deterministically.
        fixInitiatorClock.toFixedTime(SESSION_TIME.toInstant());

        setupInitiatorSessionSettings(s ->
                s.sessionScheduleSettings(scheduleWindow(BASE.minusSeconds(2), BASE.plusSeconds(60)))
                        .resetSeqNumOnLogon(true)
                        .desiredSessionState(FixSessionState.LOGGED_IN)
                        .build());

        connectFixInitiatorAndAcceptor();

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isTrue());

        fixInitiatorSession.logon();

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());

        await().untilAsserted(() -> verify(fixAcceptorApplication)
                .onLogon(any(FixSession.class), argThat(messageFieldReceived(CoreFields.RESET_NUM_FLAG, "Y"))));
    }

    @Test
    void testAcceptorAutomaticLogoutWhenOutsideOfSessionTime() {
        // Start the acceptor clock inside the window so logon succeeds, then advance past the end to trigger the
        // automatic logout coming from the acceptor.
        fixAcceptorClock.toAdvanceableTime(SESSION_TIME.toInstant());

        setupAcceptorSessionSettings(s ->
                s.sessionScheduleSettings(scheduleWindow(BASE.minusSeconds(2), BASE.plusSeconds(3)))
                        .desiredSessionState(FixSessionState.LOGGED_IN)
                        .build());

        connectFixInitiatorAndAcceptor();

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isTrue());

        fixInitiatorSession.logon();

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());

        // move the acceptor clock past the end of the session window, it should trigger an automatic logout
        fixAcceptorClock.advance(Duration.ofSeconds(4));

        await().untilAsserted(() -> assertThat(acceptorLogger.getEvents()).contains("FIX session outside of timeframe"));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onOutsideSessionTime(fixAcceptorSession));

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isFalse());
        await().untilAsserted(() -> assertThat(fixAcceptorSession.isLoggedIn()).isFalse());
        await().untilAsserted(() -> verify(fixInitiatorApplication)
                .onLogout(any(FixSession.class), eq("Outside of session timeframe"), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication)
                .onLogout(any(FixSession.class), eq("Outside of session timeframe"), any(DecodedFixMessage.class)));
    }

    @Test
    void testAutomaticLogonLogoutWhenInsideOrOutsideOfSessionTime() {
        // Start the initiator clock before the window opens, then advance into and past it to drive the automatic
        // logon (on entering the window) and automatic logout (on leaving it).
        fixInitiatorClock.toAdvanceableTime(SESSION_TIME.minusSeconds(2).toInstant());

        setupInitiatorSessionSettings(s ->
                s.sessionScheduleSettings(scheduleWindow(BASE, BASE.plusSeconds(4)))
                        .desiredSessionState(FixSessionState.LOGGED_IN)
                        .build());

        startFixInitiatorAndAcceptor();

        // an initiator whose window has not opened yet has no Logon to send, so it does not dial at all
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
        assertThat(fixInitiatorSession.isConnected()).isFalse();

        // enter the session window: the initiator dials, and inside-of-timeframe triggers the automatic logon
        // (desired state LOGGED_IN)
        fixInitiatorClock.advance(Duration.ofSeconds(3));

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isTrue());
        await().untilAsserted(() -> assertThat(initiatorLogger.getEvents()).contains("FIX session inside of timeframe"));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onInsideSessionTime(fixInitiatorSession));
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());

        // leave the session window: outside-of-timeframe triggers the automatic logout
        clearInvocations(fixInitiatorApplication, fixAcceptorApplication);
        fixInitiatorClock.advance(Duration.ofSeconds(4));

        await().untilAsserted(() -> assertThat(initiatorLogger.getEvents()).contains("FIX session outside of timeframe"));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onOutsideSessionTime(fixInitiatorSession));

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isFalse());
        await().untilAsserted(() -> verify(fixInitiatorApplication)
                .onLogout(any(FixSession.class), eq("Outside of session timeframe"), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication)
                .onLogout(any(FixSession.class), eq("Outside of session timeframe"), any(DecodedFixMessage.class)));

        // the logout takes the connection down with it, and in the shadow of the closed window nothing dials again:
        // a session that would send no Logon must not spend the downtime connecting to be dropped
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isFalse());
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
        assertThat(fixInitiatorSession.isConnected()).isFalse();

        // re-enter the window, which for this weekly one (start day and end day are both the Monday of SESSION_TIME)
        // is seven days on, and the clock lands one second inside it. Time only moves when this test moves it, so the
        // three seconds left of the window cannot run out while the session reconnects and logs back on.
        clearInvocations(fixInitiatorApplication, fixAcceptorApplication);
        fixInitiatorClock.advance(Duration.ofDays(7).minusSeconds(4));

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isLoggedIn()).isTrue());
        await().untilAsserted(() -> verify(fixInitiatorApplication).onInsideSessionTime(fixInitiatorSession));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
    }

    @Test
    void testNoAutomaticLogonLogoutWhenInsideOrOutsideOfSessionTimeWithDesiredSessionStateLoggedOut() {
        fixInitiatorClock.toAdvanceableTime(SESSION_TIME.minusSeconds(2).toInstant());

        setupInitiatorSessionSettings(s ->
                s.sessionScheduleSettings(scheduleWindow(BASE, BASE.plusSeconds(4)))
                        .desiredSessionState(FixSessionState.LOGGED_OUT)
                        .build());

        startFixInitiatorAndAcceptor();

        // enter the session window: wanting to stay logged out is no reason not to hold a connection, so the
        // initiator dials as soon as the window opens - it just has no Logon to send once there
        fixInitiatorClock.advance(Duration.ofSeconds(3));

        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isTrue());
        await().untilAsserted(() -> assertThat(initiatorLogger.getEvents())
                .contains("FIX session inside of timeframe"));
        await().untilAsserted(() -> verify(fixInitiatorApplication).onInsideSessionTime(fixInitiatorSession));

        // we have desired state LOGGED_OUT, so no automatic logon should happen even though we are inside the window
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
        assertThat(fixInitiatorSession.isLoggedIn()).isFalse();
    }
}
