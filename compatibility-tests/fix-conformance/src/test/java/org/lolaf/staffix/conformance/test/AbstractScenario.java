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
package org.lolaf.staffix.conformance.test;

import lombok.SneakyThrows;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.*;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.fix44.fields.EncryptMethod;
import org.lolaf.staffix.fix44.fields.HeartBtInt;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.lolaf.staffix.tests.*;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;
import static org.mockito.Mockito.*;

/**
 * See https://www.fixtrading.org/standards/fix-session-testcases-online
 */
abstract class AbstractScenario {

    /**
     * How long the tests wait for a message to arrive before giving up.
     */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private static final Duration DEFAULT_AWAIT_TIMEOUT = Duration.ofSeconds(30);
    /**
     * The port the acceptor under test binds, and the initiator connects to. Picked free for every test so that
     * scenarios can run in parallel, and so that a stray process holding a fixed port cannot fail the whole suite.
     */
    int acceptorPort;
    FixInitiator fixInitiator;
    FixAcceptor fixAcceptor;
    FixApplication fixInitiatorApplication;
    FixApplication fixAcceptorApplication;
    FixSession fixInitiatorSession;
    FixSession fixAcceptorSession;
    FixInitiatorBuilder fixInitiatorBuilder;
    FixAcceptorBuilder fixAcceptorBuilder;
    FixAcceptor.FixSessionEventsListener fixSessionEventsListener;
    TestingLogger acceptorLogger;
    TestingLogger initiatorLogger;
    TestingFixSessionMessagesStore initiatorMessageStore;
    TestingFixSessionMessagesStore acceptorMessageStore;
    FixEngineBuilder fixInitiatorEngineBuilder;
    FixEngine fixInitiatorEngine;
    FixEngineBuilder fixAcceptorEngineBuilder;
    FixEngine fixAcceptorEngine;

    @BeforeAll
    static void raiseAwaitilityTimeout() {
        Awaitility.setDefaultTimeout(DEFAULT_AWAIT_TIMEOUT);
    }

    @SneakyThrows
    @BeforeEach
    void setup() {
        acceptorPort = TestPorts.findFree();

        initiatorMessageStore = new TestingFixSessionMessagesStore();
        initiatorLogger = spy(new TestingLogger("CLI"));

        fixInitiatorApplication = mock(FixApplication.class);
        when(fixInitiatorApplication.getFixApiVersion()).thenReturn(FixApiVersion.of(FixRegularVersion.VERSION_44));
        when(fixInitiatorApplication.validateLogon(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        doNothing().when(fixInitiatorApplication)
                .onSessionCreated(assertArg((Consumer<FixSession>) fixSession -> fixInitiatorSession = fixSession), any(), any(), any());

        fixInitiatorEngineBuilder = FixEngineBuilder.builder()
                .fixMessagesStore(TestingFixMessagesStoreSettings.builder()
                        .testingFixSessionMessagesStore(initiatorMessageStore)
                        .build())
                .fixMessagesLogger(TestingFixMessagesLoggerSettings.builder()
                        .testingLogger(initiatorLogger)
                        .build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(getInitiatorFixSessionSettings().build()).build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .application(InstanceProvider.DEFAULT_INSTANCE_ID, fixInitiatorApplication).build())
                .build();
        fixInitiatorEngine = fixInitiatorEngineBuilder.instance();
        fixInitiatorEngine.start();

        fixInitiatorBuilder = FixInitiatorBuilder.builder()
                .fixSessionId(getInitiatorFixSessionSettings().build().getFixSessionId())
                .connectionRetry(Duration.ofSeconds(1))
                .connectAddress(new InetSocketAddress("localhost", acceptorPort))
                .build();
        fixInitiator = fixInitiatorEngine.newInitiator(fixInitiatorBuilder);

        acceptorMessageStore = new TestingFixSessionMessagesStore();
        acceptorLogger = spy(new TestingLogger("SRV"));

        fixSessionEventsListener = mock(FixAcceptor.FixSessionEventsListener.class);
        fixAcceptorApplication = mock(FixApplication.class);
        when(fixAcceptorApplication.getFixApiVersion()).thenReturn(FixApiVersion.of(FixRegularVersion.VERSION_44));
        when(fixAcceptorApplication.validateLogon(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        doNothing().when(fixAcceptorApplication)
                .onSessionCreated(assertArg((Consumer<FixSession>) fixSession -> fixAcceptorSession = fixSession), any(), any(), any());
        when(fixAcceptorApplication.setup(any(), any(), any())).thenReturn(acceptorApplicationDecoders());

        fixAcceptorEngineBuilder = FixEngineBuilder.builder()
                .fixMessagesStore(TestingFixMessagesStoreSettings.builder()
                        .testingFixSessionMessagesStore(acceptorMessageStore)
                        .build())
                .fixMessagesLogger(TestingFixMessagesLoggerSettings.builder()
                        .testingLogger(acceptorLogger)
                        .build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .application(InstanceProvider.DEFAULT_INSTANCE_ID, fixAcceptorApplication).build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(getAcceptorFixSessionSettings().build()).build())
                .build();

        fixAcceptorEngine = fixAcceptorEngineBuilder.instance();
        fixAcceptorEngine.start();

        fixAcceptorBuilder = FixAcceptorBuilder.builder()
                .bindAddress(new InetSocketAddress("localhost", acceptorPort))
                .fixSessionEventsListener(fixSessionEventsListener)
                .build();
        fixAcceptor = fixAcceptorEngine.newAcceptor(fixAcceptorBuilder);
    }

    @AfterEach
    void shutdown() {
        if (fixInitiatorSession != null && fixInitiatorSession.isLoggedIn()) {
            fixInitiatorSession.logoutPermanently("finished test");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1000));
        }
        fixInitiatorEngine.stop(Deadline.unlimited());
        fixAcceptorEngine.stop(Deadline.unlimited());
    }

    /**
     * Opens a raw FIX connection to the acceptor under test, acting as the initiator, without logging on. For the
     * tests driving what the very first message of a connection is; prefer {@link #rawInitiatorLogon()} otherwise.
     * The caller owns the returned session and must close it.
     */
    RawFixSocketClient.Session rawInitiatorConnect() throws Exception {
        return rawInitiatorConnect(DEFAULT_TIMEOUT);
    }

    /**
     * Same as {@link #rawInitiatorConnect()} with an explicit timeout.
     */
    RawFixSocketClient.Session rawInitiatorConnect(Duration timeout) throws Exception {
        FixSessionId initiator = getInitiatorFixSessionSettings().build().getFixSessionId();
        return RawFixSocketClient.connect(acceptorPort, initiator.getFixVersion(),
                initiator.getSenderCompID().getValue(), initiator.getTargetCompID().getValue(), timeout);
    }

    /**
     * Opens a raw FIX connection to the acceptor under test, acting as the initiator: logs on as MsgSeqNum 1 and
     * waits for the Logon reply, so the acceptor then expects the next inbound message to be MsgSeqNum 2. The
     * connection is pinned to the FIX version and the comp ids of the initiator session settings, so that the
     * messages it builds are the ones the acceptor expects. The caller owns the returned session and must close it.
     */
    RawFixSocketClient.Session rawInitiatorLogon() throws Exception {
        return rawInitiatorLogon(DEFAULT_TIMEOUT);
    }

    /**
     * Same as {@link #rawInitiatorLogon()} with an explicit timeout.
     */
    RawFixSocketClient.Session rawInitiatorLogon(Duration timeout) throws Exception {
        RawFixSocketClient.Session session = rawInitiatorConnect(timeout);
        session.send(session.message(MessageTypes.Logon, 1).set(EncryptMethod.get(), "0").set(HeartBtInt.get(), "5"));
        assertThatFixMessage(session.readMessage(timeout)).hasMsgType(MessageTypes.Logon);
        return session;
    }

    /**
     * Starts both connectors, waits for the initiator to connect, drives a Logon and waits until both sides are
     * logged in. The initiator/acceptor sessions are trapped by the {@code onSessionCreated} stubs configured in
     * {@link #setup()}.
     */
    void logonClient() {
        fixAcceptor.start();
        fixInitiator.start();

        await().untilAsserted(() -> assertThat(fixInitiatorSession).isNotNull());
        await().untilAsserted(() -> assertThat(fixInitiatorSession.isConnected()).isTrue());

        fixInitiatorSession.logon();

        await().untilAsserted(() -> verify(fixInitiatorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
        await().untilAsserted(() -> verify(fixAcceptorApplication).onLogon(any(FixSession.class), any(DecodedFixMessage.class)));
    }

    /**
     * The application message decoders the acceptor under test registers through
     * {@link FixApplication#setup(FixSessionSettings, FixSession, java.util.Set)}. Empty for the scenarios that only
     * exercise the session layer, where the engine decodes the messages itself; a scenario needing to be sent an
     * application message has to declare a decoder for it here, otherwise the acceptor answers
     * {@code onNoDecoderSetupForMessage}.
     */
    List<FixMessageDecoder> acceptorApplicationDecoders() {
        return List.of();
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
}
