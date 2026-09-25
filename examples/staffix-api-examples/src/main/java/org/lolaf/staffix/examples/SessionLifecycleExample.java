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
package org.lolaf.staffix.examples;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.codec.FixFieldsEncoder;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.CancelOnDisconnectType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import picocli.CommandLine;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Every {@link FixApplication} callback a session goes through, logged as it happens, on both ends of one session.
 * <p>
 * The session comes up, is logged out because its schedule closed, comes back up when the schedule reopens, and is
 * then shut down while up. That whole story takes about fifteen seconds, which is the point of the example: the
 * session schedule is not read from a configuration file naming trading hours, it is <b>computed from the wall clock
 * when the example starts</b> - a window opening a couple of seconds from now and closing a few seconds later, then a
 * second one after a gap. Everything else follows from it, the engine driving the logon and the logout on its own.
 * <p>
 * Two things worth knowing before reading the output:
 * <ul>
 *     <li>{@link FixApplication} has no {@code onConnected}: the TCP connection is not a session-layer event. An
 *     acceptor sees it through {@link FixAcceptor.FixSessionEventsListener#onFixSessionAccepted}, which this example
 *     logs alongside the application callbacks; on both ends what marks the session as usable is
 *     {@link FixApplication#onLogon}, and what marks the connection as gone is
 *     {@link FixApplication#onDisconnected}.</li>
 *     <li>the per-message hooks ({@link FixApplication#onAdminMessageEncoding},
 *     {@link FixApplication#onMessageEncoding}) are logged here because this example carries no traffic worth
 *     mentioning - a heartbeat a second. They are on the message path: do not copy this onto a session that trades.</li>
 *     <li>{@link FixApplication#setup} returns no decoder, which the engine says out loud once per session - "has no
 *     incoming FixMessageDecoder". Expected here and only here: a session with nothing mapped can receive nothing but
 *     session level messages, which is precisely this example's subject.</li>
 * </ul>
 * Unlike its sibling examples this one does not wait for a keypress to start, and ignores {@code -d}: the schedule is
 * computed at startup, so a run that started later than it planned to would have its windows in the past, and the
 * length of the run is what the schedule says rather than a duration passed in.
 */
@Slf4j
@CommandLine.Command(name = "SessionLifecycleExample", mixinStandardHelpOptions = true, version = "1.0",
        description = "Logs every FixApplication callback of a session that comes up, is closed by its schedule, "
                + "comes back and is shut down", showDefaultValues = true)
public class SessionLifecycleExample extends FixExamplesBase implements Callable<Integer> {

    /**
     * How long the sessions wait before their first window opens: enough for both ends to be started and for the
     * initiator to have been told, once, that it is holding off connecting until the schedule opens.
     */
    private static final Duration START_DELAY = Duration.ofSeconds(2);

    /**
     * Short enough to be watched, and above the acceptor's {@code acceptorLowerBoundInterval} of one second, so a
     * couple of heartbeats and their encoding hooks land inside every window.
     */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(1);

    /**
     * How often the session compares the clock with its schedule, and how long before a close
     * {@link FixApplication#onPreOutsideSessionTime} fires. Both are well under the defaults of one and two seconds:
     * they are what sets the floor on how short a window can usefully be, and the whole example is fifteen seconds.
     */
    private static final Duration SCHEDULE_CHECK_INTERVAL = Duration.ofMillis(500);
    private static final Duration PRE_CLOSE_WARNING = Duration.ofSeconds(1);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    @CommandLine.Option(names = "-w", description = "Seconds the first schedule window stays open", defaultValue = "5")
    private long windowSeconds;

    @CommandLine.Option(names = "-g", description = "Seconds spent outside the schedule between the two windows", defaultValue = "3")
    private long gapSeconds;

    @CommandLine.Option(names = "-u", description = "Seconds the session stays up in the second window before the engine is stopped", defaultValue = "5")
    private long upBeforeShutdownSeconds;

    @CommandLine.ArgGroup(exclusive = false)
    private ExampleOptions exampleOptions = new ExampleOptions();

    /**
     * Built in {@link #call()} before the engine is, and read back by {@link #getFixSessionSetting} for every session
     * the engine builder creates - so both ends of the session share one schedule, as two counterparties would.
     */
    private FixSessionSettings.SessionScheduleSettings schedule;

    public static void main(String... args) {
        System.exit(new CommandLine(new SessionLifecycleExample()).execute(args));
    }

    /**
     * The schedule this example hands to both ends, in place of the trading hours a deployment would configure.
     * <p>
     * Both ends of a window carry a day as well as a time, taken from the {@link LocalDateTime} each was computed
     * from, which is what lets the example be run at any time of day: a window whose close falls after midnight
     * simply names the following day, and {@code FixSessionScheduleManager} treats it as the interval it is.
     */
    private static FixSessionSettings.SessionScheduleSettings.ScheduleEntry window(LocalDateTime open, LocalDateTime close) {
        return FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                .startDay(open.getDayOfWeek())
                .startTime(open.toLocalTime())
                .endDay(close.getDayOfWeek())
                .endTime(close.toLocalTime())
                .build();
    }

    private static void parkUntil(LocalDateTime until, ZoneId zone) {
        while (LocalDateTime.now(zone).isBefore(until)) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
    }

    @Override
    public Integer call() {
        logConfiguration(log, this);
        // one session per side: the story is what happens to a session, and two of them would interleave it
        exampleOptions.setClientsCount(1);

        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime firstOpen = LocalDateTime.now(zone).plus(START_DELAY);
        LocalDateTime firstClose = firstOpen.plusSeconds(windowSeconds);
        LocalDateTime secondOpen = firstClose.plusSeconds(gapSeconds);
        // the second window is left wide open: the shutdown is the last thing the example shows, and it is worth
        // showing on a session that is up rather than on one the schedule had already taken down
        LocalDateTime secondClose = secondOpen.plusHours(1);
        LocalDateTime stopAt = secondOpen.plusSeconds(upBeforeShutdownSeconds);

        schedule = FixSessionSettings.SessionScheduleSettings.builder()
                .timeZone(TimeZone.getTimeZone(zone))
                .withinSessionTimeCheckInterval(SCHEDULE_CHECK_INTERVAL)
                .outsideSessionTimePreTriggerDelay(PRE_CLOSE_WARNING)
                .sessionSchedule(window(firstOpen, firstClose))
                .sessionSchedule(window(secondOpen, secondClose))
                .build();

        log.info("Session schedule for this run, in {}:\n"
                        + "  window 1   {} -> {}   opens in {}s, stays up {}s\n"
                        + "  closed     {} -> {}   {}s outside the schedule, the session stays logged out\n"
                        + "  window 2   {} -> {}   reopens, and the engine is stopped at {} while it is up",
                zone, TIME.format(firstOpen), TIME.format(firstClose), START_DELAY.getSeconds(), windowSeconds,
                TIME.format(firstClose), TIME.format(secondOpen), gapSeconds,
                TIME.format(secondOpen), TIME.format(secondClose), TIME.format(stopAt));

        FixEngine fixEngine = fixEngineBuilder(exampleOptions, true,
                sid -> new LifecycleLoggingApplication("acceptor "),
                sid -> new LifecycleLoggingApplication("initiator"),
                sid -> Map.of(),
                sid -> Map.of())
                .build()
                .instance()
                .start();

        FixAcceptor fixAcceptor = fixEngine.newAcceptor(getFixAcceptorBuilder(exampleOptions)
                .fixSessionEventsListener(new FixAcceptor.FixSessionEventsListener() {
                    @Override
                    public void onFixSessionAccepted(FixSessionId fixSession) {
                        log.info("[acceptor ] onFixSessionAccepted: a connection is bound to {}", fixSession.getId());
                    }

                    @Override
                    public void onFixSessionRejected(FixSessionId fixSession, Exception rejectionException) {
                        log.info("[acceptor ] onFixSessionRejected: {} - {}", fixSession, rejectionException.getMessage());
                    }
                })
                .build()).start();

        FixInitiator fixInitiator = fixEngine.newInitiator(
                getFixInitiatorBuilder(getInitiatorSessionId(1), getInitiatorSenderCompId(1), exampleOptions)
                        .build()).start();

        parkUntil(stopAt, zone);

        log.info("Stopping the engine, which stops the initiator and the acceptor and takes the session down with them");
        fixEngine.stop(Deadline.of(Duration.ofSeconds(10)));
        log.info("Stopped. Initiator connected: {}, acceptor started: {}", fixInitiator.isConnected(), fixAcceptor.isStarted());
        return 0;
    }

    /**
     * The schedule and the heartbeat interval are the only things this example changes about the settings the other
     * examples run with, and it changes them for the acceptor and the initiator alike - a schedule only one end keeps
     * is a session one end keeps trying to log on to.
     */
    @Override
    public FixSessionSettings getFixSessionSetting(FixSessionId sid, FixSession.FixSessionType sessionType, String targetInstancesId,
                                                   boolean resetSequenceOnLogon,
                                                   Function<FixSessionId, Map<FixApplicationSessionSettingDescriptor, String>> fixApplicationSessionSettings,
                                                   ExampleOptions options) {
        return super.getFixSessionSetting(sid, sessionType, targetInstancesId, resetSequenceOnLogon, fixApplicationSessionSettings, options)
                .toBuilder()
                .heartBeatInterval(FixSessionSettings.HeartbeatInterval.builder()
                        .initiatorInterval(HEARTBEAT_INTERVAL)
                        .build())
                .sessionScheduleSettings(schedule)
                .build();
    }

    /**
     * Every callback {@link FixApplication} offers, each logging the one thing it says. Nothing else: no encoders, no
     * decoders, no messages sent - a session's life with the business logic taken out of it.
     * <p>
     * One instance per side, named by {@code role}, so a line says which end of the session is speaking. There is one
     * session per side in this example, which is why the session id is only logged where it identifies something new.
     */
    @RequiredArgsConstructor
    private static final class LifecycleLoggingApplication implements FixApplication {

        private final String role;

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        // ---------------------------------------------------------------- the session being built and taken apart

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
            log("setup: decoders are asked for on " + fixSessionSettings.getFixSessionId().getId()
                    + ", before anything else. This application maps none");
            return List.of();
        }

        @Override
        public void onSessionCreated(FixSession fixSession, FieldsRegistry fieldsRegistry, MessageTypeRegistry messageTypeRegistry,
                                     List<FixMessageDecoder> decoders) {
            log("onSessionCreated: " + fixSession.getFixSessionId().getId()
                    + " exists and its plugins are up, nothing is connected yet");
        }

        @Override
        public void onSessionPreDestroy(FixSession fixSession) {
            log("onSessionPreDestroy: the session is going away; it may still be logged in, so a last message could go out here");
        }

        @Override
        public void onSessionDestroyed(FixSession fixSession) {
            log("onSessionDestroyed: logged out and past sending anything");
        }

        @Override
        public void destroy() {
            log.info("[{}] destroy: the application itself is being discarded", role);
        }

        // ---------------------------------------------------------------- the schedule opening and closing

        @Override
        public void onInsideSessionTime(FixSession fixSession) {
            log("onInsideSessionTime: the schedule window is open, the session may come up");
        }

        @Override
        public void onPreOutsideSessionTime(FixSession fixSession, Duration outsideSessionTimeDelay) {
            log("onPreOutsideSessionTime: the window closes in " + outsideSessionTimeDelay.toMillis() + "ms");
        }

        @Override
        public void onOutsideSessionTime(FixSession fixSession) {
            log("onOutsideSessionTime: the window is closed, the session logs out and stays down until it reopens");
        }

        // ---------------------------------------------------------------- logon, logout, disconnection

        @Override
        public CompletableFuture<Optional<String>> validateLogon(FixSession fixSession, DecodedFixMessage logonMessage, Executor executor) {
            log("validateLogon: the peer's Logon is in, credentials would be checked here");
            // null means "nothing to validate"; an Optional carrying a message would reject the logon with it. Returning
            // a future rather than a decision is what lets a real check run off the IO thread, on the executor above
            return null;
        }

        @Override
        public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
            log("onLogon: the session is usable, sequence numbers are settled");
        }

        @Override
        public void onPreLogout(FixSession fixSession, String message, boolean logoutInitiated) {
            log("onPreLogout: " + (logoutInitiated ? "this end is sending a Logout" : "the peer asked to log out, this end answers next")
                    + ", a last message could go out here - '" + message + "'");
        }

        @Override
        public void onLogout(FixSession fixSession, String message, DecodedFixMessage logoutMessage) {
            log("onLogout: logged out - '" + message + "'"
                    + (logoutMessage == null ? " (no Logout received: the connection went)" : " (Logout received)"));
        }

        @Override
        public void onDisconnected(FixSession fixSession) {
            log("onDisconnected: the connection is gone");
        }

        @Override
        public void onCancelOnDisconnectTriggered(FixSession fixSession, CancelOnDisconnectType cancelOnDisconnectType) {
            log("onCancelOnDisconnectTriggered: " + cancelOnDisconnectType);
        }

        // ---------------------------------------------------------------- while the session is up

        @Override
        public void onHeartbeat(FixSession fixSession, UTCTime remoteTimestamp) {
            log("onHeartbeat: sent by the peer at " + remoteTimestamp.asInstant());
        }

        @Override
        public void onTestRequest(FixSession fixSession, String testReqID, UTCTime remoteTimestamp) {
            log("onTestRequest: the peer is asking whether we are alive, TestReqID " + testReqID);
        }

        @Override
        public void onTestRequestResponse(FixSession fixSession, String testReqID, UTCTime remoteTimestamp) {
            log("onTestRequestResponse: the peer answered our TestReqID " + testReqID);
        }

        @Override
        public void onAdminMessageEncoding(FixSession fixSession, MessageType messageType, Supplier<FixFieldsEncoder> headersAppender,
                                           Supplier<FixFieldsEncoder> bodyAppender, Supplier<FixFieldsEncoder> trailerAppender) {
            log("onAdminMessageEncoding: MsgType(35)=" + messageType.code() + " is being encoded, extra fields could be appended");
        }

        @Override
        public void onMessageEncoding(FixSession fixSession, MessageType messageType, Supplier<FixFieldsEncoder> headersAppender,
                                      Supplier<FixFieldsEncoder> trailerAppender) {
            log("onMessageEncoding: MsgType(35)=" + messageType.code() + " is being encoded");
        }

        // ---------------------------------------------------------------- what nothing in this example provokes,
        // ---------------------------------------------------------------- kept so the list of callbacks is complete

        @Override
        public boolean onNoDecoderSetupForMessage(FixSession fixSession, MessageType messageType) {
            log("onNoDecoderSetupForMessage: MsgType(35)=" + messageType.code() + " arrived with no decoder mapped for it");
            // true would answer the peer with a BusinessMessageReject
            return false;
        }

        @Override
        public void onMessageSendingFailure(FixSession fixSession, MessageType messageType, ByteBuffer message, Exception sendingError) {
            log("onMessageSendingFailure: MsgType(35)=" + messageType.code() + " never left - " + sendingError);
        }

        @Override
        public boolean onResendRequest(FixSession fixSession, MessageType messageType, DecodedFixMessage toResend) {
            log("onResendRequest: MsgType(35)=" + messageType.code() + " is about to be retransmitted");
            // false would drop it from the retransmission, which the engine then gap fills
            return true;
        }

        @Override
        public void onResendRequestInitiated(FixSession fixSession, long fromSeqNum, long toSeqNum) {
            log("onResendRequestInitiated: asking the peer for " + fromSeqNum + " to " + toSeqNum);
        }

        @Override
        public void onResendRequestTerminated(FixSession fixSession, long fromSeqNum, long toSeqNum) {
            log("onResendRequestTerminated: " + fromSeqNum + " to " + toSeqNum + " recovered");
        }

        @Override
        public void onSequenceReset(FixSession fixSession, long newSeqNum, boolean gapFill) {
            log("onSequenceReset: outgoing numbering set to " + newSeqNum + (gapFill ? " (gap fill)" : ""));
        }

        @Override
        public void onMessageReject(FixSession fixSession, String rejectText, int rejectReason, long refSeqNum, int refTagId, String refMsgType) {
            log("onMessageReject: the peer rejected our " + refMsgType + " " + refSeqNum + " - " + rejectText);
        }

        @Override
        public void onBusinessMessageReject(FixSession fixSession, String rejectText, int rejectReason, long refSeqNum,
                                            String businessRejectRefId, String refMsgType) {
            log("onBusinessMessageReject: the peer's application rejected our " + refMsgType + " - " + rejectText);
        }

        @Override
        public void onNetworkWatermarkEvent(FixSession fixSession, boolean highWatermarkReached, long bytesLeftToWrite) {
            log("onNetworkWatermarkEvent: " + (highWatermarkReached ? "high" : "low") + " watermark, "
                    + bytesLeftToWrite + " bytes left to write");
        }

        private void log(String event) {
            log.info("[{}] {}", role, event);
        }
    }
}
