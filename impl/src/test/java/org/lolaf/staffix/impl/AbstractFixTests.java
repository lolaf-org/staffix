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

import lombok.Getter;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.*;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.codec.FixMessageEncoderFactory;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.api.version.SemVer;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.codec.decoders.DecodedFixMessageDecoder;
import org.lolaf.staffix.codec.decoders.DecodedFixMessageImpl;
import org.lolaf.staffix.codec.decoders.FieldMapImpl;
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.fix44.encoders.group.NoLinesOfTextEncoder;
import org.lolaf.staffix.fix44.fields.EmailType;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.impl.session.FixSessionImpl;
import org.lolaf.staffix.codec.serde.StringSerde;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.lolaf.staffix.tests.*;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

abstract class AbstractFixTests {

    /**
     * Long enough to cover the initiator's 100ms connection retry twice over.
     */
    static final Duration REJECTION_SETTLE_TIME = Duration.ofMillis(200);
    /**
     * What an engine gets to stop in. Generous: a stop that reaches it is a hang, not a slow machine.
     */
    static final Duration ENGINE_STOP_DEADLINE = Duration.ofSeconds(30);
    FixInitiator fixInitiator;
    FixAcceptor fixAcceptor;
    FixApplication fixInitiatorApplication;
    FixApplication fixAcceptorApplication;
    FixSession fixAcceptorSession;
    FixSession fixInitiatorSession;
    FixInitiatorBuilder fixInitiatorBuilder;
    TestingLogger initiatorLogger;
    TestingLogger acceptorLogger;
    FixAcceptorBuilder fixAcceptorBuilder;
    FixAcceptor.FixSessionEventsListener fixSessionEventsListener;
    Map<MessageType, FixMessageDecoder> initiatorMessageDecoders;
    Map<MessageType, FixMessageDecoder> acceptorMessageDecoders;
    List<TestingDecodedFixMessage> decodedAcceptorMessages;
    List<TestingDecodedFixMessage> decodedInitiatorMessages;
    TestingFixSessionMessagesStore initiatorMessagesStore;
    TestingFixSessionMessagesStore acceptorMessagesStore;
    FixEngineBuilder initiatorFixEngineBuilder;
    FixEngine initiatorFixEngine;
    FixEngineBuilder acceptorFixEngineBuilder;
    FixEngine acceptorFixEngine;
    MessageType testMessageType;
    List<BiConsumer<FixFieldsDecoderMapper, FieldsRegistry>> onMapFieldsForDecodingListeners;
    TestingClock fixInitiatorClock;
    TestingClock fixAcceptorClock;
    /**
     * The port the acceptor binds and the initiator connects to. Picked free for every test rather than hardcoded, so
     * that these classes can run alongside anything else and a stray process cannot fail the build.
     */
    int acceptorPort;

    @BeforeAll
    static void raiseAwaitilityDefaults() {
        Awaitility.setDefaultPollDelay(100, TimeUnit.MICROSECONDS);
        Awaitility.setDefaultPollInterval(200, TimeUnit.MICROSECONDS);
        Awaitility.setDefaultTimeout(Duration.ofSeconds(30));
    }

    static Stream<ConnectorType> initiatorOrAcceptorParams() {
        return Stream.of(ConnectorType.INITIATOR, ConnectorType.ACCEPTOR);
    }

    @BeforeEach
    void setup() {
        acceptorPort = TestPorts.findFree();

        initiatorLogger = new TestingLogger("INITIATOR");
        acceptorLogger = new TestingLogger("ACCEPTOR");

        setupOrResetFixInitiatorApplication();
        setupOrResetFixAcceptorApplication();

        decodedInitiatorMessages = new CopyOnWriteArrayList<>();
        decodedAcceptorMessages = new CopyOnWriteArrayList<>();
        initiatorMessageDecoders = new HashMap<>();
        acceptorMessageDecoders = new HashMap<>();
        onMapFieldsForDecodingListeners = new ArrayList<>();
        testMessageType = MessageTypes.Email;
        initiatorMessagesStore = new TestingFixSessionMessagesStore();
        acceptorMessagesStore = new TestingFixSessionMessagesStore();

        setupMessageDecoders(ConnectorType.INITIATOR);
        initiatorFixEngineBuilder = FixEngineBuilder.builder()
                .fixMessagesStore(TestingFixMessagesStoreSettings.builder()
                        .testingFixSessionMessagesStore(initiatorMessagesStore)
                        .build())
                .fixMessagesLogger(TestingFixMessagesLoggerSettings.builder()
                        .testingLogger(initiatorLogger)
                        .build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .application(InstanceProvider.DEFAULT_INSTANCE_ID, fixInitiatorApplication)
                        .build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(getInitiatorFixSessionSettings().build()).build())
                .build();
        initiatorFixEngine = initiatorFixEngineBuilder.instance();
        initiatorFixEngine.start();

        fixInitiatorClock = new TestingClock();
        fixInitiatorBuilder = FixInitiatorBuilder.builder()
                .instanceId("test-initiator")
                .connectAddress(new InetSocketAddress("localhost", acceptorPort))
                .connectionRetry(Duration.ofMillis(100))
                .fixSessionId(getInitiatorFixSessionSettings().build().getFixSessionId())
                .clock(fixInitiatorClock)
                .build();
        fixInitiator = initiatorFixEngine.newInitiator(fixInitiatorBuilder);

        setupMessageDecoders(ConnectorType.ACCEPTOR);
        acceptorFixEngineBuilder = FixEngineBuilder.builder()
                .fixMessagesStore(TestingFixMessagesStoreSettings.builder()
                        .testingFixSessionMessagesStore(acceptorMessagesStore)
                        .build())
                .fixMessagesLogger(TestingFixMessagesLoggerSettings.builder()
                        .testingLogger(acceptorLogger)
                        .build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .application(InstanceProvider.DEFAULT_INSTANCE_ID, fixAcceptorApplication)
                        .build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(getAcceptorFixSessionSettings().build()).build())
                .build();
        acceptorFixEngine = acceptorFixEngineBuilder.instance();
        acceptorFixEngine.start();

        fixSessionEventsListener = mock(FixAcceptor.FixSessionEventsListener.class);

        fixAcceptorClock = new TestingClock();
        fixAcceptorBuilder = FixAcceptorBuilder.builder()
                .instanceId("test-acceptor")
                .bindAddress(new InetSocketAddress("localhost", acceptorPort))
                .fixSessionEventsListener(fixSessionEventsListener)
                .clock(fixAcceptorClock)
                .build();
        fixAcceptor = acceptorFixEngine.newAcceptor(fixAcceptorBuilder);
    }

    void setupAcceptorSessionSettings(Function<FixSessionSettings.FixSessionSettingsBuilder<?, ?>, FixSessionSettings> settingsProvider) {
        stopWithinDeadline(acceptorFixEngine, ConnectorType.ACCEPTOR);
        acceptorFixEngine = acceptorFixEngineBuilder.toBuilder()
                .clearFixSessionsSettingsStores()
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(settingsProvider.apply(getAcceptorFixSessionSettings()))
                        .build())
                .build()
                .instance().start();
        fixAcceptor = acceptorFixEngine.newAcceptor(fixAcceptorBuilder);
    }

    void setupInitiatorSessionSettings(Function<FixSessionSettings.FixSessionSettingsBuilder<?, ?>, FixSessionSettings> settingsProvider) {
        stopWithinDeadline(initiatorFixEngine, ConnectorType.INITIATOR);
        initiatorFixEngine = initiatorFixEngineBuilder.toBuilder()
                .clearFixSessionsSettingsStores()
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(settingsProvider.apply(getInitiatorFixSessionSettings()))
                        .build())
                .build()
                .instance().start();
        fixInitiator = initiatorFixEngine.newInitiator(fixInitiatorBuilder);
    }

    void setupSessionSettings(ConnectorType connectorType, Function<FixSessionSettings.FixSessionSettingsBuilder<?, ?>, FixSessionSettings> settingsProvider) {
        if (connectorType.equals(ConnectorType.INITIATOR)) {
            setupInitiatorSessionSettings(settingsProvider);
        } else if (connectorType.equals(ConnectorType.ACCEPTOR)) {
            setupAcceptorSessionSettings(settingsProvider);
        }
    }

    void setupOrResetFixAcceptorApplication() {
        if (fixAcceptorApplication == null) {
            fixAcceptorApplication = mock(FixApplication.class);
        } else {
            reset(fixAcceptorApplication);
        }
        when(fixAcceptorApplication.getFixApiVersion()).thenReturn(FixApiVersion.of("test acceptor app", SemVer.of(1, 2, 3), "test acceptor vendor"));
        when(fixAcceptorApplication.validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class)))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
    }

    void setupOrResetFixInitiatorApplication() {
        if (fixInitiatorApplication == null) {
            fixInitiatorApplication = mock(FixApplication.class);
        } else {
            reset(fixInitiatorApplication);
        }
        when(fixInitiatorApplication.getFixApiVersion()).thenReturn(FixApiVersion.of("test initiator app", SemVer.of(1, 2, 3), "test initiator vendor"));
        when(fixInitiatorApplication.validateLogon(any(FixSession.class), any(DecodedFixMessage.class), any(Executor.class)))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
    }

    @AfterEach
    void shutdown() {
        if (fixAcceptorSession != null && fixAcceptorSession.isLoggedIn()) {
            fixAcceptorSession.logoutPermanently("finished test");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1000));
        }

        stopWithinDeadline(initiatorFixEngine, ConnectorType.INITIATOR);
        stopWithinDeadline(acceptorFixEngine, ConnectorType.ACCEPTOR);

        initiatorLogger.clear();
        acceptorLogger.clear();
        decodedAcceptorMessages.clear();
        decodedInitiatorMessages.clear();
    }

    /**
     * Bounded, and checked. An engine that cannot stop is a product hang, and stopping it with
     * {@link Deadline#unlimited()} made that a build which never finished and never failed: the retransmission
     * hang this test base was written through took its whole run that way. A stop that reaches the deadline is
     * now the test's failure, on the test that caused it.
     */
    private void stopWithinDeadline(FixEngine fixEngine, ConnectorType connectorType) {
        long stoppingSinceNanos = System.nanoTime();
        fixEngine.stop(Deadline.of(ENGINE_STOP_DEADLINE));
        assertThat(Duration.ofNanos(System.nanoTime() - stoppingSinceNanos))
                .as("the %s engine did not stop within %s", connectorType, ENGINE_STOP_DEADLINE)
                .isLessThan(ENGINE_STOP_DEADLINE);
    }

    /**
     * Forgets the interactions recorded so far on both applications and on their message decoders, so that a test can
     * count what happens after a given point without counting what led up to it.
     * <p>
     * Deliberately not a {@code reset(...)}: that drops the stubbing {@link #setup()} installed along with the
     * recorded calls, leaving {@link FixApplication#validateLogon} returning {@code null},
     * {@link FixApplication#getFixApiVersion()} returning {@code null} and {@link FixApplication#setup} returning no
     * decoder at all. Nothing fails at that point, but the next logon the test drives silently misbehaves - the
     * acceptor stops acknowledging it - which is exactly the trap the resend tests were caught in.
     */
    void clearApplicationsInvocations() {
        clearInvocations(fixInitiatorApplication, fixAcceptorApplication);
        initiatorMessageDecoders.values().forEach(Mockito::clearInvocations);
        acceptorMessageDecoders.values().forEach(Mockito::clearInvocations);
    }

    void assertNoMessageReceived(List<TestingDecodedFixMessage> target, FixField stringField, String expectedFieldValue) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        await().untilAsserted(() -> Assertions.assertThat(target)
                .noneMatch(testingDecodedFixMessage ->
                        testingDecodedFixMessage.getString(stringField, "").equals(expectedFieldValue)));

    }

    void assertMessageReceived(List<TestingDecodedFixMessage> target, FixField stringField, String expectedFieldValue) {
        await().untilAsserted(() -> Assertions.assertThat(target)
                .anyMatch(testingDecodedFixMessage -> !testingDecodedFixMessage.isPossDuplicate()
                        && testingDecodedFixMessage.getString(stringField, "").equals(expectedFieldValue)));

    }

    void assertPossDupMessageReceived(List<TestingDecodedFixMessage> target, FixField stringField, String expectedFieldValue) {
        await().untilAsserted(() -> Assertions.assertThat(target)
                .anyMatch(testingDecodedFixMessage -> testingDecodedFixMessage.isPossDuplicate()
                        && testingDecodedFixMessage.getString(stringField, "").equals(expectedFieldValue)));
    }

    /**
     * The session behind a {@link FixSession} reference, for the engine side operations - the admin API above all -
     * that the public interface deliberately does not carry.
     */
    FixSessionImpl getFixSessionImpl(ConnectorType connectorType) {
        return (FixSessionImpl) getFixSession(connectorType);
    }

    ArgumentMatcher<DecodedFixMessage> messageFieldReceived(int fixFieldCode, String value) {
        return new ArgumentMatcher<>() {

            @Override
            public boolean matches(DecodedFixMessage decodedFixMessage) {
                return decodedFixMessage.getValue(fixFieldCode, StringSerde.instance(), "null").equals(value);
            }

            @Override
            public String toString() {
                return "FIX Message contains field " + fixFieldCode + "=" + value;
            }
        };
    }

    void setupMessageDecoders(ConnectorType connectorType) {
        FixApplication targetApp = connectorType.select(fixInitiatorApplication, fixAcceptorApplication);
        Map<MessageType, FixMessageDecoder> targetMap = connectorType.select(initiatorMessageDecoders, acceptorMessageDecoders);
        for (MessageType mt : getDecoders(connectorType)) {
            List<TestingDecodedFixMessage> targetList = connectorType.select(decodedInitiatorMessages, decodedAcceptorMessages);
            targetMap.put(mt, spy(new TestDecodedFixMessageDecoder(mt, targetList::add)));
        }
        when(targetApp.setup(any(), any(), any())).thenReturn(new ArrayList<>(targetMap.values()));
    }

    List<MessageType> getDecoders(ConnectorType connectorType) {
        return List.of(testMessageType);
    }

    FixMessageDecoder getDecoder(MessageType mt, ConnectorType connectorType) {
        return connectorType.select(initiatorMessageDecoders, acceptorMessageDecoders).get(mt);
    }

    List<TestingDecodedFixMessage> getDecodedFixMessages(ConnectorType connectorType) {
        return connectorType.select(decodedInitiatorMessages, decodedAcceptorMessages);
    }

    TestingFixSessionMessagesStore getFixMessagesStore(ConnectorType connectorType) {
        return connectorType.select(initiatorMessagesStore, acceptorMessagesStore);
    }

    FixApplication getFixApplication(ConnectorType connectorType) {
        return connectorType.select(fixInitiatorApplication, fixAcceptorApplication);
    }

    TestingLogger getLogger(ConnectorType connectorType) {
        return connectorType.select(initiatorLogger, acceptorLogger);
    }

    FixSession getFixSession(ConnectorType connectorType) {
        return connectorType.select(fixInitiatorSession, fixAcceptorSession);
    }

    Startable<?> getConnector(ConnectorType connectorType) {
        return connectorType.select(fixInitiator, fixAcceptor);
    }

    FixSessionSettings.FixSessionSettingsBuilder<?, ?> getAcceptorFixSessionSettings() {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of("test", FixRegularVersion.VERSION_44, "TARGET44_TEST", "SENDER44_TEST"))
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                // null, as FixSessionSettings itself defaults to: the counterparty manages ResetSeqNumFlag(141)
                // and this session follows it. An explicit false means "resetting is not supported" and is answered
                // with a Logout, which is a deliberate choice a test should make rather than inherit.
                .resetSeqNumOnLogon(null)
                .desiredSessionState(FixSessionState.LOGGED_IN);
    }

    FixSessionSettings.FixSessionSettingsBuilder<?, ?> getInitiatorFixSessionSettings() {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER44_TEST", "TARGET44_TEST"))
                .fixSessionType(FixSession.FixSessionType.INITIATOR)
                .heartBeatInterval(FixSessionSettings.HeartbeatInterval.builder().initiatorInterval(Duration.ofSeconds(5)).build())
                // null, as FixSessionSettings itself defaults to: the counterparty manages ResetSeqNumFlag(141)
                // and this session follows it. An explicit false means "resetting is not supported" and is answered
                // with a Logout, which is a deliberate choice a test should make rather than inherit.
                .resetSeqNumOnLogon(null)
                .desiredSessionState(FixSessionState.LOGGED_OUT);
    }

    void connectFixInitiatorAndAcceptor() {
        startFixInitiatorAndAcceptor();
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isTrue());
    }

    /**
     * Brings both ends up without waiting for the connection to be established, for the tests where it is not going
     * to be: an initiator is asked before every dialling attempt whether it wants a connection at all, and one whose
     * session schedule is currently closed - or whose desired state is DISCONNECTED - answers no until that changes.
     */
    void startFixInitiatorAndAcceptor() {
        startFixAcceptor();
        if (fixInitiatorSession == null || !fixInitiatorSession.isConnected()) {
            fixInitiator.start();
            trapCreatedFixSession(ConnectorType.INITIATOR);
        }
    }

    void startFixAcceptor() {
        if (!fixAcceptor.isStarted()) {
            fixAcceptor.start();
            trapCreatedFixSession(ConnectorType.ACCEPTOR);
        }
    }

    void logonClient() {
        connectFixInitiatorAndAcceptor();

        fixInitiatorSession.logon();

        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
    }

    /**
     * Ends the initiator's dialling on its first rejection, so that a rejected-logon test sees exactly one attempt.
     * Call it once the connection is up and before the logon it is about to reject.
     * <p>
     * The initiator's desired state stays LOGGED_IN once {@link FixSession#logon()} is called, and this class dials
     * with a 100ms connection retry, so a rejected session re-dials and is rejected again for as long as the test
     * leaves it running - the callback counts climb the whole time. Verifying them would then have to settle for
     * {@code atLeastOnce()}, which no longer notices a reject path firing its callbacks twice for a single attempt.
     * {@link FixSession#disconnect(String)} moves the desired state to DISCONNECTED, and that is what an initiator is
     * asked before every dialling attempt, so the rejection that just happened stays the only one and the counts can
     * be pinned exactly.
     */
    void rejectedLogonEndsDialling() {
        doAnswer(invocation -> {
            // the session the callback carries, rather than the field: the callback can land before the field that
            // traps it has been assigned, and reading it here would end the dialling only sometimes
            invocation.getArgument(0, FixSession.class).disconnect("test: a single logon attempt");
            return null;
        }).when(fixInitiatorApplication).onLogout(any(), any(), any());
    }

    /**
     * Verifies that the initiator's application was told about the rejection exactly once, and keeps verifying it for
     * {@link #REJECTION_SETTLE_TIME} afterwards. Holding the assertion is what makes it a regression trap rather than
     * a sample: a second callback for the same attempt, or a dialling attempt {@link #rejectedLogonEndsDialling()}
     * should have prevented, fails the test instead of slipping through between two polls.
     */
    void awaitSingleRejectedLogon(String logoutReason) {
        await().during(REJECTION_SETTLE_TIME).untilAsserted(() -> {
            verify(fixInitiatorApplication).onLogout(any(), Mockito.eq(logoutReason), any());
            verify(fixInitiatorApplication).onDisconnected(any());
        });
    }

    void trapCreatedFixSession(ConnectorType connectorType) {
        ArgumentCaptor<FixSession> fixSessionCaptor = ArgumentCaptor.forClass(FixSession.class);
        await().untilAsserted(() ->
                verify(getFixApplication(connectorType)).onSessionCreated(fixSessionCaptor.capture(), any(), any(), any()));
        if (connectorType.equals(ConnectorType.INITIATOR)) {
            fixInitiatorSession = fixSessionCaptor.getValue();
        } else {
            fixAcceptorSession = fixSessionCaptor.getValue();
        }
    }

    FixMessageEncoder<?> encodeTestMessage() {
        return encodeTestMessage(0);
    }

    FixMessageEncoder<?> encodeTestMessage(int index) {
        EmailEncoder emailEncoder = FixMessageEncoderFactory.Registry.find(EmailEncoder.class).newInstance(EmailEncoder.class, null, null, null, null);
        emailEncoder.begin().setEmailType(EmailType.EmailTypeValues.NEW).setEmailThreadID("test thread id " + index).setSubject("test subject " + index);
        NoLinesOfTextEncoder lotEncoder = emailEncoder.addNoLinesOfText(2);
        for (int j = 0; j < 2; j++) {
            lotEncoder.setText("test text");
            lotEncoder.setEncodedTextLen("encoded text".length());
            lotEncoder.setEncodedText("encoded text");
        }
        return emailEncoder;
    }

    enum ConnectorType {
        INITIATOR {
            @Override
            <T> T select(T initiator, T acceptor) {
                return initiator;
            }

            @Override
            <T> T select(Supplier<T> initiator, Supplier<T> acceptor) {
                return initiator.get();
            }

            @Override
            ConnectorType inverse() {
                return ConnectorType.ACCEPTOR;
            }
        },

        ACCEPTOR {
            @Override
            <T> T select(T initiator, T acceptor) {
                return acceptor;
            }

            @Override
            <T> T select(Supplier<T> initiator, Supplier<T> acceptor) {
                return acceptor.get();
            }

            @Override
            ConnectorType inverse() {
                return ConnectorType.INITIATOR;
            }
        };

        abstract <T> T select(T initiator, T acceptor);

        abstract <T> T select(Supplier<T> initiator, Supplier<T> acceptor);

        abstract ConnectorType inverse();

    }

    static class TestDecodedFixMessageDecoder extends DecodedFixMessageDecoder {

        private final Consumer<TestingDecodedFixMessage> decodedMessagesConsumer;

        public TestDecodedFixMessageDecoder(MessageType messageType, Consumer<TestingDecodedFixMessage> decodedMessagesConsumer) {
            super(messageType);
            this.decodedMessagesConsumer = decodedMessagesConsumer;
        }

        @Override
        public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
            DecodedFixMessageImpl decodedFixMessage = (DecodedFixMessageImpl) super.getDecodedFixMessage();
            decodedMessagesConsumer.accept(new TestingDecodedFixMessage(decodedFixMessage.getFieldsValues(), decodedFixMessage.getMessageType(), possDupFlag, possResend));
            super.onDecoded(fixSession, possDupFlag, possResend);
        }
    }

    public static class TestingDecodedFixMessage extends FieldMapImpl implements DecodedFixMessage {
        private final MessageType messageType;
        @Getter
        private final boolean possDuplicate;
        @Getter
        private final boolean possResend;

        public TestingDecodedFixMessage(Map<FixField, Object> fieldsValues, MessageType messageType, boolean possDuplicate, boolean possResend) {
            super(fieldsValues);
            this.messageType = messageType;
            this.possDuplicate = possDuplicate;
            this.possResend = possResend;
        }

        @Override
        public MessageType getMessageType() {
            return messageType;
        }

        @Override
        public String toString() {
            StringBuilder toString = new StringBuilder(256);
            foreach((field, value) ->
                    toString.append(field.getCode()).append("=").append(new String(value)).append(CoreFields.FIELD_SEPARATOR));
            return toString.toString();
        }

        @Override
        public DecodedFixMessage copy() {
            return new DecodedFixMessageImpl(messageType, getFieldsValues());
        }
    }
}