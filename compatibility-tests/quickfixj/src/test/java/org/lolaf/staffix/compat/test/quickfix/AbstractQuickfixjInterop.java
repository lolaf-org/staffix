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

package org.lolaf.staffix.compat.test.quickfix;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.ResendRequestRange;
import org.lolaf.staffix.tests.FixMessageFields;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;

/**
 * The deep QuickFIX/J interoperability scenarios: retransmission, sequence reset, schedules and the
 * NextExpectedMsgSeqNum(789) recovery, each written once against {@link AbstractQuickfixjHarness} and run in both
 * directions and under both FIX versions - FIX.4.4 here, FIXT.1.1 carrying FIX 5.0 SP2 in
 * {@link AbstractQuickfixjFixtInterop}.
 * <p>
 * Logon and one Email each way live in {@link AbstractQuickfixjSmokeInterop}, inherited from here and run against
 * every other version staffix ships. What is below is session-layer behaviour, the same code whatever the
 * application dictionary, so it is proven in depth on two versions rather than on all six.
 * <p>
 * <b>Not covered here yet.</b> This class replaced two older ones, {@code TestFixInitiatorImpl} and
 * {@code TestFixAcceptorImpl}, which had been {@code @Disabled} against an API that no longer exists. Everything they
 * asserted is now covered either by a scenario below or - where the behaviour is staffix's own rather than a matter of
 * interoperability, such as offline sends, broadcasts or {@code onMessageSendingFailure} - by {@code impl}.
 * These are what they stood for and nothing yet pins against a second implementation, listed so that retiring them did
 * not quietly drop the intent:
 * <ul>
 *     <li><b>staffix accepting a SequenceReset-GapFill produced by QuickFIX/J</b>, the mirror of
 *     {@link #testStaffixGapFillsTheMessagesItsApplicationWithholds}. staffix is only ever the sender of a gap fill
 *     here; that it consumes one correctly is covered staffix-to-staffix by {@code TestFixMessagesResends}.</li>
 *     <li><b>A retransmitted range that ends on a gap fill</b> rather than on a real message, which is the case where
 *     the peer has to take the announced NewSeqNo(36) as the whole of the range's tail. Covered staffix-to-staffix by
 *     {@code TestFixMessagesResends.testResendRequestWithFilteredResendsByApplication} with
 *     {@code lastMessageIsAlsoFiltered}.</li>
 *     <li><b>Messages sent while a retransmission is still in flight</b>, in either direction, and both sides
 *     recovering at once. Covered staffix-to-staffix by
 *     {@code TestFixMessagesResends.testResendRequestWithApplicationSendingMessagesDuringResendRequest} and
 *     {@code testResendRequestDoesNotBlockIncomingMessagesWhileRetransmitting}.</li>
 *     <li><b>The session schedule against QuickFIX/J</b>: the automatic logout and logon at a session boundary, and
 *     the sequence reset a new day triggers. Covered staffix-to-staffix by {@code TestFixSessionsSchedule}; what a
 *     QuickFIX/J peer makes of it is untested.</li>
 * </ul>
 * FIXT 1.1 / FIX 5.0 and SSL were on that list and are no longer: see {@link AbstractQuickfixjFixtInterop} and
 * {@link AbstractQuickfixjSslInterop}.
 */
abstract class AbstractQuickfixjInterop extends AbstractQuickfixjSmokeInterop {

    /**
     * How long the schedule scenario's trading window stays open.
     * <p>
     * The window is anchored to the real clock rather than to a fixed date, and the staffix clock is only ever nudged
     * seconds rather than days, because the peer checks SendingTime(52) against its own clock: a staffix side pinned
     * to some other date would have every message it sent rejected as stale long before the schedule mattered.
     */
    private static final int WINDOW_SECONDS = 30;

    /**
     * How far ahead of logon the non-stop schedule's roll is put.
     * <p>
     * Real time rather than a driven clock, for the reason above and one more: a crossing is consumed whether or not
     * the session is logged in, so a roll scheduled before logon completes is skipped rather than deferred. The
     * margin has to cover standing both engines up, which is why it is generous where the impl tests use three
     * seconds.
     */
    private static final int ROLL_MARGIN_SECONDS = 10;

    private static FixSessionSettings.SessionScheduleSettings tradingWindow(ZonedDateTime opens, ZonedDateTime closes) {
        return FixSessionSettings.SessionScheduleSettings.builder()
                .timeZone(TimeZone.getTimeZone("UTC"))
                .sessionSchedule(FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                        .startDay(opens.getDayOfWeek())
                        .startTime(opens.toLocalTime())
                        .endDay(closes.getDayOfWeek())
                        .endTime(closes.toLocalTime())
                        .build())
                .build();
    }

    private static FixSessionSettings.SessionScheduleSettings nonStopRollingAt(ZonedDateTime rollsAt) {
        FixSessionSettings.SessionScheduleSettings.SessionScheduleSettingsBuilder builder =
                FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("UTC"))
                        .withinSessionTimeCheckInterval(Duration.ofMillis(100));
        // every day at the same time, so a run crossing a midnight fires the next day's identical entry
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            builder.nonStopSchedule(FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry.builder()
                    .dayOfWeek(day)
                    .sequenceResetTime(rollsAt.toLocalTime())
                    .initiatesReset(true)
                    .build());
        }
        return builder.build();
    }

    /**
     * A trading window closing under a connected peer, the other half of the schedule from
     * {@link #testStaffixRollsTheSequenceOnScheduleAndQuickfixFollows}: there a day that never closes, here one that
     * does.
     * <p>
     * Nothing in the session protocol announces a schedule. A trading window is local configuration, so when
     * staffix's closes, all the peer has to go on is an ordinary Logout(35=5) with the reason in Text(58) - a logout
     * it never asked for and had no way to anticipate. What is asserted is that QuickFIX/J takes it as the ordinary
     * logout it looks like and the session ends cleanly on both sides.
     */
    @Test
    void testStaffixLogsOutWhenItsTradingWindowCloses() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        staffixSessionSchedule = tradingWindow(now.minusSeconds(WINDOW_SECONDS), now.plusSeconds(WINDOW_SECONDS));
        // advanceable rather than fixed, and anchored to the real clock: the window has to be crossed on demand, and
        // a staffix living on some other date has QuickFIX/J reject everything it sends on SendingTime(52) first
        staffixClock.toAdvanceableTime(now.toInstant());

        logon();

        sendEmailFromStaffix(1);
        assertQuickfixReceivedEmail(1);
        sendEmailFromQuickfix(2);
        assertStaffixReceivedEmail(2);

        // past the close, by less than the peer's MaxLatency so its SendingTime(52) check stays satisfied
        staffixClock.advance(Duration.ofSeconds(WINDOW_SECONDS + 5));

        await().untilAsserted(() -> assertThatFixMessage(
                messagesOfType(staffixLogger.getOutgoingMessages(), CoreMessageType.LOGOUT))
                .as("staffix must log out of its own accord when its window closes, saying why in Text(58)")
                .containsFieldWithValueContaining(CoreFields.TEXT, "Outside of session timeframe"));

        // and the peer takes it as an ordinary logout rather than an error. staffix is the later of the two to settle,
        // its own side of the exchange ending on the peer's Logout coming back rather than on its own going out
        await().untilAsserted(() -> assertThat(quickfixSession().isLoggedOn()).isFalse());
        await().untilAsserted(() -> assertThat(staffixSession.isLoggedIn()).isFalse());
    }

    /**
     * What the closed window does to a peer that keeps trying, which is the half of the schedule a Logout does not
     * settle. The harness gives both engines a one second reconnect interval, so neither is short of opportunities.
     * <p>
     * The two roles behave differently, and the difference is the point. staffix <b>initiating</b> knows its own
     * schedule and goes quiet - it is asked before every connection attempt and answers no for as long as the window
     * stays closed, so there is not even a socket in the window's shadow, let alone a message. staffix
     * <b>accepting</b> has no say in
     * when QuickFIX/J dials, so it refuses each attempt with a Logout naming the reason, and the pair cycles
     * connect-refuse-retry until the window opens again. That is correct on both sides and worth writing down
     * anyway: an operator sees a session flapping every couple of seconds all weekend, spending sequence numbers on
     * each refusal, and it is the peer's {@code ReconnectInterval} that decides the cost rather than anything
     * staffix can configure.
     * <p>
     * The assertion that carries weight is the last one: a closed window must never re-admit the peer. It is the only
     * place a second implementation checks the window arithmetic, which is why it matters more since that arithmetic
     * was rewritten - a schedule wrongly reporting itself open would log the peer straight back in here.
     */
    @Test
    void testTheClosedWindowRefusesThePeerRatherThanReadmittingIt() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        staffixSessionSchedule = tradingWindow(now.minusSeconds(WINDOW_SECONDS), now.plusSeconds(WINDOW_SECONDS));
        staffixClock.toAdvanceableTime(now.toInstant());

        logon();
        staffixClock.advance(Duration.ofSeconds(WINDOW_SECONDS + 5));
        await().untilAsserted(() -> assertThat(quickfixSession().isLoggedOn()).isFalse());
        await().untilAsserted(() -> assertThat(staffixSession.isLoggedIn()).isFalse());
        if (staffixIsInitiator()) {
            // down to the socket, not just logged out: the closing is still sending while it tears down - the same
            // clock jump that shut the window leaves the peer looking silent for longer than a heartbeat, so a
            // TestRequest(35=1) and a Heartbeat(35=0) follow the Logout out. Those belong to the close, and clearing
            // the log before they are written would leave them to be read below as the closed window talking
            await().untilAsserted(() -> assertThat(staffixSession.isConnected()).isFalse());
        }

        // everything from here is what the closed window does, so the establishment traffic is out of the way
        staffixLogger.clear();
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(8));

        if (staffixIsInitiator()) {
            assertThat(staffixSession.isConnected())
                    .as("staffix initiating knows its own schedule and must not dial again while it is closed")
                    .isFalse();
            assertThat(staffixLogger.getOutgoingMessages())
                    .as("and so must have nothing to say either")
                    .isEmpty();
        } else {
            assertThat(staffixLogger.getIncomingMessages())
                    .as("QuickFIX/J must have retried on its ReconnectInterval, or this asserts nothing below")
                    .isNotEmpty();
            assertThatFixMessage(messagesOfType(staffixLogger.getOutgoingMessages(), CoreMessageType.LOGOUT))
                    .as("staffix accepting must refuse each attempt with a Logout naming the reason")
                    .containsFieldWithValueContaining(CoreFields.TEXT, "Logon attempt outside of configured session time");
        }

        assertThat(staffixSession.isLoggedIn())
                .as("a closed window must never re-admit the peer, however often it asks")
                .isFalse();
        assertThat(quickfixSession().isLoggedOn())
                .as("and the peer must not believe otherwise")
                .isFalse();
    }

    /**
     * How many Logon(35=A) messages staffix has received, the establishment one included.
     */
    private long logonsReceivedByStaffix() {
        return staffixLogger.getIncomingMessages().stream()
                .filter(message -> FixMessageFields.hasFieldWithValue(message, CoreFields.MESSAGE_TYPE, CoreMessageType.LOGON))
                .count();
    }

    /**
     * Section 4.4.2 driven by staffix's non-stop schedule against QuickFIX/J, which the harness already configures
     * {@code NonStopSession=Y}. The roll arrives on the schedule's own timer rather than through the admin API, so
     * this covers the settings as a deployment would use them.
     * <p>
     * The open question was what QuickFIX/J does with a Logon(35=A) carrying ResetSeqNumFlag(141)=Y on a session
     * already logged on. It <b>follows the reset in both directions</b> - both ends restart, nothing logs out, the
     * connection survives and traffic continues at the new numbering - but it confirms the reset with a Logon of its
     * own <b>only where it accepts</b>. Initiating, it applies the reset silently: no Logon back, and its next
     * message simply carries MsgSeqNum(34)=1.
     * <p>
     * That is the same asymmetry as {@link #testPeerResettingOnDisconnectAnnouncesItOnlyWhenItInitiates}, seen from
     * the other side and with a far better outcome. There, a QuickFIX/J that accepts restarts without announcing it
     * and the pair flaps. Here the announcement comes from staffix, so QuickFIX/J has nothing to work out - and the
     * missing confirmation costs nothing, because the end that proposes 4.4.2 has already reset itself and does not
     * need to be told. Section 4.4.2 asks for that confirming Logon; QuickFIX/J's model has an incoming Logon be the
     * <em>response</em> to its own, and an initiator does not answer a response.
     */
    @Test
    void testStaffixRollsTheSequenceOnScheduleAndQuickfixFollows() {
        ZonedDateTime rollsAt = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(ROLL_MARGIN_SECONDS);
        staffixSessionSchedule = nonStopRollingAt(rollsAt);

        logon();

        // traffic first, so the roll has something to put back rather than landing on numbers already at 1
        sendEmailFromStaffix(1);
        assertQuickfixReceivedEmail(1);
        sendEmailFromQuickfix(2);
        assertStaffixReceivedEmail(2);
        long logonsBeforeTheRoll = logonsReceivedByStaffix();

        assertThat(ZonedDateTime.now(ZoneOffset.UTC))
                .as("standing the engines up took longer than the %ss margin, so the crossing was consumed before "
                        + "logon and this run cannot observe the roll - raise the margin", ROLL_MARGIN_SECONDS)
                .isBefore(rollsAt);

        // staffix announces the roll on the schedule's own timer
        await().untilAsserted(() -> assertThatFixMessage(
                messagesOfType(staffixLogger.getOutgoingMessages(), CoreMessageType.LOGON))
                .as("staffix must announce the scheduled roll with ResetSeqNumFlag(141)=Y")
                .containsFieldWithValue(CoreFields.RESET_NUM_FLAG, "Y"));

        // "upon completion of the session reset, both peers must have NextNumIn = 2 and NextNumOut = 2". Staffix
        // reaches NextNumIn = 2 on whatever QuickFIX/J sends next, which is the acknowledging Logon where it accepts
        // and merely the next heartbeat where it initiates.
        await().untilAsserted(() -> assertThat(staffixMessagesStore.getOutgoingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(staffixMessagesStore.getIncomingSeqNum()).isEqualTo(2));

        // the connection carried it rather than a logout and a fresh session
        assertThat(staffixSession.isLoggedIn()).isTrue();
        await().untilAsserted(() -> assertThat(quickfixConnector.isLoggedOn()).isTrue());
        assertThat(staffixLogger.getOutgoingMessages())
                .as("the connection must survive the roll, so staffix may not have sent a Logout")
                .noneMatch(message -> FixMessageFields.hasFieldWithValue(message, CoreFields.MESSAGE_TYPE, CoreMessageType.LOGOUT));
        assertThat(staffixLogger.getIncomingMessages())
                .as("nor may QuickFIX/J have answered the reset with one, which is how 4.4.2 says a peer refuses it")
                .noneMatch(message -> FixMessageFields.hasFieldWithValue(message, CoreFields.MESSAGE_TYPE, CoreMessageType.LOGOUT));

        if (staffixIsInitiator()) {
            // QuickFIX/J accepts here, and an acceptor answers a Logon with a Logon: 4.4.2's confirmation arrives
            await().untilAsserted(() -> assertThat(logonsReceivedByStaffix())
                    .as("QuickFIX/J accepting must confirm the reset with a Logon of its own")
                    .isEqualTo(logonsBeforeTheRoll + 1));
            assertThatFixMessage(messagesOfType(staffixLogger.getIncomingMessages(), CoreMessageType.LOGON))
                    .as("and that Logon must carry ResetSeqNumFlag(141)=Y")
                    .containsFieldWithValue(CoreFields.RESET_NUM_FLAG, "Y");
        } else {
            // QuickFIX/J initiates here, and an initiator treats an incoming Logon as the response to its own rather
            // than as something to answer. It applies the reset all the same - which is what the restarted numbering
            // above says - but sends no confirming Logon, so 4.4.2's acknowledgement is simply absent.
            assertThat(logonsReceivedByStaffix())
                    .as("QuickFIX/J initiating sends no Logon of its own in answer to the reset")
                    .isEqualTo(logonsBeforeTheRoll);
        }

        // and the session is usable at the restarted numbering rather than merely synchronised on paper
        sendEmailFromStaffix(3);
        assertQuickfixReceivedEmail(3);
        sendEmailFromQuickfix(4);
        assertStaffixReceivedEmail(4);
    }

    @Test
    void testStaffixRetransmitsMessagesQuickfixMissed() {
        logon();

        for (int i = 1; i <= MESSAGES_BEFORE_GAP; i++) {
            sendEmailFromStaffix(i);
        }
        assertQuickfixReceivedEmail(MESSAGES_BEFORE_GAP);

        // QuickFIX/J is made to believe it never got the last few, so the next message arrives beyond what it expects
        makeQuickfixExpectMissedMessages(MISSED_MESSAGES);
        quickfixReceivedEmails.clear();
        sendEmailFromStaffix(MESSAGES_BEFORE_GAP + 1);

        // the gap is closed by retransmission, and a retransmitted message is flagged PossDupFlag(43)=Y
        for (int i = MESSAGES_BEFORE_GAP - MISSED_MESSAGES + 1; i <= MESSAGES_BEFORE_GAP; i++) {
            assertQuickfixReceivedEmail(i);
        }
        assertQuickfixReceivedEmail(MESSAGES_BEFORE_GAP + 1);
        await().untilAsserted(() -> assertThat(quickfixReceivedEmails)
                .filteredOn(email -> !email.getEmailThreadId().equals(emailThreadId(MESSAGES_BEFORE_GAP + 1)))
                .isNotEmpty()
                .allMatch(ReceivedEmail::isPossDup));

        // and the session is usable afterwards rather than merely caught up
        sendEmailFromStaffix(MESSAGES_BEFORE_GAP + 2);
        assertQuickfixReceivedEmail(MESSAGES_BEFORE_GAP + 2);
    }

    @Test
    void testQuickfixRetransmitsMessagesStaffixMissed() {
        logon();

        for (int i = 1; i <= MESSAGES_BEFORE_GAP; i++) {
            sendEmailFromQuickfix(i);
        }
        assertStaffixReceivedEmail(MESSAGES_BEFORE_GAP);

        makeStaffixExpectMissedMessages(MISSED_MESSAGES);
        staffixReceivedEmails.clear();
        sendEmailFromQuickfix(MESSAGES_BEFORE_GAP + 1);

        for (int i = MESSAGES_BEFORE_GAP - MISSED_MESSAGES + 1; i <= MESSAGES_BEFORE_GAP; i++) {
            assertStaffixReceivedEmail(i);
        }
        assertStaffixReceivedEmail(MESSAGES_BEFORE_GAP + 1);
        await().untilAsserted(() -> assertThat(staffixReceivedEmails)
                .filteredOn(email -> !email.getEmailThreadId().equals(emailThreadId(MESSAGES_BEFORE_GAP + 1)))
                .isNotEmpty()
                .allMatch(ReceivedEmail::isPossDup));

        sendEmailFromQuickfix(MESSAGES_BEFORE_GAP + 2);
        assertStaffixReceivedEmail(MESSAGES_BEFORE_GAP + 2);
    }

    @Test
    void testLogonWithResetSeqNumFlagRestartsBothSequences() {
        // whichever engine is playing the initiator asks for the reset, being the one that sends a Logon of its own
        if (staffixIsInitiator()) {
            staffixResetSeqNumOnLogon = true;
        } else {
            quickfixResetOnLogon = true;
        }

        logon();

        // spend some sequence numbers, so that a reset is visible as more than a session that never moved
        for (int i = 1; i <= MESSAGES_BEFORE_GAP; i++) {
            sendEmailFromStaffix(i);
            sendEmailFromQuickfix(100 + i);
        }
        assertQuickfixReceivedEmail(MESSAGES_BEFORE_GAP);
        assertStaffixReceivedEmail(100 + MESSAGES_BEFORE_GAP);
        await().untilAsserted(() -> assertThat(staffixMessagesStore.getOutgoingSeqNum()).isGreaterThan(2));

        cycleSession();

        // section 4.4.2: "upon completion of the session reset, both peers must have NextNumIn = 2 and NextNumOut = 2",
        // the Logon exchange having spent the first number on each side
        await().untilAsserted(() -> assertThat(staffixMessagesStore.getOutgoingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(staffixMessagesStore.getIncomingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(quickfixSession().getExpectedSenderNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(quickfixSession().getExpectedTargetNum()).isEqualTo(2));

        // and both ends agree well enough on the restarted numbering to keep talking
        sendEmailFromStaffix(MESSAGES_BEFORE_GAP + 1);
        assertQuickfixReceivedEmail(MESSAGES_BEFORE_GAP + 1);
        sendEmailFromQuickfix(100 + MESSAGES_BEFORE_GAP + 1);
        assertStaffixReceivedEmail(100 + MESSAGES_BEFORE_GAP + 1);
    }

    @Test
    void testStaffixGapFillsTheMessagesItsApplicationWithholds() {
        // an application that answers no to onResendRequest keeps the message to itself, and the session accounts for
        // it with a SequenceReset-GapFill(35=4, 123=Y) so that the peer's numbering still adds up
        staffixResendsApplicationMessages = false;

        logon();

        for (int i = 1; i <= MESSAGES_BEFORE_GAP; i++) {
            sendEmailFromStaffix(i);
        }
        assertQuickfixReceivedEmail(MESSAGES_BEFORE_GAP);

        makeQuickfixExpectMissedMessages(MISSED_MESSAGES);
        quickfixReceivedEmails.clear();
        // the message revealing the gap is itself inside the range the peer then asks to have retransmitted, so it is
        // withheld and gap filled along with the rest rather than being delivered
        sendEmailFromStaffix(MESSAGES_BEFORE_GAP + 1);

        // the peer asks for everything from the gap onwards, so a message sent before the retransmission is over
        // falls inside the range and is withheld too. Waiting for the two sides to agree on the next number again is
        // what tells us the SequenceReset-GapFill has been applied and the range is closed.
        awaitSequencesResynchronised();

        // the session realigns on the gap fill alone: what proves it is that a message sent afterwards, outside the
        // retransmitted range, arrives under the numbering the gap fill jumped to
        sendEmailFromStaffix(MESSAGES_BEFORE_GAP + 2);
        assertQuickfixReceivedEmail(MESSAGES_BEFORE_GAP + 2);

        assertThat(quickfixReceivedEmails)
                .as("nothing the application withheld may have been retransmitted")
                .noneMatch(ReceivedEmail::isPossDup)
                .extracting(ReceivedEmail::getEmailThreadId)
                .containsExactly(emailThreadId(MESSAGES_BEFORE_GAP + 2));

        // and the other direction is unaffected
        sendEmailFromQuickfix(100);
        assertStaffixReceivedEmail(100);
    }

    /**
     * The mirror of {@link #testStaffixGapFillsTheMessagesItsApplicationWithholds}: this time QuickFIX/J is the one
     * withholding, and staffix has to make sense of the SequenceReset-GapFill(35=4, 123=Y) that arrives in place of
     * the messages.
     * <p>
     * Producing a gap fill and consuming one are different code entirely, and only the producing half was pinned
     * against another implementation - the consuming half is covered staffix-to-staffix by
     * {@code TestFixMessagesResends}, which is staffix's own encoder feeding staffix's own decoder. A gap fill is
     * exactly where two engines have room to disagree while both being right: how much of the range one SequenceReset
     * covers, whether it is coalesced at all, what MsgSeqNum(34) it carries itself. staffix has to take whichever
     * shape the peer chose, so the assertions below are about the effect rather than about the shape.
     * The same recovery asked for open ended, which is the reason {@link ResendRequestRange} exists: EndSeqNo(16)=0
     * rather than the last message of the gap, the form QuickFIX/J itself sends by default (its {@code
     * ClosedResendInterval=N}) and the only one some counterparties answer.
     * <p>
     * What it buys is only visible against another engine, and it is what the peer does with it: asked for everything
     * from BeginSeqNo(7) onwards, QuickFIX/J answers past the end of the gap - the message that revealed it, and the
     * session level messages after it, are all inside the range it was given - so the answer runs on after the
     * request has been satisfied. Digesting that tail is the half of the setting that staffix-to-staffix coverage in
     * {@code TestFixMessagesResends} pins by construction; this is the half that pins it against a peer whose answer
     * staffix does not get to choose the shape of.
     */
    @Test
    void testStaffixAsksQuickfixForARetransmissionOpenEnded() {
        staffixResendRequestRange = ResendRequestRange.OPEN_ENDED;

        logon();

        for (int i = 1; i <= MESSAGES_BEFORE_GAP; i++) {
            sendEmailFromQuickfix(i);
        }
        assertStaffixReceivedEmail(MESSAGES_BEFORE_GAP);

        makeStaffixExpectMissedMessages(MISSED_MESSAGES);
        staffixReceivedEmails.clear();
        sendEmailFromQuickfix(MESSAGES_BEFORE_GAP + 1);

        // the whole point of the setting: the request names no end at all
        await().untilAsserted(() -> assertThatFixMessage(
                messagesOfType(staffixLogger.getOutgoingMessages(), CoreMessageType.RESEND_REQUEST))
                .as("an open ended range is EndSeqNo(16)=0 from FIX.4.2 on")
                .containsFieldWithValue(CoreFields.END_SEQ_NO, "0"));

        // and the peer's answer, tail and all, leaves the two sides agreeing on the next number again
        awaitIncomingSequenceResynchronised();

        // the missed messages come back as retransmissions, and the one that revealed the gap is replayed after them
        await().untilAsserted(() -> assertThat(staffixReceivedEmails)
                .filteredOn(ReceivedEmail::isPossDup)
                .extracting(ReceivedEmail::getEmailThreadId)
                .contains(emailThreadId(MESSAGES_BEFORE_GAP - MISSED_MESSAGES + 1), emailThreadId(MESSAGES_BEFORE_GAP)));
        assertStaffixReceivedEmail(MESSAGES_BEFORE_GAP + 1);

        // nothing in the tail took the session down, so it keeps working in both directions
        sendEmailFromQuickfix(MESSAGES_BEFORE_GAP + 2);
        assertStaffixReceivedEmail(MESSAGES_BEFORE_GAP + 2);
        sendEmailFromStaffix(100);
        assertQuickfixReceivedEmail(100);
    }

    @Test
    void testStaffixAcceptsTheGapFillQuickfixProduces() {
        quickfixResendsApplicationMessages = false;

        logon();

        for (int i = 1; i <= MESSAGES_BEFORE_GAP; i++) {
            sendEmailFromQuickfix(i);
        }
        assertStaffixReceivedEmail(MESSAGES_BEFORE_GAP);

        makeStaffixExpectMissedMessages(MISSED_MESSAGES);
        staffixReceivedEmails.clear();
        sendEmailFromQuickfix(MESSAGES_BEFORE_GAP + 1);

        // the peer answers the whole range with a gap fill, so what says the range is closed is the two sides
        // agreeing again on the next number rather than any message arriving
        awaitIncomingSequenceResynchronised();

        assertThatFixMessage(messagesOfType(staffixLogger.getIncomingMessages(), CoreMessageType.SEQUENCE_REQUEST))
                .as("the peer must have covered the withheld messages with a gap fill, not a hard reset")
                .containsFieldWithValue(CoreFields.GAP_FILL, "Y");

        // staffix realigned on the gap fill alone: what proves it is a message sent afterwards, outside the
        // retransmitted range, arriving under the numbering the gap fill jumped to
        sendEmailFromQuickfix(MESSAGES_BEFORE_GAP + 2);
        assertStaffixReceivedEmail(MESSAGES_BEFORE_GAP + 2);

        // The message that revealed the gap arrives, and this is where consuming a gap fill differs from producing
        // one. In the mirror scenario staffix is the resender, so that message falls inside the range it gap fills and
        // never goes out. Here staffix is the requester: it holds the out of sequence message and replays it once the
        // range is closed, while QuickFIX/J only ever withholds what it was actually asked to retransmit. So the two
        // beyond the gap arrive, in order, and nothing from inside it does.
        assertThat(staffixReceivedEmails)
                .as("only the messages outside the retransmitted range may have been delivered")
                .noneMatch(ReceivedEmail::isPossDup)
                .extracting(ReceivedEmail::getEmailThreadId)
                .containsExactly(emailThreadId(MESSAGES_BEFORE_GAP + 1), emailThreadId(MESSAGES_BEFORE_GAP + 2));

        // and the other direction is unaffected
        sendEmailFromStaffix(100);
        assertQuickfixReceivedEmail(100);
    }

    /**
     * A retransmitted range that <b>ends</b> on a gap fill rather than on a real message.
     * <p>
     * Distinct from {@link #testStaffixGapFillsTheMessagesItsApplicationWithholds}, where everything is withheld and
     * the whole range is one SequenceReset. Here the peer gets real retransmissions first and the tail gap filled, so
     * the last thing it hears about the range is a NewSeqNo(36) rather than a message - it has to take that announced
     * number as the end of the range, with nothing following to confirm it. Covered staffix-to-staffix by
     * {@code TestFixMessagesResends.testResendRequestWithFilteredResendsByApplication} with
     * {@code lastMessageIsAlsoFiltered}.
     */
    @Test
    void testStaffixGapFillsTheTailOfARetransmittedRange() {
        // the first message of the range is retransmitted for real, everything after it is withheld
        staffixResendsBeforeWithholdingTheRest = 1;

        logon();

        for (int i = 1; i <= MESSAGES_BEFORE_GAP; i++) {
            sendEmailFromStaffix(i);
        }
        assertQuickfixReceivedEmail(MESSAGES_BEFORE_GAP);

        makeQuickfixExpectMissedMessages(MISSED_MESSAGES);
        quickfixReceivedEmails.clear();
        sendEmailFromStaffix(MESSAGES_BEFORE_GAP + 1);

        // the first of the retransmitted range really comes back, PossDupFlag(43)=Y as any resend is
        assertQuickfixReceivedEmail(MESSAGES_BEFORE_GAP - MISSED_MESSAGES + 1);
        await().untilAsserted(() -> assertThat(quickfixReceivedEmails)
                .filteredOn(email -> email.getEmailThreadId()
                        .equals(emailThreadId(MESSAGES_BEFORE_GAP - MISSED_MESSAGES + 1)))
                .isNotEmpty()
                .allMatch(ReceivedEmail::isPossDup));

        // and the range closes on the gap fill that covers the rest, which the peer accepts as the tail
        awaitSequencesResynchronised();

        // the tail really was gap filled rather than retransmitted: a SequenceReset went out carrying
        // GapFillFlag(123)=Y, and the last message of the range never arrived as a message
        assertThatFixMessage(messagesOfType(staffixLogger.getOutgoingMessages(), CoreMessageType.SEQUENCE_REQUEST))
                .as("the withheld tail must have been covered by a gap fill")
                .containsFieldWithValue(CoreFields.GAP_FILL, "Y");
        // and the peer got it marked as part of the retransmission it is. This peer asks open ended - QuickFIX/J's
        // default - so the answer runs past the gap it was recovering, and a gap fill reaching it after it has moved
        // on is one it must log out over unless PossDupFlag(43)=Y says it is a duplicate. That QuickFIX/J accepted
        // the message at all is the other half of the assertion: it wants an OrigSendingTime(122) alongside the flag.
        assertThatFixMessage(messagesOfType(staffixLogger.getOutgoingMessages(), CoreMessageType.SEQUENCE_REQUEST))
                .as("a gap fill must be marked as the retransmission it is part of")
                .containsFieldWithValue(CoreFields.POSS_DUP_FLAG, "Y");
        assertThat(quickfixReceivedEmails)
                .as("the withheld tail must not have been retransmitted as messages")
                .extracting(ReceivedEmail::getEmailThreadId)
                .doesNotContain(emailThreadId(MESSAGES_BEFORE_GAP));

        sendEmailFromStaffix(MESSAGES_BEFORE_GAP + 2);
        assertQuickfixReceivedEmail(MESSAGES_BEFORE_GAP + 2);
    }

    @Test
    void testStaffixRetransmitsMessagesItSentWhileDisconnected() {
        logon();

        // a message each way first, so that neither store is empty when the connection goes and the gap that follows
        // is a gap in a sequence that was already running
        sendEmailFromStaffix(1);
        assertQuickfixReceivedEmail(1);

        dropConnection();

        // staffix keeps sending into a session with nowhere to write: every message still takes its MsgSeqNum(34) and
        // goes to the store, which is what makes the peer's next expected number fall behind
        long outgoingSeqNumBeforeGap = staffixMessagesStore.getOutgoingSeqNum();
        for (int i = 2; i <= 1 + MISSED_MESSAGES; i++) {
            sendEmailFromStaffix(i);
        }
        await().untilAsserted(() -> assertThat(staffixMessagesStore.getOutgoingSeqNum())
                .as("the messages sent while the connection was down must have spent their sequence numbers")
                .isEqualTo(outgoingSeqNumBeforeGap + MISSED_MESSAGES));
        assertThat(quickfixReceivedEmails)
                .as("nothing may have reached the peer while there was no connection")
                .extracting(ReceivedEmail::getEmailThreadId)
                .containsExactly(emailThreadId(1));
        quickfixReceivedEmails.clear();

        awaitLoggedBackOn();

        // the gap is discovered on the Logon this time rather than mid-stream, and closed the same way
        for (int i = 2; i <= 1 + MISSED_MESSAGES; i++) {
            assertQuickfixReceivedEmail(i);
        }
        await().untilAsserted(() -> assertThat(quickfixReceivedEmails)
                .as("everything sent while down comes back as a retransmission")
                .isNotEmpty()
                .allMatch(ReceivedEmail::isPossDup));

        // and the session picked up where it left off rather than merely having caught up
        sendEmailFromStaffix(1 + MISSED_MESSAGES + 1);
        assertQuickfixReceivedEmail(1 + MISSED_MESSAGES + 1);
    }

    @Test
    void testQuickfixRetransmitsMessagesItSentWhileDisconnected() {
        logon();

        sendEmailFromQuickfix(1);
        assertStaffixReceivedEmail(1);

        dropConnection();

        for (int i = 2; i <= 1 + MISSED_MESSAGES; i++) {
            sendEmailFromQuickfixWhileDisconnected(i);
        }
        assertThat(staffixReceivedEmails)
                .as("nothing may have reached staffix while there was no connection")
                .extracting(ReceivedEmail::getEmailThreadId)
                .containsExactly(emailThreadId(1));
        staffixReceivedEmails.clear();

        awaitLoggedBackOn();

        // staffix is the one that has to notice, on the peer's Logon, that its incoming sequence fell behind
        for (int i = 2; i <= 1 + MISSED_MESSAGES; i++) {
            assertStaffixReceivedEmail(i);
        }
        await().untilAsserted(() -> assertThat(staffixReceivedEmails)
                .isNotEmpty()
                .allMatch(ReceivedEmail::isPossDup));

        sendEmailFromQuickfix(1 + MISSED_MESSAGES + 1);
        assertStaffixReceivedEmail(1 + MISSED_MESSAGES + 1);
    }

    @Test
    void testGapAfterReconnectIsFilledWithoutAResendRequestWhenBothSidesUse789() {
        // section 4.4.1: "peers should not generate a ResendRequest(35=2) message based on MsgSeqNum(34) of the
        // incoming Logon(35=A) message but should expect any gaps to be filled automatically". The gap this opens is
        // the one of testStaffixRetransmitsMessagesItSentWhileDisconnected, and what makes this a different test is
        // that it must close without a ResendRequest ever going on the wire. staffix advertises 789 by default; the
        // QuickFIX/J side does not, and until it is turned on here the same gap is recovered the ordinary way.
        quickfixEnableNextExpectedMsgSeqNum = true;

        logon();

        sendEmailFromStaffix(1);
        assertQuickfixReceivedEmail(1);

        dropConnection();

        long outgoingSeqNumBeforeGap = staffixMessagesStore.getOutgoingSeqNum();
        for (int i = 2; i <= 1 + MISSED_MESSAGES; i++) {
            sendEmailFromStaffix(i);
        }
        await().untilAsserted(() -> assertThat(staffixMessagesStore.getOutgoingSeqNum())
                .isEqualTo(outgoingSeqNumBeforeGap + MISSED_MESSAGES));
        quickfixReceivedEmails.clear();
        // from here on the log holds the recovery and nothing else, so a ResendRequest found in it is this gap's
        staffixLogger.clear();

        awaitLoggedBackOn();

        for (int i = 2; i <= 1 + MISSED_MESSAGES; i++) {
            assertQuickfixReceivedEmail(i);
        }
        await().untilAsserted(() -> assertThat(quickfixReceivedEmails)
                .isNotEmpty()
                .allMatch(ReceivedEmail::isPossDup));

        sendEmailFromStaffix(1 + MISSED_MESSAGES + 1);
        assertQuickfixReceivedEmail(1 + MISSED_MESSAGES + 1);

        // the Logon exchange carried the field on both sides, so the recovery above really was the 789 one and not
        // the ordinary path passing for it
        assertLogonExchangeCarriedNextExpectedMsgSeqNum();
        assertNoResendRequestOnTheWire();
    }

    @Test
    void testLogoutWhenTheLogonExpectsAMessageThePeerNeverSent() {
        // section 4.4.1: a NextExpectedMsgSeqNum(789) higher than anything the peer has sent cannot be honoured -
        // there is nothing to retransmit - and the session is not recoverable, so the Logon(35=A) is answered with a
        // Logout(35=5). The initiator of the pair is the one made to advertise the impossible number, so that its
        // Logon is what the acceptor rejects; which engine plays which role is what the two subclasses vary, so this
        // one scenario covers staffix both rejecting and being rejected.
        quickfixEnableNextExpectedMsgSeqNum = true;

        logon();

        sendEmailFromStaffix(1);
        assertQuickfixReceivedEmail(1);
        sendEmailFromQuickfix(2);
        assertStaffixReceivedEmail(2);

        // the session is taken down by dropping the socket rather than by logging out, so that no Logout response is
        // still in flight when the number is tampered with below. Processing one moves the incoming sequence number
        // on, which silently puts back the value the test just changed and leaves the Logon advertising the ordinary
        // number - a green test proving nothing.
        staffixLogger.clear();
        dropConnection();
        long impossibleNextExpected = makeTheInitiatorExpectAMessageNeverSent();

        // the Logon really did advertise the impossible number. Without this the test would still be green if the
        // tampering were undone before the Logon went out, having proved nothing at all
        List<String> logonSentBy = staffixIsInitiator()
                ? staffixLogger.getOutgoingMessages()   // staffix is the initiator and sends it
                : staffixLogger.getIncomingMessages();  // QuickFIX/J is the initiator and sends it
        await().untilAsserted(() -> assertThatFixMessage(messagesOfType(logonSentBy, CoreMessageType.LOGON))
                .as("the initiator must advertise NextExpectedMsgSeqNum(789)=%s", impossibleNextExpected)
                .containsFieldWithValue(CoreFields.NEXT_EXPECTED_MSG_SEQ_NUM, String.valueOf(impossibleNextExpected)));

        // and the acceptor of the pair answers it with a Logout naming the field, rather than logging on
        List<String> logoutSeenBy = staffixIsInitiator()
                ? staffixLogger.getIncomingMessages()   // QuickFIX/J is the acceptor and sends it
                : staffixLogger.getOutgoingMessages();  // staffix is the acceptor and sends it
        // the two engines word it differently - "NextExpectedMsgSeqNum is higher than expected: expected 3, received
        // 103" against "Tag 789 (NextExpectedMsgSeqNum) is higher than expected. Expected 3, Received 103" - so what
        // is asserted is the substance they share
        await().untilAsserted(() -> assertThatFixMessage(messagesOfType(logoutSeenBy, CoreMessageType.LOGOUT))
                .as("the acceptor must answer a NextExpectedMsgSeqNum(789) it cannot satisfy with a Logout")
                .containsFieldWithValueContaining(CoreFields.TEXT, "higher than expected"));
        assertThat(staffixSession.isLoggedIn())
                .as("a session whose Logon was rejected must not be logged in")
                .isFalse();
    }

    @Test
    void testHardSequenceResetFromQuickfixIsAppliedByStaffix() {
        // Section 4.8.6: a SequenceReset(35=4) with GapFillFlag(123) other than Y forces the peer's expected incoming
        // sequence number to NewSeqNo(36), whatever its own MsgSeqNum(34). Unlike the gap fill of the resend
        // scenarios it abandons the numbers it skips rather than accounting for them, so nothing may be asked for.
        logon();

        sendEmailFromQuickfix(1);
        assertStaffixReceivedEmail(1);
        staffixLogger.clear();

        long newSeqNo = staffixMessagesStore.getIncomingSeqNum() + 50;
        sendHardSequenceResetFromQuickfix(newSeqNo);

        await().untilAsserted(() -> assertThat(staffixMessagesStore.getIncomingSeqNum())
                .as("staffix must take the sequence number the peer's hard reset announces")
                .isEqualTo(newSeqNo));
        assertNoResendRequestOnTheWire();

        // and the session runs on from the announced numbering
        sendEmailFromQuickfix(2);
        assertStaffixReceivedEmail(2);
    }

    @Test
    void testHardSequenceResetFromQuickfixLoweringTheSequenceIsRejected() {
        // A hard reset may only move the sequence forward; one that would lower it is answered with a Reject(35=3)
        // carrying SessionRejectReason(373)=5 and leaves the expected sequence number alone.
        logon();

        sendEmailFromQuickfix(1);
        assertStaffixReceivedEmail(1);
        staffixLogger.clear();

        long expectedIncomingSeqNum = staffixMessagesStore.getIncomingSeqNum();
        emitHardSequenceResetFromQuickfix(expectedIncomingSeqNum - 1);

        assertRefusedWithoutAskingForARetransmission(staffixLogger.getOutgoingMessages());
        assertThat(staffixMessagesStore.getIncomingSeqNum())
                .as("the refused reset must leave the expected incoming sequence number untouched")
                .isEqualTo(expectedIncomingSeqNum);
        assertThat(staffixSession.isLoggedIn())
                .as("a refused reset is a Reject, not a reason to end the session")
                .isTrue();
    }

    @Test
    void testHardSequenceResetFromStaffixIsAppliedByQuickfix() {
        // the mirror of the first scenario. No staffix code path sends a hard reset of its own accord, so it comes
        // from FixSessionImpl.hardSequenceReset, which exists for exactly this.
        logon();

        sendEmailFromStaffix(1);
        assertQuickfixReceivedEmail(1);
        staffixLogger.clear();

        long newSeqNo = quickfixSession().getExpectedTargetNum() + 50;
        sendHardSequenceResetFromStaffix(newSeqNo);

        await().untilAsserted(() -> assertThat((long) quickfixSession().getExpectedTargetNum())
                .as("QuickFIX/J must take the sequence number staffix's hard reset announces")
                .isEqualTo(newSeqNo));
        assertNoResendRequestOnTheWire();

        sendEmailFromStaffix(2);
        assertQuickfixReceivedEmail(2);
    }

    @Test
    void testHardSequenceResetFromStaffixLoweringTheSequenceIsRejected() {
        // the mirror of the second, and what makes the pair worth having: both engines refuse the same message the
        // same way, down to the SessionRejectReason(373)=5 and the field it names
        logon();

        sendEmailFromStaffix(1);
        assertQuickfixReceivedEmail(1);
        staffixLogger.clear();

        int expectedTargetNum = quickfixSession().getExpectedTargetNum();
        sendHardSequenceResetFromStaffix(expectedTargetNum - 1);

        assertRefusedWithoutAskingForARetransmission(staffixLogger.getIncomingMessages());
        assertThat(quickfixSession().getExpectedTargetNum())
                .as("the refused reset must leave the expected incoming sequence number untouched")
                .isEqualTo(expectedTargetNum);
        assertThat(quickfixSession().isLoggedOn())
                .as("a refused reset is a Reject, not a reason to end the session")
                .isTrue();
    }

    /**
     * A peer that throws its sequence numbers away when the connection drops - QuickFIX/J's
     * {@code ResetOnDisconnect} - and what that does to the session afterwards.
     * <p>
     * staffix has no equivalent setting: the session layer negotiates a restart through ResetSeqNumFlag(141) on the
     * Logon rather than by each end deciding for itself. So the question is what a staffix session makes of a
     * counterparty that restarts its numbering on its own, and <b>the answer depends on which role the peer is
     * playing, because only an initiator's Logon announces the restart</b>:
     * <ul>
     *     <li>QuickFIX/J <b>initiating</b> - its Logon carries {@code 141=Y}, so the restart is announced after all.
     *     staffix leaves the flag to the counterparty by default, follows it, and the session re-establishes with
     *     both ends back at NextNumIn = 2, NextNumOut = 2.</li>
     *     <li>QuickFIX/J <b>accepting</b> - it has reset just as thoroughly, but its Logon <em>response</em> carries
     *     no 141 at all. staffix sees a MsgSeqNum(34) that went backwards, which is unrecoverable by definition -
     *     nothing distinguishes a genuine restart from a replay - and answers with a Logout. The pair then flaps,
     *     QuickFIX/J reconnecting and resetting again each time.</li>
     * </ul>
     * The second case is the one worth knowing about: the setting looks symmetric and is not, and an operator who
     * turned it on for a session where QuickFIX/J accepts has quietly made that session unable to re-establish.
     */
    @Test
    void testPeerResettingOnDisconnectAnnouncesItOnlyWhenItInitiates() {
        quickfixResetOnDisconnect = true;

        logon();
        spendSomeSequenceNumbers();

        dropConnection();

        assertUnilateralResetOutcome();
    }

    /**
     * The same restart reached through a clean logout rather than a dropped connection, QuickFIX/J's
     * {@code ResetOnLogout} rather than {@code ResetOnDisconnect}. Two settings a peer may well have only one of, and
     * what the other end sees afterwards is the same.
     * Whether {@code ResetOnLogon} rescues a peer that resets while <b>accepting</b>.
     * <p>
     * The obvious hope is that it does: the setting sounds like it should make the peer announce its restart with
     * ResetSeqNumFlag(141)=Y, which is exactly what
     * {@link #testPeerResettingOnDisconnectAnnouncesItOnlyWhenItInitiates} shows is missing in that direction. It
     * does not, and the reason is structural rather than a matter of configuration: 141 is something a Logon
     * <em>request</em> carries to propose a restart, and an acceptor only ever answers one. Nothing it can be
     * configured to do puts the flag on a response the peer did not ask for.
     * <p>
     * So the two settings together are not a fix for the asymmetry - they only help when the resetting side is also
     * the initiating side, where the announcement was already happening.
     * <p>
     * <b>This one cannot be vacuity checked the usual way</b>, and that is not an oversight: turning its own knob off
     * leaves it passing, because the finding is that the knob changes nothing. What it guards against is the
     * <em>peer</em> changing - the assertion in {@link #assertUnilateralResetOutcome()} that the accepting side's
     * Logon carries no ResetSeqNumFlag(141) is the finding written down, and a QuickFIX/J that started sending one
     * would fail here rather than quietly altering what this suite believes. Measured when it was written: 0 of 3
     * such Logons carried the flag, against 8 "MsgSeqNum too low" Logouts.
     */
    @Test
    void testResetOnLogonDoesNotRescueAPeerThatResetsWhileAccepting() {
        quickfixResetOnDisconnect = true;
        quickfixResetOnLogon = true;

        logon();
        spendSomeSequenceNumbers();

        dropConnection();

        assertUnilateralResetOutcome();
    }

    @Test
    void testPeerResettingOnLogoutAnnouncesItOnlyWhenItInitiates() {
        quickfixResetOnLogout = true;

        logon();
        spendSomeSequenceNumbers();

        // logging out is what triggers the reset here; the re-logon has to be asked for on the staffix side, a
        // permanent logout being exactly the sort that does not come back by itself
        if (staffixIsInitiator()) {
            staffixSession.logoutPermanently("reset on logout test");
            await().untilAsserted(() -> assertThat(quickfixSession().isLoggedOn()).isFalse());
            staffixSession.logon();
        } else {
            quickfixSession().logout("reset on logout test");
            await().untilAsserted(() -> assertThat(quickfixSession().isLoggedOn()).isFalse());
            quickfixSession().logon();
        }

        assertUnilateralResetOutcome();
    }

    /**
     * Sends enough traffic that a restart at 1 is a real step backwards rather than a session that never moved.
     */
    private void spendSomeSequenceNumbers() {
        for (int i = 1; i <= MESSAGES_BEFORE_GAP; i++) {
            sendEmailFromStaffix(i);
        }
        assertQuickfixReceivedEmail(MESSAGES_BEFORE_GAP);
        await().untilAsserted(() -> assertThat(staffixMessagesStore.getOutgoingSeqNum()).isGreaterThan(2));
        staffixLogger.clear();
    }

    /**
     * Asserts whichever half of the asymmetry this direction produces. See
     * {@link #testPeerResettingOnDisconnectAnnouncesItOnlyWhenItInitiates()} for why there are two.
     */
    private void assertUnilateralResetOutcome() {
        if (staffixIsInitiator()) {
            // QuickFIX/J is accepting, so its Logon is a response and carries no ResetSeqNumFlag(141): the restart is
            // never announced and staffix has nothing to go on but a sequence number that went backwards
            await().untilAsserted(() -> assertThatFixMessage(
                    messagesOfType(staffixLogger.getOutgoingMessages(), CoreMessageType.LOGOUT))
                    .as("an unannounced restart must be answered with a Logout naming the reason")
                    .containsFieldWithValueContaining(CoreFields.TEXT, "MsgSeqNum too low"));
            assertThatFixMessage(messagesOfType(staffixLogger.getIncomingMessages(), CoreMessageType.LOGON))
                    .as("the peer's Logon must be the unannounced restart this scenario is about")
                    .doesNotContainField(CoreFields.RESET_NUM_FLAG);
            // deliberately no assertion on isLoggedIn(): the pair flaps from here, QuickFIX/J reconnecting and
            // resetting again, so whether the session happens to be up at the instant of the check is a coin toss.
            // The Logout above is the durable fact - the session could not carry on from where it was.
            return;
        }
        // QuickFIX/J is initiating, so it announces the restart and the session comes back on the announcement
        awaitLoggedBackOn();
        assertThatFixMessage(messagesOfType(staffixLogger.getIncomingMessages(), CoreMessageType.LOGON))
                .as("an initiating peer must announce its restart with ResetSeqNumFlag(141)=Y")
                .containsFieldWithValue(CoreFields.RESET_NUM_FLAG, "Y");
        // section 4.4.2, the same end state a negotiated reset produces
        await().untilAsserted(() -> assertThat(staffixMessagesStore.getOutgoingSeqNum()).isEqualTo(2));
        await().untilAsserted(() -> assertThat(staffixMessagesStore.getIncomingSeqNum()).isEqualTo(2));

        sendEmailFromStaffix(MESSAGES_BEFORE_GAP + 1);
        assertQuickfixReceivedEmail(MESSAGES_BEFORE_GAP + 1);
    }
}
