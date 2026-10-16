package com.example.gateway;

import com.example.gateway.fix.encoders.NewOrderSingleEncoder;
import com.example.gateway.fix.fields.ClOrdID;
import com.example.gateway.fix.fields.OrdStatus;
import com.example.gateway.fix.fields.OrdType;
import com.example.gateway.fix.fields.Side;
import com.example.gateway.fix.msg.MessageTypes;
import lombok.Setter;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.api.FixInitiatorBuilder;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.stores.loggers.slf4j.Slf4jMessagesLoggerSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Counterparty CLIENT_A, in process. Its session has its own name: session ids are interned for the whole JVM, and
 * staffix 0.9.1 mixes up the CompIDs of two sessions sharing a name there.
 */
final class ClientSimulator {

    private final FixEngine engine;
    private final FixInitiator initiator;
    private final ClientApplication application = new ClientApplication();

    ClientSimulator(InetSocketAddress gatewayAddress) {
        FixSessionId sessionId = FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                .name("client-a-simulator")
                .senderCompID("CLIENT_A")
                .targetCompID("BROKER")
                .build());
        engine = FixEngineBuilder.builder()
                .instanceId("client-simulator")
                .fixMessagesStore(MemoryMessageStoreSettings.builder().build())
                .fixMessagesLogger(Slf4jMessagesLoggerSettings.builder().build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .application("client", application)
                        .build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(FixSessionSettings.builder()
                                .fixSessionId(sessionId)
                                .fixSessionType(FixSession.FixSessionType.INITIATOR)
                                .fixApplicationInstanceId("client")
                                .resetSeqNumOnLogon(true)
                                .build())
                        .build())
                .build().instance().start();
        initiator = engine.newInitiator(FixInitiatorBuilder.builder()
                .connectionRetry(Duration.ofMillis(200))
                .mainTarget(FixInitiatorTarget.builder()
                        .fixSessionId(sessionId)
                        .connectAddress(gatewayAddress)
                        .build())
                .build()).start();
    }

    boolean isLoggedOn() {
        return application.session != null;
    }

    void sendNewOrderSingle(String clOrdId) {
        NewOrderSingleEncoder order = application.session.newEncoder(NewOrderSingleEncoder.class);
        application.session.send(order.begin()
                .setClOrdID(clOrdId)
                .setSymbol("EUR/USD")
                .setSide(Side.SideValues.BUY)
                .setOrderQty(1_000_000d)
                .setOrdType(OrdType.OrdTypeValues.LIMIT)
                .setPrice(1.0825d)
                .setTransactTime(UTCTime.of(Instant.now()), TimeUnit.MILLISECONDS), null);
    }

    List<String> executionReports() {
        return application.executionReports;
    }

    void stop() {
        initiator.stop();
        engine.stop(Deadline.of(Duration.ofSeconds(5)));
    }

    private static final class ClientApplication implements FixApplication {

        private final List<String> executionReports = new CopyOnWriteArrayList<>();
        private volatile FixSession session;

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public FixDictionaryId getDictionaryId() {
            return FixDictionaryId.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings settings, FixSession session,
                                             Set<MessageType> encodedMessagesTypes) {
            encodedMessagesTypes.add(MessageTypes.NewOrderSingle);
            return List.of(new ExecutionReportDecoder(executionReports));
        }

        @Override
        public void onLogon(FixSession session, DecodedFixMessage logon) {
            this.session = session;
        }
    }

    @Setter
    private static final class ExecutionReportDecoder implements FixMessageDecoder {

        private final List<String> executionReports;
        private String clOrdId;
        private char ordStatus;

        ExecutionReportDecoder(List<String> executionReports) {
            this.executionReports = executionReports;
        }

        @Override
        public MessageType getMessageType() {
            return MessageTypes.ExecutionReport;
        }

        @Override
        public void mapFieldsForDecoding(FixFieldsDecoderMapper mapper, FieldsRegistry fieldsRegistry) {
            mapper.mapStringField(ClOrdID.get(), this::setClOrdId, null)
                    .mapCharField(OrdStatus.get(), this::setOrdStatus, (char) 0);
        }

        @Override
        public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
            executionReports.add(clOrdId + ":" + ordStatus);
        }
    }
}
