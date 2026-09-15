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
package org.lolaf.staffix.benchmarks.impl;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.betty.api.io.IOWorkersGroup;
import org.lolaf.betty.api.settings.IOWorkersGroupSettings;
import org.lolaf.betty.api.ss.IdleStrategySelectStrategy;
import org.lolaf.betty.api.ss.SelectStrategy;
import org.lolaf.betty.api.ss.WakeupSelectStrategy;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BusySpinIdleStrategy;
import org.lolaf.staffix.api.*;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionState;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.benchmarks.fix44.encoders.QuoteEncoder;
import org.lolaf.staffix.benchmarks.fix44.encoders.QuoteRequestEncoder;
import org.lolaf.staffix.benchmarks.fix44.fields.*;
import org.lolaf.staffix.benchmarks.fix44.msg.MessageTypes;
import org.lolaf.staffix.stores.loggers.slf4j.Slf4jMessagesLoggerSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

@Slf4j
@State(Scope.Benchmark)
public class StaffixEngine extends AbstractBenchmark {

    private static final String ACCEPTOR = "acceptor";
    private static final String INITIATOR = "initiator";
    FixAcceptor fixAcceptor;
    FixInitiator fixInitiator;
    FixEngine engine;
    InitiatorApplication initiatorApplication;
    AcceptorApplication acceptorApplication;

    private static FixSessionId getInitiatorFixSessionId() {
        return FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER44_TEST", "TARGET44_TEST");
    }

    private IOWorkersGroup getIOWorkersGroup(FixEngineSettings fixEngineSettings) {
        SelectStrategy ss;
        switch (fixEngineSettings) {
            case STOCK:
                ss = new WakeupSelectStrategy(1);
                break;
            case LOW_LATENCY:
                ss = new IdleStrategySelectStrategy(BusySpinIdleStrategy.getInstance());
                break;
            default:
                throw new IllegalStateException("Implement me");
        }
        return IOWorkersGroupSettings.builder()
                .ioThreadGroup(IOWorkersGroupSettings.IOThreadGroup.builder()
                        .selectStrategy(ss)
                        .build()).build().newInstance();
    }

    @Override
    void fixEngineSetup(FixEngineSettings fixEngineSettings) throws Exception {
        acceptorApplication = new AcceptorApplication();
        initiatorApplication = new InitiatorApplication();
        engine = FixEngineBuilder.builder()
                .fixMessagesStore(MemoryMessageStoreSettings.builder().maxEntriesInMemory(0).build())
                .fixMessagesLogger(Slf4jMessagesLoggerSettings.builder().logIncoming(false).logOutgoing(false).build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .instanceId(ACCEPTOR)
                        .application(ACCEPTOR, acceptorApplication).build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .instanceId(INITIATOR)
                        .application(INITIATOR, initiatorApplication).build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .instanceId(ACCEPTOR)
                        .fixSessionSetting(getAcceptorFixSessionSettings()).build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .instanceId(INITIATOR)
                        .fixSessionSetting(getInitiatorFixSessionSettings()).build())
                .build().instance();
        engine.start();
    }

    @Override
    void setupAcceptor(FixEngineSettings fixEngineSettings) throws Exception {
        FixAcceptorBuilder fixAcceptorBuilder = FixAcceptorBuilder.builder()
                .ioWorkersGroup(getIOWorkersGroup(fixEngineSettings))
                .bindAddress(new InetSocketAddress("localhost", 7001))
                .targetFixSessionsSettingsStoreInstancesIds(List.of(ACCEPTOR))
                .build();
        fixAcceptor = engine.newAcceptor(fixAcceptorBuilder);
        super.setupAcceptor(fixEngineSettings);
    }

    @Override
    void setupInitiator(FixEngineSettings fixEngineSettings) throws Exception {
        FixInitiatorBuilder fixInitiatorBuilder = FixInitiatorBuilder.builder()
                .ioWorkersGroup(getIOWorkersGroup(fixEngineSettings))
                .connectAddress(new InetSocketAddress("localhost", 7001))
                .fixSessionId(getInitiatorFixSessionId())
                .build();
        fixInitiator = engine.newInitiator(fixInitiatorBuilder);
        super.setupInitiator(fixEngineSettings);
    }

    private FixSessionSettings getAcceptorFixSessionSettings() {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of("test", FixRegularVersion.VERSION_44, "TARGET44_TEST", "SENDER44_TEST"))
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .dictionaryId("benchmarks")
                .resetSeqNumOnLogon(false)
                .fixApplicationFactoryInstanceId(ACCEPTOR)
                .fixApplicationInstanceId(ACCEPTOR)
                .desiredSessionState(FixSessionState.LOGGED_IN)
                .build();
    }

    private FixSessionSettings getInitiatorFixSessionSettings() {
        return FixSessionSettings.builder()
                .fixSessionId(getInitiatorFixSessionId())
                .fixSessionType(FixSession.FixSessionType.INITIATOR)
                .dictionaryId("benchmarks")
                .heartBeatInterval(FixSessionSettings.HeartbeatInterval.builder().initiatorInterval(Duration.ofSeconds(1)).build())
                .resetSeqNumOnLogon(false)
                .fixApplicationFactoryInstanceId(INITIATOR)
                .fixApplicationInstanceId(INITIATOR)
                .desiredSessionState(FixSessionState.LOGGED_IN)
                .build();
    }

    @Override
    void startAndWaitForConnection() {
        fixAcceptor.start();
        fixInitiator.start();
        while (!initiatorApplication.connected.get()) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }
    }

    @Override
    void shutdownEngine() throws Exception {
        fixInitiator.stop(Deadline.unlimited());
        fixAcceptor.stop(Deadline.unlimited());
        while (initiatorApplication.connected.get()) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }
        engine.stop(Deadline.unlimited());
        super.shutdownEngine();
    }


    @Override
    public void sendMessageAndWaitForResponse() {
        initiatorApplication.sendMessage();
        while (!initiatorApplication.receivedResponse.get()) {
            Thread.onSpinWait();
        }
    }

    private static class InitiatorApplication implements FixApplication {

        AtomicBoolean connected = new AtomicBoolean();
        AtomicBoolean receivedResponse = new AtomicBoolean();
        FixSession fixSession;
        QuoteRequestEncoder quoteRequestEncoder;

        public void sendMessage() {
            quoteRequestEncoder.begin()
                    .setQuoteReqID("testQuoteRequest")
                    .addNoRelatedSym(1).setSymbol("FOO/BAR");
            receivedResponse.set(false);
            fixSession.send(quoteRequestEncoder, null);
        }

        @Override
        public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
            this.fixSession = fixSession;
            quoteRequestEncoder = fixSession.newEncoder(QuoteRequestEncoder.class).asReusable();
            connected.set(true);
        }

        @Override
        public void onLogout(FixSession fixSession, String message, DecodedFixMessage logoutMessage) {
            connected.set(false);
        }

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
            return List.of(new QuoteDecoder(receivedResponse));
        }

        @Setter
        private static class QuoteDecoder implements FixMessageDecoder {

            private final AtomicBoolean receivedMessage;

            private String symbol;
            private String quoteReqId;
            private String quoteId;
            private Side.SideValues side;
            private double bidPx;
            private double bidSize;

            protected QuoteDecoder(AtomicBoolean receivedMessage) {
                this.receivedMessage = receivedMessage;
            }

            @Override
            public MessageType getMessageType() {
                return MessageTypes.Quote;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
                fixFieldsDecoderMapper.mapStringField(QuoteReqID.get(), this::setQuoteReqId, null, FixFieldsDecoderMapper.ObjectInstanceStrategy.THREAD_LOCAL)
                        .mapStringField(QuoteID.get(), this::setQuoteId, null, FixFieldsDecoderMapper.ObjectInstanceStrategy.THREAD_LOCAL)
                        .mapStringField(Symbol.get(), this::setSymbol, null, FixFieldsDecoderMapper.ObjectInstanceStrategy.CACHED)
                        .mapCharValuesEnumField(Side.get(), this::setSide, null)
                        .mapDoubleField(BidPx.get(), this::setBidPx, 0)
                        .mapDoubleField(BidSize.get(), this::setBidSize, 0);
            }

            @Override
            public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
                receivedMessage.set(true);
            }
        }
    }

    private static class AcceptorApplication implements FixApplication {

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
            return List.of(new QuoteRequestDecoder(fixSession));
        }

        @Setter
        private static class QuoteRequestDecoder implements FixMessageDecoder {

            private final QuoteEncoder quoteEncoder;

            private String symbol;
            private String quoteReqId;

            protected QuoteRequestDecoder(FixSession fixSession) {
                this.quoteEncoder = fixSession.newEncoder(QuoteEncoder.class).asReusable();
            }

            @Override
            public MessageType getMessageType() {
                return MessageTypes.QuoteRequest;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
                fixFieldsDecoderMapper.mapStringField(QuoteReqID.get(), this::setQuoteReqId, null, FixFieldsDecoderMapper.ObjectInstanceStrategy.THREAD_LOCAL)
                        .forGroup(NoRelatedSym.get())
                        .withFieldsAutoResetDisabled()
                        .mapStringField(Symbol.get(), this::setSymbol, null, FixFieldsDecoderMapper.ObjectInstanceStrategy.CACHED);
            }

            @Override
            public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
                quoteEncoder.begin()
                        .setQuoteReqID(quoteReqId)
                        .setQuoteID("testQuoteId")
                        .setSymbol(symbol)
                        .setSide(Side.SideValues.BUY)
                        .setBidPx(1234567.123456789d)
                        .setBidSize(1234567d);
                fixSession.send(quoteEncoder, null);
            }
        }
    }
}