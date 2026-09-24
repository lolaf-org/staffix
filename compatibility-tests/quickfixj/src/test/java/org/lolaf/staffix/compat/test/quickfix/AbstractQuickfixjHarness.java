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

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.ToString;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.lolaf.betty.api.settings.SSLSettings;
import org.lolaf.betty.api.settings.ServerSSLSettings;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.*;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.*;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.codec.decoders.DecodedFixMessageDecoder;
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.fix44.encoders.group.NoLinesOfTextEncoder;
import org.lolaf.staffix.fix44.fields.EmailThreadID;
import org.lolaf.staffix.fix44.fields.EmailType;
import org.lolaf.staffix.fix44.fields.Subject;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.impl.session.FixSessionImpl;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.lolaf.staffix.tests.*;
import quickfix.*;
import quickfix.field.EncodedText;
import quickfix.field.EncodedTextLen;
import quickfix.field.PossDupFlag;
import quickfix.field.Text;
import quickfix.fix44.Email;
import quickfix.mina.ssl.SSLSupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The machinery of the QuickFIX/J interoperability tests: a staffix session and a QuickFIX/J session talking to each
 * other over a real socket, one taking the initiator role and the other the acceptor role. Standing the pair up,
 * tearing it down, and every helper the scenarios send and assert through.
 * <p>
 * No scenario lives here. They live in the subclasses that group them - {@link AbstractQuickfixjInterop} for the
 * session-layer suite, {@link AbstractQuickfixjSslInterop} for the ones that need TLS - which is what lets a group be
 * run on its own rather than every class inheriting all of them. The concrete classes below those declare only which
 * side staffix plays ({@link #staffixRole()}), so a scenario written once runs in both directions: a behaviour
 * staffix only gets right when it drives the connection is not interoperability.
 * <p>
 * The QuickFIX/J side is configured programmatically rather than from a {@code .cfg} file, because the port has to be
 * picked free per test - see {@link #port} - and a config file cannot express that.
 * <p>
 * Everything the FIX version changes is one of the hooks grouped below {@link #staffixIsInitiator()}, defaulting to
 * FIX.4.4 and overridden by {@link AbstractQuickfixjFixtInterop} for FIXT.1.1 carrying FIX 5.0 SP2.
 */
abstract class AbstractQuickfixjHarness {

    /**
     * How many messages each resend scenario sends before opening a gap, and how wide that gap is.
     */
    static final int MESSAGES_BEFORE_GAP = 5;
    static final int MISSED_MESSAGES = 3;
    /**
     * How far ahead of the peer's expectation a hard reset is made to arrive. Section 4.8.6 has it processed "without
     * regard to its own MsgSeqNum(34)", and sending it out of sequence is what tells the two SequenceReset(35=4)
     * messages apart: a gap fill that far ahead is queued as the far end of a gap and answered with a
     * ResendRequest(35=2) instead of being applied. In sequence the two are indistinguishable from the outside, and a
     * scenario built that way passes just as happily with GapFillFlag(123)=Y - which is how this constant came to
     * exist.
     */
    static final int HARD_RESET_SEQ_NUM_OVERSHOOT = 500;
    /**
     * SessionRejectReason(373), which has no {@link CoreFields} constant, and its "value is incorrect (out of range)
     * for this tag" reason.
     */
    static final int SESSION_REJECT_REASON = 373;
    static final String INCORRECT_VALUE_FOR_TAG = "5";
    private static final String FIX_44_BEGIN_STRING = "FIX.4.4";
    /**
     * QuickFIX/J's own dictionaries, extracted once per JVM rather than once per test. See
     * {@link #quickfixOwnDictionary}.
     */
    private static final Map<String, String> QUICKFIX_DICTIONARIES = new ConcurrentHashMap<>();
    /**
     * Emails the staffix side decoded, in arrival order.
     */
    final List<ReceivedEmail> staffixReceivedEmails = new CopyOnWriteArrayList<>();
    /**
     * Emails the QuickFIX/J side received through {@code fromApp}, in arrival order.
     */
    final List<ReceivedEmail> quickfixReceivedEmails = new CopyOnWriteArrayList<>();
    /**
     * The clock the staffix connector runs on, so that a scenario can move the session in and out of its schedule
     * rather than wait. Left at real time unless a scenario pins it.
     */
    final TestingClock staffixClock = new TestingClock();
    /**
     * The port the acceptor of the pair binds and the initiator connects to. Picked free for every test so that test
     * classes can run in parallel, and so that a stray process holding a fixed port cannot fail the whole suite.
     */
    int port;
    /**
     * Comp id of the side that initiates, whichever engine is playing it. Carries the port because QuickFIX/J keeps
     * its sessions in a registry keyed by SessionID and shared by the whole JVM: two test classes running at the same
     * time under the same comp ids would be the same session as far as {@link Session#sendToTarget} is concerned.
     */
    String initiatorCompId;
    /**
     * Comp id of the side that accepts, whichever engine is playing it. Unique per test, see {@link #initiatorCompId}.
     */
    String acceptorCompId;
    /**
     * What the staffix side is configured to do with ResetSeqNumFlag(141) on logon. Null - the default - leaves it to
     * the counterparty; TRUE asks for a reset; FALSE refuses one, and answers a Logon carrying 141=Y with a Logout.
     * Set before {@link #logon()}, which is where the engines are built.
     */
    Boolean staffixResetSeqNumOnLogon;
    /**
     * Whether the QuickFIX/J side sends ResetSeqNumFlag(141)=Y on its Logon, its {@code ResetOnLogon} setting. Only
     * an initiator sends a Logon of its own, so this does nothing when QuickFIX/J is the acceptor. Set before
     * {@link #logon()}.
     */
    boolean quickfixResetOnLogon;
    /**
     * Whether the staffix side agrees to retransmit application messages, i.e. what
     * {@link FixApplication#onResendRequest} answers. A session that says no sends a SequenceReset-GapFill in place of
     * the messages it withheld. Set before {@link #logon()}.
     */
    boolean staffixResendsApplicationMessages = true;
    /**
     * The mirror of {@link #staffixResendsApplicationMessages} on the other side: whether the QuickFIX/J side agrees
     * to retransmit application messages, i.e. whether its {@code Application.toApp} throws {@code DoNotSend} when
     * asked to resend one. A session that says no covers the messages it withheld with a SequenceReset-GapFill, which
     * is what makes staffix the <em>consumer</em> of a gap fill rather than its producer. Set before {@link #logon()}.
     */
    boolean quickfixResendsApplicationMessages = true;
    /**
     * How many messages of a retransmission the staffix application agrees to before withholding everything after
     * them, so that a range can be made to <em>end</em> on a SequenceReset-GapFill rather than on a real message.
     * Unlimited by default, which leaves {@link #staffixResendsApplicationMessages} in sole charge. Set before
     * {@link #logon()}.
     */
    int staffixResendsBeforeWithholdingTheRest = Integer.MAX_VALUE;
    /**
     * The trading window the staffix session keeps, or null for a session that is always open. Set before
     * {@link #logon()}, together with {@link #staffixClock}, which is what lets a scenario cross the window's edges
     * without waiting for them.
     */
    FixSessionSettings.SessionScheduleSettings staffixSessionSchedule;
    /**
     * How the staffix session fills in EndSeqNo(16) on a ResendRequest(35=2) it sends, its {@code
     * FixSessionSettings.resendRequestRange}. The QuickFIX/J equivalent is {@code ClosedResendInterval}, whose default
     * is the open ended form this one calls {@link ResendRequestRange#OPEN_ENDED} - so the two engines differ here
     * unless a scenario says otherwise. Set before {@link #logon()}.
     */
    ResendRequestRange staffixResendRequestRange = ResendRequestRange.CLOSED;
    /**
     * Whether the QuickFIX/J side takes part in NextExpectedMsgSeqNum(789), its {@code EnableNextExpectedMsgSeqNum}
     * setting. The one flag governs both sending the field and acting on a received one, and defaults to false in
     * QuickFIX/J - so, left alone, QuickFIX/J ignores the 789 staffix advertises and recovery falls back on
     * ResendRequest(35=2). staffix has the equivalent setting on by default, see {@code
     * FixSessionSettings.enabledLogonNextExpectedMsgSeqNum}. Set before {@link #logon()}.
     */
    boolean quickfixEnableNextExpectedMsgSeqNum;
    /**
     * Whether the QuickFIX/J side throws its sequence numbers away when the connection drops, its
     * {@code ResetOnDisconnect} setting. staffix has no equivalent - the FIX session layer negotiates a reset through
     * ResetSeqNumFlag(141) on the Logon rather than by each end deciding for itself - so this is only ever one end
     * restarting its numbering without saying so. Set before {@link #logon()}.
     */
    boolean quickfixResetOnDisconnect;
    /**
     * As {@link #quickfixResetOnDisconnect}, but triggered by a clean logout rather than by a dropped connection: the
     * {@code ResetOnLogout} setting. What the peer sees afterwards is the same either way.
     */
    boolean quickfixResetOnLogout;
    FixEngine staffixEngine;
    FixApplication staffixApplication;
    FixSession staffixSession;
    TestingFixSessionMessagesStore staffixMessagesStore;
    TestingLogger staffixLogger;
    /**
     * The staffix connector under test. Exactly one of {@link #staffixInitiator} / {@link #staffixAcceptor} is set,
     * according to {@link #staffixRole()}.
     */
    FixInitiator staffixInitiator;
    FixAcceptor staffixAcceptor;
    Application quickfixApplication;
    SessionID quickfixSessionId;
    Connector quickfixConnector;

    @BeforeAll
    static void raiseAwaitilityDefaults() {
        Awaitility.setDefaultPollDelay(100, TimeUnit.MICROSECONDS);
        Awaitility.setDefaultPollInterval(200, TimeUnit.MICROSECONDS);
        Awaitility.setDefaultTimeout(Duration.ofSeconds(30));
    }

    private static boolean isResendRequest(String message) {
        return isOfType(message, CoreMessageType.RESEND_REQUEST);
    }

    /**
     * A Reject(35=3) blaming SessionRejectReason(373)=5, "value is incorrect for this tag", which is what both
     * engines answer a hard reset that would lower the sequence number with.
     */
    private static boolean isRejectForIncorrectValue(String message) {
        return isOfType(message, CoreMessageType.REJECT)
                && FixMessageFields.hasFieldWithValue(message, SESSION_REJECT_REASON, INCORRECT_VALUE_FOR_TAG);
    }

    private static boolean isOfType(String message, String msgType) {
        return FixMessageFields.hasFieldWithValue(message, CoreFields.MESSAGE_TYPE, msgType);
    }

    /**
     * The messages of one MsgType(35), joined into the single text {@link FixMessageAssert} asserts on.
     * <p>
     * Narrowing before asserting is what keeps the pairing that matters. {@code FixMessageAssert} applies itself to
     * the fields of everything it is handed, so asserting "the Logon carries NextExpectedMsgSeqNum(789)" against a
     * whole wire log would also pass on a 789 that came in on some other message. Joined on the field separator, not
     * end to end, so that the checksum of one message cannot merge with the BeginString of the next into a token that
     * parses as neither.
     */
    static String messagesOfType(List<String> messages, String msgType) {
        return messages.stream()
                .filter(message -> isOfType(message, msgType))
                .collect(Collectors.joining(String.valueOf(CoreFields.FIELD_SEPARATOR)));
    }

    static String emailThreadId(int index) {
        return "test thread id " + index;
    }

    static String emailSubject(int index) {
        return "test subject " + index;
    }

    /**
     * Extracts one of QuickFIX/J's bundled dictionaries from the jar that ships it, and answers a path QuickFIX/J can
     * be pointed at.
     * <p>
     * <b>This is not incidental plumbing.</b> staffix ships dictionaries under exactly the same classpath-root names
     * as QuickFIX/J - both have a {@code /FIX44.xml} and a {@code /FIX50SP2.xml} - and staffix's come first on this
     * module's test classpath. Left to load by name, QuickFIX/J therefore validates against <em>staffix's</em>
     * dictionary, which makes an interoperability test that is really one implementation checking itself. With FIX 4.4
     * that went unnoticed for as long as the two files agreed; with FIX 5.0 SP2 it does not even start, staffix
     * writing {@code servicepack="SP2"} where QuickFIX/J's parser wants an integer.
     * <p>
     * Resolving through the jar rather than shipping a copy of the file keeps this honest: whatever dictionary the
     * QuickFIX/J version in the pom ships is the one it is held to, with nothing here to drift out of date.
     */
    @SneakyThrows
    private static String quickfixOwnDictionary(String resourceName) {
        return QUICKFIX_DICTIONARIES.computeIfAbsent(resourceName, name -> {
            try {
                URL quickfixCopy = null;
                for (URL candidate : Collections.list(
                        AbstractQuickfixjHarness.class.getClassLoader().getResources(name))) {
                    if (candidate.getPath().contains("quickfixj")) {
                        quickfixCopy = candidate;
                        break;
                    }
                }
                assertThat(quickfixCopy)
                        .as("QuickFIX/J must ship %s itself, or it is not its own dictionary being used", name)
                        .isNotNull();
                Path extracted = Files.createTempFile("quickfixj-", "-" + name);
                extracted.toFile().deleteOnExit();
                try (InputStream in = quickfixCopy.openStream()) {
                    Files.copy(in, extracted, StandardCopyOption.REPLACE_EXISTING);
                }
                return extracted.toAbsolutePath().toString();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * A staffix decoder that hands every decoded Email to {@code consumer}. The decoded message is only valid for the
     * duration of the callback, so the fields the assertions need are copied out of it there and then.
     */
    FixMessageDecoder recordingEmailDecoder(Consumer<ReceivedEmail> consumer) {
        return new DecodedFixMessageDecoder(MessageTypes.Email) {
            @Override
            public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
                DecodedFixMessage decoded = getDecodedFixMessage();
                consumer.accept(new ReceivedEmail(
                        decoded.getString(EmailThreadID.get(), ""),
                        decoded.getString(Subject.get(), ""),
                        possDupFlag,
                        possResend));
                super.onDecoded(fixSession, possDupFlag, possResend);
            }
        };
    }

    /**
     * Which side of the session staffix plays. QuickFIX/J plays the other one.
     */
    abstract FixSession.FixSessionType staffixRole();

    // ---------------------------------------------------------------------------------------------------------------
    // What the FIX version costs the harness. Everything version-specific is one of the hooks below, defaulting to
    // FIX.4.4; AbstractQuickfixjFixtInterop overrides them for FIXT.1.1 carrying FIX 5.0 SP2. Nothing else in this
    // class names a version - the quickfix.field.* classes it uses are shared across QuickFIX/J's dictionaries.
    // ---------------------------------------------------------------------------------------------------------------

    boolean staffixIsInitiator() {
        return staffixRole() == FixSession.FixSessionType.INITIATOR;
    }

    /**
     * BeginString(8) of the session, i.e. what QuickFIX/J keys its {@link SessionID} on.
     */
    String beginString() {
        return FIX_44_BEGIN_STRING;
    }

    /**
     * Distinguishes this dialect's comp ids from another's. Only cosmetic - what makes them unique per test is the
     * port they carry, see {@link #initiatorCompId}.
     */
    String compIdTag() {
        return "44";
    }

    /**
     * The application's own version, which the staffix session advertises on its Logon. Unrelated to the session's FIX
     * version.
     */
    FixApiVersion staffixApiVersion() {
        return FixApiVersion.of(FixRegularVersion.VERSION_44);
    }

    /**
     * The staffix session id, which carries the FIX version and, under FIXT, the DefaultApplVerID(1137) the session
     * defaults to.
     */
    FixSessionId staffixFixSessionId() {
        return FixSessionId.of("test", FixRegularVersion.VERSION_44,
                staffixIsInitiator() ? initiatorCompId : acceptorCompId,
                staffixIsInitiator() ? acceptorCompId : initiatorCompId);
    }

    /**
     * QuickFIX/J's {@code DefaultApplVerID}, which a FIXT session cannot start without. Null for a session whose
     * BeginString already names the application version, where QuickFIX/J rejects the setting as meaningless.
     */
    String quickfixDefaultApplVerId() {
        return null;
    }

    /**
     * The dictionary QuickFIX/J validates application messages against, by the name it has inside QuickFIX/J's own
     * jars.
     */
    String quickfixApplicationDictionaryResource() {
        return "FIX44.xml";
    }

    /**
     * The dictionary QuickFIX/J validates session-level messages against. Null outside FIXT, where the one dictionary
     * covers both and QuickFIX/J wants it under {@code DataDictionary} instead.
     */
    String quickfixTransportDictionaryResource() {
        return null;
    }

    /**
     * Whether the pair talks over TLS, mutually authenticated with the certificates {@link SSLUtils} shares. Off for
     * everything but {@link AbstractQuickfixjSslInterop}: TLS is a transport concern the FIX session layer above it
     * cannot tell apart, so paying for it in every scenario would buy nothing.
     * <p>
     * Whichever engine plays the initiator uses the client store pair and whichever plays the acceptor uses the
     * server pair, so the two ends present the certificate the other one trusts however the roles fall.
     */
    boolean useSsl() {
        return false;
    }

    /**
     * Only what a test may need to look at or change before the engines exist. The engines themselves are built by
     * {@link #startConnectors()}, so that a scenario can set the knobs above - ResetSeqNumFlag(141) handling and the
     * like - in its own body rather than having to be a class of its own to carry a different configuration.
     */
    @BeforeEach
    void setup() {
        port = TestPorts.findFree();
        initiatorCompId = "SENDER" + compIdTag() + "_" + port;
        acceptorCompId = "TARGET" + compIdTag() + "_" + port;
    }

    private void setupStaffixSide() {
        staffixMessagesStore = new TestingFixSessionMessagesStore();
        staffixLogger = spy(new TestingLogger(staffixIsInitiator() ? "STAFFIX-INI" : "STAFFIX-ACC"));

        staffixApplication = mock(FixApplication.class);
        when(staffixApplication.getFixApiVersion()).thenReturn(staffixApiVersion());
        when(staffixApplication.validateLogon(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        // an Email decoder, otherwise an inbound application message is answered with onNoDecoderSetupForMessage
        when(staffixApplication.setup(any(), any(), any()))
                .thenReturn(List.of(recordingEmailDecoder(staffixReceivedEmails::add)));
        // onResendRequest is asked once per message of the range, in order, so counting the calls is enough to
        // withhold a tail without having to decode anything version-specific out of the message
        AtomicInteger resendRequests = new AtomicInteger();
        when(staffixApplication.onResendRequest(any(), any(), any())).thenAnswer(invocation ->
                staffixResendsApplicationMessages
                        && resendRequests.getAndIncrement() < staffixResendsBeforeWithholdingTheRest);
        doNothing().when(staffixApplication)
                .onSessionCreated(assertArg((Consumer<FixSession>) session -> staffixSession = session), any(), any(), any());

        staffixEngine = FixEngineBuilder.builder()
                .fixMessagesStore(TestingFixMessagesStoreSettings.builder()
                        .testingFixSessionMessagesStore(staffixMessagesStore)
                        .build())
                .fixMessagesLogger(TestingFixMessagesLoggerSettings.builder()
                        .testingLogger(staffixLogger)
                        .build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(staffixSessionSettings()).build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .application(InstanceProvider.DEFAULT_INSTANCE_ID, staffixApplication).build())
                .build()
                .instance();
        staffixEngine.start();

        if (staffixIsInitiator()) {
            var initiator = FixInitiatorBuilder.builder()
                    .fixSessionId(staffixSessionSettings().getFixSessionId())
                    .connectionRetry(Duration.ofSeconds(1))
                    .connectAddress(new InetSocketAddress("localhost", port))
                    .clock(staffixClock);
            if (useSsl()) {
                initiator.sslSettings(SSLSettings.builder().sslContext(SSLUtils.getClientSSLContext()).build());
            }
            staffixInitiator = staffixEngine.newInitiator(initiator.build());
        } else {
            var acceptor = FixAcceptorBuilder.builder()
                    .bindAddress(new InetSocketAddress("localhost", port))
                    .clock(staffixClock);
            if (useSsl()) {
                // mutual: the stores are set up for it either way round, and a peer certificate is what tells a
                // negotiated connection from a plaintext one that merely worked
                acceptor.serverSSLSettings(ServerSSLSettings.builder()
                        .sslContext(SSLUtils.getServerSSLContext())
                        .needClientAuth(true)
                        .wantClientAuth(true)
                        .build());
            }
            staffixAcceptor = staffixEngine.newAcceptor(acceptor.build());
        }
    }

    @SneakyThrows
    private void setupQuickfixSide() {
        quickfixSessionId = new SessionID(beginString(),
                staffixIsInitiator() ? acceptorCompId : initiatorCompId,
                staffixIsInitiator() ? initiatorCompId : acceptorCompId);

        quickfixApplication = mock(Application.class);
        doAnswer(invocation -> {
            quickfixReceivedEmails.add(ReceivedEmail.of(invocation.getArgument(0)));
            return null;
        }).when(quickfixApplication).fromApp(any(Message.class), any(SessionID.class));
        // toApp is QuickFIX/J's counterpart of FixApplication.onResendRequest, and DoNotSend its counterpart of
        // answering false: the resend path catches it and covers the message with a SequenceReset-GapFill instead.
        // It is called for every outgoing application message though, not only retransmitted ones, so the withholding
        // is keyed on PossDupFlag(43) - which QuickFIX/J has already set by this point on a resend, and never sets on
        // an original.
        doAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            if (!quickfixResendsApplicationMessages && message.getHeader().isSetField(PossDupFlag.FIELD)
                    && message.getHeader().getBoolean(PossDupFlag.FIELD)) {
                throw new DoNotSend();
            }
            return null;
        }).when(quickfixApplication).toApp(any(Message.class), any(SessionID.class));

        SessionSettings sessionSettings = quickfixSessionSettings();
        // a memory store, so that nothing survives a test and no two tests share a directory on disk
        quickfixConnector = staffixIsInitiator()
                ? SocketAcceptor.newBuilder()
                .withSettings(sessionSettings)
                .withApplication(quickfixApplication)
                .withMessageFactory(new DefaultMessageFactory())
                .withMessageStoreFactory(new MemoryStoreFactory())
                .build()
                : SocketInitiator.newBuilder()
                .withSettings(sessionSettings)
                .withApplication(quickfixApplication)
                .withMessageFactory(new DefaultMessageFactory())
                .withMessageStoreFactory(new MemoryStoreFactory())
                .build();
    }

    /**
     * The QuickFIX/J configuration, built in code so that the socket port can be the free one picked for this test.
     * Subclasses and scenarios needing a different QuickFIX/J behaviour - {@code ResetOnLogon} and friends - override
     * {@link #customiseQuickfixSettings(SessionSettings, SessionID)}.
     */
    @SneakyThrows
    private SessionSettings quickfixSessionSettings() {
        Dictionary defaults = new Dictionary();
        defaults.setString("ConnectionType", staffixIsInitiator() ? "acceptor" : "initiator");
        // NonStopSession, and not StartTime == EndTime, is what asks QuickFIX/J for a session that never rolls:
        // DefaultSessionSchedule takes isNonStopSession from this setting alone, and isSameSession then answers true
        // unconditionally, so the session is never out of time and never resets. Equal start and end times mean
        // something else - a 24 hour session that still rolls at that time, restarting its sequence numbers - which
        // would have these scenarios reset underneath themselves if one happened to run across midnight UTC.
        defaults.setString(Session.SETTING_NON_STOP_SESSION, "Y");
        defaults.setString("HeartBtInt", "5");
        defaults.setString("TimeStampPrecision", "MICROS");
        defaults.setString("UseDataDictionary", "Y");
        defaults.setString("ReconnectInterval", "1");
        // as a string, deliberately: Dictionary.setBool puts a Boolean into what ends up being a Properties, and
        // Properties.getProperty answers null for a value that is not a String, so the setting would go missing
        // without a word
        defaults.setString(Session.SETTING_RESET_ON_LOGON, quickfixResetOnLogon ? "Y" : "N");
        defaults.setString(Session.SETTING_ENABLE_NEXT_EXPECTED_MSG_SEQ_NUM, quickfixEnableNextExpectedMsgSeqNum ? "Y" : "N");
        defaults.setString(Session.SETTING_RESET_ON_DISCONNECT, quickfixResetOnDisconnect ? "Y" : "N");
        defaults.setString(Session.SETTING_RESET_ON_LOGOUT, quickfixResetOnLogout ? "Y" : "N");
        // a FIXT session has no application version in its BeginString(8), so QuickFIX/J will not start one without
        // being told which dictionary its application messages belong to
        if (quickfixDefaultApplVerId() != null) {
            defaults.setString(Session.SETTING_DEFAULT_APPL_VER_ID, quickfixDefaultApplVerId());
        }
        if (useSsl()) {
            // the mirror of what setupStaffixSide gives the staffix end: QuickFIX/J presents the store belonging to
            // the role it is playing, and trusts the one the other end presents. The names resolve off the classpath
            // through quickfix.FileUtil.open, so the shared resources need no unpacking.
            boolean quickfixIsInitiator = !staffixIsInitiator();
            defaults.setString(SSLSupport.SETTING_USE_SSL, "Y");
            defaults.setString(SSLSupport.SETTING_KEY_STORE_NAME,
                    quickfixIsInitiator ? "clientkeystore.p12" : "serverkeystore.p12");
            defaults.setString(SSLSupport.SETTING_KEY_STORE_PWD, SSLUtils.STORE_PASSWORD);
            defaults.setString(SSLSupport.SETTING_KEY_STORE_TYPE, "PKCS12");
            defaults.setString(SSLSupport.SETTING_TRUST_STORE_NAME,
                    quickfixIsInitiator ? "clienttruststore.jks" : "servertruststore.jks");
            defaults.setString(SSLSupport.SETTING_TRUST_STORE_PWD, SSLUtils.STORE_PASSWORD);
            defaults.setString(SSLSupport.SETTING_TRUST_STORE_TYPE, "JKS");
            if (!quickfixIsInitiator) {
                defaults.setString(SSLSupport.SETTING_NEED_CLIENT_AUTH, "Y");
            }
        }
        // and it is told which dictionary explicitly, see quickfixOwnDictionary
        if (quickfixTransportDictionaryResource() == null) {
            defaults.setString(Session.SETTING_DATA_DICTIONARY,
                    quickfixOwnDictionary(quickfixApplicationDictionaryResource()));
        } else {
            defaults.setString(Session.SETTING_TRANSPORT_DATA_DICTIONARY,
                    quickfixOwnDictionary(quickfixTransportDictionaryResource()));
            defaults.setString(Session.SETTING_APP_DATA_DICTIONARY,
                    quickfixOwnDictionary(quickfixApplicationDictionaryResource()));
        }

        SessionSettings sessionSettings = new SessionSettings();
        sessionSettings.set(defaults);
        if (staffixIsInitiator()) {
            sessionSettings.setString(quickfixSessionId, "SocketAcceptPort", String.valueOf(port));
        } else {
            sessionSettings.setString(quickfixSessionId, "SocketConnectHost", "localhost");
            sessionSettings.setString(quickfixSessionId, "SocketConnectPort", String.valueOf(port));
        }
        customiseQuickfixSettings(sessionSettings, quickfixSessionId);
        return sessionSettings;
    }

    /**
     * Hook for a test needing a QuickFIX/J setting the defaults above do not carry. Does nothing by default.
     */
    void customiseQuickfixSettings(SessionSettings sessionSettings, SessionID sessionId) {
    }

    private FixSessionSettings staffixSessionSettings() {
        FixSessionSettings.FixSessionSettingsBuilder<?, ?> builder = FixSessionSettings.builder()
                .fixSessionId(staffixFixSessionId())
                .fixSessionType(staffixRole())
                // null, as FixSessionSettings itself defaults to: the counterparty manages ResetSeqNumFlag(141) and
                // this session follows it. An explicit false means "resetting is not supported" and is answered with a
                // Logout, which is a deliberate choice a test should make rather than inherit.
                .resetSeqNumOnLogon(staffixResetSeqNumOnLogon)
                .resendRequestRange(staffixResendRequestRange);
        if (staffixSessionSchedule != null) {
            builder.sessionScheduleSettings(staffixSessionSchedule);
        }
        if (staffixIsInitiator()) {
            builder.heartBeatInterval(FixSessionSettings.HeartbeatInterval.builder()
                            .initiatorInterval(Duration.ofSeconds(5)).build())
                    .desiredSessionState(FixSessionState.LOGGED_OUT);
        } else {
            builder.desiredSessionState(FixSessionState.LOGGED_IN);
        }
        return customiseStaffixSettings(builder).build();
    }

    /**
     * Hook for a test needing staffix session settings other than the defaults above. Returns the builder unchanged
     * by default.
     */
    FixSessionSettings.FixSessionSettingsBuilder<?, ?> customiseStaffixSettings(
            FixSessionSettings.FixSessionSettingsBuilder<?, ?> builder) {
        return builder;
    }

    @AfterEach
    void shutdown() {
        if (staffixSession != null && staffixSession.isLoggedIn()) {
            staffixSession.logoutPermanently("finished test");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1000));
        }
        if (staffixEngine != null) {
            staffixEngine.stop(Deadline.unlimited());
        }
        if (quickfixConnector != null) {
            quickfixConnector.stop();
        }
        if (staffixLogger != null) {
            staffixLogger.clear();
        }
    }

    /**
     * Pushes the next MsgSeqNum(34) the initiator of the pair expects to receive far beyond anything the acceptor has
     * sent, which is the number its next Logon(35=A) advertises in NextExpectedMsgSeqNum(789). Returns that number.
     * <p>
     * Only meaningful while the connection is down: on a live session the peer's traffic moves the same counter.
     */
    @SneakyThrows
    long makeTheInitiatorExpectAMessageNeverSent() {
        if (staffixIsInitiator()) {
            long impossible = staffixMessagesStore.getIncomingSeqNum() + 100;
            staffixMessagesStore.setCurrentIncomingSeqNum(impossible);
            return impossible;
        }
        int impossible = quickfixSession().getExpectedTargetNum() + 100;
        quickfixSession().setNextTargetMsgSeqNum(impossible);
        return impossible;
    }

    /**
     * Asserts that both Logons of the last exchange carried NextExpectedMsgSeqNum(789), one seen going out and one
     * coming in. Without it a scenario that meant to exercise 789 could quietly be exercising the ordinary path - the
     * way {@code Dictionary.setBool} once dropped {@code ResetOnLogon} without a word.
     */
    void assertLogonExchangeCarriedNextExpectedMsgSeqNum() {
        await().untilAsserted(() -> assertThatFixMessage(
                messagesOfType(staffixLogger.getOutgoingMessages(), CoreMessageType.LOGON))
                .as("the staffix Logon must carry NextExpectedMsgSeqNum(789)")
                .containsField(CoreFields.NEXT_EXPECTED_MSG_SEQ_NUM));
        await().untilAsserted(() -> assertThatFixMessage(
                messagesOfType(staffixLogger.getIncomingMessages(), CoreMessageType.LOGON))
                .as("the QuickFIX/J Logon must carry NextExpectedMsgSeqNum(789)")
                .containsField(CoreFields.NEXT_EXPECTED_MSG_SEQ_NUM));
    }

    /**
     * Asserts that no ResendRequest(35=2) was sent by either side since the log was last cleared. One logger is enough
     * for both: what staffix sends it logs as outgoing, and what the peer sends it logs as incoming.
     */
    void assertNoResendRequestOnTheWire() {
        assertThat(staffixLogger.getOutgoingMessages())
                .as("staffix must let NextExpectedMsgSeqNum(789) drive the recovery rather than ask for a resend")
                .noneMatch(AbstractQuickfixjHarness::isResendRequest);
        assertThat(staffixLogger.getIncomingMessages())
                .as("QuickFIX/J must let NextExpectedMsgSeqNum(789) drive the recovery rather than ask for a resend")
                .noneMatch(AbstractQuickfixjHarness::isResendRequest);
    }

    /**
     * Puts a hard SequenceReset(35=4) - GapFillFlag(123)=N - on the wire from the QuickFIX/J side without touching
     * the numbering it announces, which is what a test wants when the reset is going to be refused.
     */
    @SneakyThrows
    void emitHardSequenceResetFromQuickfix(long newSeqNo) {
        quickfixSession().setNextSenderMsgSeqNum(quickfixSession().getExpectedSenderNum() + HARD_RESET_SEQ_NUM_OVERSHOOT);
        Message sequenceReset = newQuickfixSequenceReset();
        sequenceReset.setField(new quickfix.field.GapFillFlag(false));
        sequenceReset.setField(new quickfix.field.NewSeqNo((int) newSeqNo));
        assertThat(quickfixSession().send(sequenceReset))
                .as("QuickFIX/J put the SequenceReset on the wire")
                .isTrue();
    }

    /**
     * As {@link #emitHardSequenceResetFromQuickfix}, and renumbers the QuickFIX/J side to {@code newSeqNo} so that
     * the message it sends next really carries the number the reset just announced.
     */
    @SneakyThrows
    void sendHardSequenceResetFromQuickfix(long newSeqNo) {
        emitHardSequenceResetFromQuickfix(newSeqNo);
        quickfixSession().setNextSenderMsgSeqNum((int) newSeqNo);
    }

    /**
     * The staffix counterpart of {@link #emitHardSequenceResetFromQuickfix}. The message itself and the renumbering
     * that has to follow it belong to {@link FixSessionImpl#hardSequenceReset}; all this adds is the overshoot, which
     * is a test stimulus rather than anything a session would do to itself.
     */
    void sendHardSequenceResetFromStaffix(long newSeqNo) {
        staffixMessagesStore.setCurrentOutgoingSeqNum(
                staffixMessagesStore.getOutgoingSeqNum() + HARD_RESET_SEQ_NUM_OVERSHOOT);
        ((FixSessionImpl) staffixSession).hardSequenceReset(newSeqNo);
        await().untilAsserted(() -> assertThat(staffixMessagesStore.getOutgoingSeqNum()).isEqualTo(newSeqNo));
    }

    /**
     * Waits for the Reject a refused hard reset earns and asserts nothing asked for a retransmission before it.
     * <p>
     * The Reject on its own does not say which of the two SequenceReset(35=4) messages arrived: what is refused is
     * the lowering, whichever carried it, and a gap fill sent {@link #HARD_RESET_SEQ_NUM_OVERSHOOT} ahead earns the
     * very same Reject once it has been queued, asked for, retransmitted and finally processed. Only a hard reset
     * gets there with nothing in between.
     * <p>
     * Order rather than absence, deliberately. The overshoot leaves the two ends genuinely out of step once the reset
     * is refused, so the peer's next message - a heartbeat will do - is rightly too high and rightly answered with a
     * ResendRequest(35=2). Asserting none ever appears passes or fails on whether that heartbeat beats the assertion.
     */
    void assertRefusedWithoutAskingForARetransmission(List<String> sentMessages) {
        await().untilAsserted(() -> assertThatFixMessage(messagesOfType(sentMessages, CoreMessageType.REJECT))
                .as("a hard reset that would lower the sequence number must be answered with a Reject(35=3) "
                        + "naming SessionRejectReason(373)=5")
                .containsFieldWithValue(SESSION_REJECT_REASON, INCORRECT_VALUE_FOR_TAG));
        List<String> sentSoFar = List.copyOf(sentMessages);
        int firstReject = 0;
        while (!isRejectForIncorrectValue(sentSoFar.get(firstReject))) {
            firstReject++;
        }
        assertThat(sentSoFar.subList(0, firstReject))
                .as("the refusal must come straight back, with no ResendRequest(35=2) on the way to it")
                .noneMatch(AbstractQuickfixjHarness::isResendRequest);
    }

    /**
     * Drops the socket under the session without logging out, the way a production connection fails: the QuickFIX/J
     * {@link Session} and its store survive, so both sides keep the sequence numbers they had. Driven from the
     * QuickFIX/J side whichever role it plays, that being the one mechanism that reads the same in both directions.
     */
    @SneakyThrows
    void dropConnection() {
        FixSession droppedSession = staffixSession;
        quickfixSession().disconnect("interoperability test", false);
        await().untilAsserted(() -> assertThat(quickfixSession().isLoggedOn()).isFalse());
        await().untilAsserted(() -> assertThat(droppedSession.isLoggedIn()).isFalse());
    }

    /**
     * Waits until the pair has re-established itself after {@link #dropConnection()}. Neither side is told to do
     * anything: the QuickFIX/J initiator retries on its {@code ReconnectInterval}, the staffix initiator on its
     * {@code connectionRetry}, and the staffix session logs on again by itself because {@link #logon()} left its
     * desired state at LOGGED_IN.
     */
    void awaitLoggedBackOn() {
        await().untilAsserted(() -> assertThat(staffixSession.isLoggedIn()).isTrue());
        await().untilAsserted(() -> assertThat(quickfixSession().isLoggedOn()).isTrue());
    }

    /**
     * Logs the session out and back on from the initiator side, whichever engine is playing it, so that a Logon
     * carrying the configured ResetSeqNumFlag(141) is exchanged over a session that has already spent some sequence
     * numbers.
     */
    @SneakyThrows
    void cycleSession() {
        if (staffixIsInitiator()) {
            staffixSession.logoutPermanently("sequence reset test");
            await().untilAsserted(() -> assertThat(staffixSession.isLoggedIn()).isFalse());
            staffixSession.logon();
        } else {
            quickfixSession().logout("sequence reset test");
            await().untilAsserted(() -> assertThat(quickfixSession().isLoggedOn()).isFalse());
            quickfixSession().logon();
        }
        await().untilAsserted(() -> assertThat(staffixSession.isLoggedIn()).isTrue());
        await().untilAsserted(() -> assertThat(quickfixSession().isLoggedOn()).isTrue());
    }

    /**
     * Starts both engines and waits until both sides report being logged on. A staffix initiator is told to log on
     * explicitly, its desired state being LOGGED_OUT; a QuickFIX/J initiator logs on by itself as soon as it connects.
     */
    void logon() {
        startConnectors();

        await().untilAsserted(() -> assertThat(staffixSession).isNotNull());
        if (staffixIsInitiator()) {
            await().untilAsserted(() -> assertThat(staffixSession.isConnected()).isTrue());
            staffixSession.logon();
        }

        await().untilAsserted(() -> verify(staffixApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(quickfixApplication).onLogon(any(SessionID.class)));
    }

    /**
     * Builds both engines from the configuration as it stands, then starts them. The acceptor of the pair goes first,
     * so that the initiator does not spend a retry interval on a closed port.
     */
    @SneakyThrows
    void startConnectors() {
        setupStaffixSide();
        setupQuickfixSide();
        if (staffixIsInitiator()) {
            quickfixConnector.start();
            staffixInitiator.start();
        } else {
            staffixAcceptor.start();
            quickfixConnector.start();
        }
    }

    /**
     * Sends an Email from the staffix side, identified by {@code index} through its EmailThreadID(164).
     */
    void sendEmailFromStaffix(int index) {
        EmailEncoder email = staffixSession.newEncoder(EmailEncoder.class);
        email.begin()
                .setEmailType(EmailType.EmailTypeValues.NEW)
                .setEmailThreadID(emailThreadId(index))
                .setSubject(emailSubject(index));
        NoLinesOfTextEncoder linesOfText = email.addNoLinesOfText(2);
        for (int i = 0; i < 2; i++) {
            linesOfText.setText("test text");
            linesOfText.setEncodedTextLen("encoded text".length());
            linesOfText.setEncodedText("encoded text");
        }
        staffixSession.send(email, null);
    }

    /**
     * The SequenceReset(35=4) the QuickFIX/J side puts on the wire, empty of everything but its message type. A
     * session-level message, so under FIXT it comes from the transport dictionary rather than the application one.
     */
    Message newQuickfixSequenceReset() {
        return new quickfix.fix44.SequenceReset();
    }

    /**
     * Sends an Email from the QuickFIX/J side, identified by {@code index} through its EmailThreadID(164).
     */
    @SneakyThrows
    void sendEmailFromQuickfix(int index) {
        assertThat(Session.sendToTarget(newQuickfixEmail(index), quickfixSessionId))
                .as("QuickFIX/J put the Email on the wire")
                .isTrue();
    }

    /**
     * Sends an Email from a QuickFIX/J side that is currently disconnected. {@link Session#sendToTarget} answers false
     * because there is no responder to hand the message to, but the message has already been given its MsgSeqNum(34)
     * and written to the store by then - which is exactly the backlog the peer has to ask for once the session is back.
     */
    @SneakyThrows
    void sendEmailFromQuickfixWhileDisconnected(int index) {
        assertThat(Session.sendToTarget(newQuickfixEmail(index), quickfixSessionId))
                .as("a QuickFIX/J session with nothing to write to cannot have sent the Email")
                .isFalse();
    }

    /**
     * The Email the QuickFIX/J side sends, an application message and so built from the application dictionary's
     * package. FIX 5.0 SP2 names the repeating group NoLinesOfText where FIX 4.4 names it LinesOfText, which is why
     * this is a whole method rather than a field or two.
     */
    Message newQuickfixEmail(int index) {
        Email email = new Email();
        email.set(new quickfix.field.EmailType(quickfix.field.EmailType.NEW));
        email.set(new quickfix.field.EmailThreadID(emailThreadId(index)));
        email.set(new quickfix.field.Subject(emailSubject(index)));
        for (int i = 0; i < 2; i++) {
            Email.LinesOfText linesOfText = new Email.LinesOfText();
            linesOfText.set(new Text("test text"));
            linesOfText.set(new EncodedTextLen("encoded text".length()));
            linesOfText.set(new EncodedText("encoded text"));
            email.addGroup(linesOfText);
        }
        return email;
    }

    /**
     * Waits until the staffix side has decoded the Email {@code index}, and no other.
     */
    void assertStaffixReceivedEmail(int index) {
        await().untilAsserted(() -> assertThat(staffixReceivedEmails)
                .extracting(ReceivedEmail::getEmailThreadId)
                .contains(emailThreadId(index)));
    }

    /**
     * Waits until the QuickFIX/J side has received the Email {@code index}.
     */
    void assertQuickfixReceivedEmail(int index) {
        await().untilAsserted(() -> assertThat(quickfixReceivedEmails)
                .extracting(ReceivedEmail::getEmailThreadId)
                .contains(emailThreadId(index)));
    }

    /**
     * The QuickFIX/J session of this test, once the engines are up.
     */
    Session quickfixSession() {
        return Session.lookupSession(quickfixSessionId);
    }

    /**
     * Rewinds what the staffix side expects to receive next, so that it believes it missed the {@code count} messages
     * it in fact already has. The next message to arrive then looks like the far end of a gap, which is what makes a
     * session ask for a retransmission without having to take the connection down first.
     * <p>
     * Waits for the store to catch up first: the application sees a message before its MsgSeqNum(34) is stored, so a
     * rewind made on seeing it would be overwritten.
     */
    void makeStaffixExpectMissedMessages(int count) {
        awaitIncomingSequenceResynchronised();
        staffixMessagesStore.setCurrentIncomingSeqNum(staffixMessagesStore.getIncomingSeqNum() - count);
    }

    /**
     * The QuickFIX/J counterpart of {@link #makeStaffixExpectMissedMessages(int)}, with the same wait: QuickFIX/J also
     * advances its expected number after handing the message to the application.
     */
    @SneakyThrows
    void makeQuickfixExpectMissedMessages(int count) {
        awaitSequencesResynchronised();
        quickfixSession().setNextTargetMsgSeqNum(quickfixSession().getExpectedTargetNum() - count);
    }

    /**
     * Waits until the two sides agree again on the next MsgSeqNum(34) staffix will send, which is what a completed
     * retransmission looks like from the outside.
     */
    void awaitSequencesResynchronised() {
        await().untilAsserted(() -> assertThat((long) quickfixSession().getExpectedTargetNum())
                .isEqualTo(staffixMessagesStore.getOutgoingSeqNum()));
    }

    /**
     * The mirror of {@link #awaitSequencesResynchronised()}, for a scenario moving messages the other way: waits until
     * what staffix expects to receive next has caught up with what QuickFIX/J believes it has sent.
     */
    void awaitIncomingSequenceResynchronised() {
        await().untilAsserted(() -> assertThat(staffixMessagesStore.getIncomingSeqNum())
                .isEqualTo((long) quickfixSession().getExpectedSenderNum()));
    }

    /**
     * What the assertions need of an Email that arrived, on either side. PossDupFlag(43) and PossResend(97) are part
     * of it because the resend scenarios turn on them.
     */
    @Getter
    @ToString
    static class ReceivedEmail {

        private final String emailThreadId;
        private final String subject;
        private final boolean possDup;
        private final boolean possResend;

        ReceivedEmail(String emailThreadId, String subject, boolean possDup, boolean possResend) {
            this.emailThreadId = emailThreadId;
            this.subject = subject;
            this.possDup = possDup;
            this.possResend = possResend;
        }

        @SneakyThrows
        static ReceivedEmail of(Message message) {
            return new ReceivedEmail(
                    message.isSetField(quickfix.field.EmailThreadID.FIELD)
                            ? message.getString(quickfix.field.EmailThreadID.FIELD) : "",
                    message.isSetField(quickfix.field.Subject.FIELD)
                            ? message.getString(quickfix.field.Subject.FIELD) : "",
                    message.getHeader().isSetField(quickfix.field.PossDupFlag.FIELD)
                            && message.getHeader().getBoolean(quickfix.field.PossDupFlag.FIELD),
                    message.getHeader().isSetField(quickfix.field.PossResend.FIELD)
                            && message.getHeader().getBoolean(quickfix.field.PossResend.FIELD));
        }
    }
}
