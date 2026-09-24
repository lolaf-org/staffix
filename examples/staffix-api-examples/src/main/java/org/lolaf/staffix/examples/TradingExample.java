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
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.timer.MutableTimeout;
import org.lolaf.ringos.timer.Timeout;
import org.lolaf.ringos.timer.WheelTimer;
import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.ids.UUIDsGenerator;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.serde.DecimalFloat;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.fix44.encoders.ExecutionReportEncoder;
import org.lolaf.staffix.fix44.encoders.NewOrderSingleEncoder;
import org.lolaf.staffix.fix44.fields.*;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import picocli.CommandLine;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Run with -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints --enable-native-access=ALL-UNNAMED --add-opens java.base/jdk.internal.ref=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED
 */
@Slf4j
@CommandLine.Command(name = "TradingExample", mixinStandardHelpOptions = true, version = "1.0",
        description = "NewOrderSingle/ExecutionReport trading example", showDefaultValues = true)
public class TradingExample extends FixExamplesBase implements Callable<Integer> {

    @CommandLine.Option(names = "-w", description = "Wait delay in micros to respond to a trade request", defaultValue = "0")
    private long waitDelayInMicros;

    @CommandLine.ArgGroup(exclusive = false)
    private ExampleOptions exampleOptions = new ExampleOptions();

    @CommandLine.ArgGroup(exclusive = false)
    private Profiling.ProfilingOptions profilingOptions = new Profiling.ProfilingOptions();

    public static void main(String... args) {
        System.exit(new CommandLine(new TradingExample()).execute(args));
    }

    @Override
    public Integer call() {
        logConfiguration(log, this);
        Duration exampleDuration = Duration.ofSeconds(exampleOptions.getDuration());
        Duration tradesThrottling = Duration.ofNanos(waitDelayInMicros * 1000);

        waitForUserInput(log);

        WheelTimer throttlingTimer = createThrottlingTimer(tradesThrottling, exampleOptions.getClientsCount(), exampleOptions);

        FixEngine fixEngine = fixEngineBuilder(exampleOptions, false,
                FixAcceptorApplication::new,
                sid -> new FixInitiatorApplication(sid, tradesThrottling, throttlingTimer),
                sid -> Map.of(FixAcceptorApplication.USER_ACCOUNT_SETTING, "testAccount_for_" + sid.getSenderCompID().getValue()),
                sid -> Collections.emptyMap())
                .build()
                .instance()
                .start();

        FixAcceptor fixAcceptor = fixEngine.newAcceptor(getFixAcceptorBuilder(exampleOptions).build()).start();

        List<FixInitiator> initiators = new ArrayList<>();
        for (int i = 1; i <= exampleOptions.getClientsCount(); i++) {
            FixInitiator initiator = fixEngine.newInitiator(getFixInitiatorBuilder(getInitiatorSessionId(i), getInitiatorSenderCompId(i), exampleOptions)
                    .build()).start();
            initiators.add(initiator);
        }

        Profiling.startProfilingIfNeeded(profilingOptions, TradingExample.class);
        registerShutdownHook(initiators, throttlingTimer, fixAcceptor);
        LockSupport.parkNanos(exampleDuration.toNanos());
        shutdown(initiators, throttlingTimer, fixAcceptor);
        return 0;
    }

    private static final class FixAcceptorApplication implements FixApplication {

        static final FixApplicationSessionSettingDescriptor USER_ACCOUNT_SETTING = FixApplicationSessionSettingDescriptor
                .of("tradingUserAccount", "The backed user account for which the NewOrderSingle are received");

        private String tradingUserAccount;

        FixAcceptorApplication(FixSessionId fixSessionId) {
            log.info("Creating dedicated acceptor fix application instance for session {}", fixSessionId);
        }

        @Override
        public Collection<FixApplicationSessionSettingDescriptor> getRequiredFixSessionSettings() {
            return List.of(USER_ACCOUNT_SETTING);
        }

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
            tradingUserAccount = fixSessionSettings.getFixApplicationSessionSettings().get(USER_ACCOUNT_SETTING);
            log.info("Trading user account bound to session {} is {}", fixSession.getFixSessionId(), tradingUserAccount);
            encodedMessagesTypes.add(MessageTypes.ExecutionReport);
            return List.of(new NewOrderSingleDecoder(fixSession));
        }

        @Override
        public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
            log.info("Logon acceptor {}", fixSession.getFixSessionId());
        }

        @Setter
        private static class NewOrderSingleDecoder implements FixMessageDecoder {

            private final ExecutionReportEncoder executionReportEncoderNew;
            private final ExecutionReportEncoder executionReportEncoderFilled;

            private String symbol;
            private UUID clOrdId;
            private Side.SideValues side;
            private TimeInForce.TimeInForceValues tif;
            private OrdType.OrdTypeValues ordType;
            private double ordQty;
            private DecimalFloat price;

            NewOrderSingleDecoder(FixSession fixSession) {
                executionReportEncoderNew = fixSession.newEncoder(ExecutionReportEncoder.class).asReusable();
                executionReportEncoderFilled = fixSession.newEncoder(ExecutionReportEncoder.class).asReusable();
            }

            @Override
            public MessageType getMessageType() {
                return MessageTypes.NewOrderSingle;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
                fixFieldsDecoderMapper
                        .mapUUIDField(ClOrdID.get(), this::setClOrdId, null, FixFieldsDecoderMapper.ObjectInstanceStrategy.THREAD_LOCAL)
                        .mapStringField(Symbol.get(), this::setSymbol, null, FixFieldsDecoderMapper.ObjectInstanceStrategy.CACHED)
                        .mapCharValuesEnumField(Side.get(), this::setSide, null)
                        .mapCharValuesEnumField(TimeInForce.get(), this::setTif, null)
                        .mapCharValuesEnumField(OrdType.get(), this::setOrdType, null)
                        .mapDoubleField(OrderQty.get(), this::setOrdQty, 0)
                        .mapDecimalFloatField(Price.get(), this::setPrice, null, FixFieldsDecoderMapper.ObjectInstanceStrategy.THREAD_LOCAL);
            }

            @Override
            public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
                long orderId = System.nanoTime();
                long execId = System.nanoTime();
                fixSession.send(executionReportEncoderNew.begin()
                        .setOrdStatus(OrdStatus.OrdStatusValues.NEW)
                        .setSymbol(symbol)
                        .addUUID(ClOrdID.get(), clOrdId)
                        .setSide(side)
                        .setPrice(price)
                        .setOrdType(ordType)
                        .setOrderQty(ordQty)
                        .setLeavesQty(ordQty)
                        .addLong(OrderID.get(), orderId)
                        .setTimeInForce(tif)
                        .setCumQty(0d)
                        .setAvgPx(0d)
                        .addLong(ExecID.get(), 0L)
                        .setExecType(ExecType.ExecTypeValues.NEW), null);

                fixSession.send(executionReportEncoderFilled.begin()
                        .setOrdStatus(OrdStatus.OrdStatusValues.FILLED)
                        .setSymbol(symbol)
                        .addUUID(ClOrdID.get(), clOrdId)
                        .setSide(side)
                        .setPrice(price)
                        .setOrdType(ordType)
                        .setOrderQty(ordQty)
                        .setLastQty(ordQty)
                        .setLeavesQty(0)
                        .addLong(OrderID.get(), orderId)
                        .setTimeInForce(tif)
                        .setCumQty(ordQty)
                        .setAvgPx(price)
                        .addLong(ExecID.get(), execId)
                        .setExecType(ExecType.ExecTypeValues.TRADE), null);
            }
        }
    }

    private static final class FixInitiatorApplication implements FixApplication {

        private static final UUIDsGenerator UUIDS = UUIDsGenerator.instance();
        private final Duration tradesThrottling;
        private final WheelTimer throttlingTimer;
        private final MutableTimeout reusableTimeout;
        private final Runnable sendOrderInIOThreadRunnable = this::sendOrderInIOThread;
        private final Runnable sendOrderNowRunnable = this::sendOrderNow;
        private FixSession fixSession;
        private NewOrderSingleEncoder newOrderSingleEncoder;
        private Clock clock;

        FixInitiatorApplication(FixSessionId fixSessionId, Duration tradesThrottling, WheelTimer throttlingTimer) {
            this.tradesThrottling = tradesThrottling;
            this.throttlingTimer = throttlingTimer;
            this.reusableTimeout = throttlingTimer != null ? throttlingTimer.newReusableTimeout() : null;
            log.info("Creating dedicated initiator fix application instance for session {}", fixSessionId);
        }

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        private void scheduleSendOrder() {
            if (tradesThrottling.isZero()) {
                sendOrderNow();
                return;
            }
            Timeout t = throttlingTimer.schedule(reusableTimeout, sendOrderInIOThreadRunnable,
                    tradesThrottling.toNanos(), TimeUnit.NANOSECONDS);
            if (t.isRejected()) {
                log.warn("Throttling submission queue full for session {} — dropping order", fixSession.getFixSessionId());
            }
        }

        private void sendOrderInIOThread() {
            fixSession.processTask(sendOrderNowRunnable);
        }

        private void sendOrderNow() {
            newOrderSingleEncoder.begin()
                    .setTransactTime(clock.now(), TimeUnit.MICROSECONDS)
                    .setSymbol("XAG/USD")
                    .setSide(Side.SideValues.BUY)
                    .setOrdType(OrdType.OrdTypeValues.LIMIT)
                    .setPrice(121.111)
                    .setTimeInForce(TimeInForce.TimeInForceValues.FILL_OR_KILL)
                    .addUUID(ClOrdID.get(), UUIDS.threadLocalV7())
                    .setOrderQty(1234);
            fixSession.send(newOrderSingleEncoder, null);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
            this.fixSession = fixSession;
            this.newOrderSingleEncoder = fixSession.newEncoder(NewOrderSingleEncoder.class).asReusable();
            clock = fixSession.getClock();
            encodedMessagesTypes.add(MessageTypes.NewOrderSingle);
            return List.of(new ExecutionReportDecoder(this::scheduleSendOrder));
        }

        @Override
        public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
            log.info("Logon initiator {}", fixSession.getFixSessionId());
            scheduleSendOrder();
        }

        @Setter
        @RequiredArgsConstructor
        private static class ExecutionReportDecoder implements FixMessageDecoder {

            private final Runnable scheduleSendOrder;
            private long nextLogTimestamp = System.currentTimeMillis();
            private int receivedExecReports;

            private OrdStatus.OrdStatusValues ordStatus;
            private String symbol;
            private UUID clOrdId;
            private Side.SideValues side;
            private double price;
            private double avgPx;
            private OrdType.OrdTypeValues ordType;
            private double orderQty;
            private double lastQty = Double.NaN;
            private double leavesQty = Double.NaN;
            private double cumQty = Double.NaN;
            private long ordId;
            private TimeInForce.TimeInForceValues tif;
            private UTCTime transactTime;
            private long execId;
            private ExecType.ExecTypeValues execType;

            @Override
            public MessageType getMessageType() {
                return MessageTypes.ExecutionReport;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
                fixFieldsDecoderMapper
                        .mapCharValuesEnumField(OrdStatus.get(), this::setOrdStatus, null)
                        .mapStringField(Symbol.get(), this::setSymbol, null, FixFieldsDecoderMapper.ObjectInstanceStrategy.CACHED)
                        .mapUUIDField(ClOrdID.get(), this::setClOrdId, null, FixFieldsDecoderMapper.ObjectInstanceStrategy.THREAD_LOCAL)
                        .mapCharValuesEnumField(Side.get(), this::setSide, null)
                        .mapDoubleField(Price.get(), this::setPrice, 0)
                        .mapDoubleField(AvgPx.get(), this::setAvgPx, 0)
                        .mapCharValuesEnumField(OrdType.get(), this::setOrdType, null)
                        .mapDoubleField(OrderQty.get(), this::setOrderQty, 0)
                        .mapDoubleField(CumQty.get(), this::setCumQty, 0)
                        .mapDoubleField(LastQty.get(), this::setLastQty, Double.NaN)
                        .mapDoubleField(LeavesQty.get(), this::setLeavesQty, Double.NaN)
                        .mapLongField(OrderID.get(), this::setOrdId, 0)
                        .mapCharValuesEnumField(TimeInForce.get(), this::setTif, null)
                        .mapUtcDateTimeField(TransactTime.get(), this::setTransactTime, null)
                        .mapCharValuesEnumField(ExecType.get(), this::setExecType, null)
                        .mapLongField(ExecID.get(), this::setExecId, 0);
            }

            @Override
            public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
                if (ordStatus.equals(OrdStatus.OrdStatusValues.FILLED)) {
                    scheduleSendOrder.run();
                }
                receivedExecReports++;
                long now = System.currentTimeMillis();
                if (now > nextLogTimestamp) {
                    log.info("Received {} execution reports/s on FIX session {}", receivedExecReports / 5, fixSession.getFixSessionId());
                    receivedExecReports = 0;
                    nextLogTimestamp = now + TimeUnit.SECONDS.toMillis(5);
                }
            }
        }
    }
}