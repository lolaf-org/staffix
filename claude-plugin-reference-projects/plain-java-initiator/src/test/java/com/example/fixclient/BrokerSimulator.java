package com.example.fixclient;

import com.example.fixclient.fix.encoders.ExecutionReportEncoder;
import com.example.fixclient.fix.fields.ClOrdID;
import com.example.fixclient.fix.fields.ExecType;
import com.example.fixclient.fix.fields.OrdStatus;
import com.example.fixclient.fix.fields.Side;
import com.example.fixclient.fix.fields.Symbol;
import com.example.fixclient.fix.msg.MessageTypes;
import lombok.Setter;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.FixAcceptorBuilder;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.stores.loggers.slf4j.Slf4jMessagesLoggerSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The broker side of the order entry session, in process: fills every NewOrderSingle at once.
 */
final class BrokerSimulator {

    private final FixEngine engine;
    private final FixAcceptor acceptor;

    BrokerSimulator(InetSocketAddress bindAddress) {
        FixSessionId sessionId = FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                .name("broker-order-entry")
                .senderCompID("BROKER")
                .targetCompID("CLIENT")
                .build());
        engine = FixEngineBuilder.builder()
                .instanceId("broker-simulator")
                .fixMessagesStore(MemoryMessageStoreSettings.builder().build())
                .fixMessagesLogger(Slf4jMessagesLoggerSettings.builder().build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .application("broker", new BrokerApplication())
                        .build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(FixSessionSettings.builder()
                                .fixSessionId(sessionId)
                                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                                .fixApplicationInstanceId("broker")
                                .build())
                        .build())
                .build().instance().start();
        acceptor = engine.newAcceptor(FixAcceptorBuilder.builder().bindAddress(bindAddress).build()).start();
    }

    void stop() {
        acceptor.stop();
        engine.stop(Deadline.of(Duration.ofSeconds(5)));
    }

    private static final class BrokerApplication implements FixApplication {

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
            encodedMessagesTypes.add(MessageTypes.ExecutionReport);
            return List.of(new NewOrderSingleDecoder(session));
        }
    }

    @Setter
    private static final class NewOrderSingleDecoder implements FixMessageDecoder {

        private final ExecutionReportEncoder executionReportEncoder;
        private String clOrdId;
        private String symbol;
        private Side.SideValues side;
        private long orderCount;

        NewOrderSingleDecoder(FixSession session) {
            executionReportEncoder = session.newEncoder(ExecutionReportEncoder.class).asReusable();
        }

        @Override
        public MessageType getMessageType() {
            return MessageTypes.NewOrderSingle;
        }

        @Override
        public void mapFieldsForDecoding(FixFieldsDecoderMapper mapper, FieldsRegistry fieldsRegistry) {
            mapper.mapStringField(ClOrdID.get(), this::setClOrdId, null)
                    .mapStringField(Symbol.get(), this::setSymbol, null)
                    .mapCharValuesEnumField(Side.get(), this::setSide, null);
        }

        @Override
        public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
            orderCount++;
            session.send(executionReportEncoder.begin()
                    .setOrderID("order-" + orderCount)
                    .setExecID("exec-" + orderCount)
                    .setClOrdID(clOrdId)
                    .setExecType(ExecType.ExecTypeValues.NEW)
                    .setOrdStatus(OrdStatus.OrdStatusValues.NEW)
                    .setSymbol(symbol)
                    .setSide(side)
                    .setLeavesQty(0d)
                    .setCumQty(0d)
                    .setAvgPx(0d), null);
        }
    }
}
