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
import org.lolaf.staffix.alerting.slf4j.Slf4jAlertsPublisherSettings;
import org.lolaf.staffix.api.*;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.AlertLevel;
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeEvaluator;
import org.lolaf.staffix.api.alerting.RaisedAlertEvaluator;
import org.lolaf.staffix.api.alerting.RateAlertEvaluator;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.fix44.encoders.NewOrderSingleEncoder;
import org.lolaf.staffix.fix44.fields.OrdType;
import org.lolaf.staffix.fix44.fields.OrderQty;
import org.lolaf.staffix.fix44.fields.Side;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import picocli.CommandLine;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/**
 * An application watching its own business flow with probes.
 * <p>
 * Each initiator sends NewOrderSingle in bursts, with pauses that grow from one burst to the next, and every few
 * bursts one order above the acceptor's quantity limit. The acceptor's application adds three probes to its session
 * on logon and removes them on logout:
 * <ul>
 *     <li>{@code order-flow}, its own evaluator measuring the time since the last order, {@code WARNING} after a
 *     short silence and {@code MAJOR} after a longer one, re-published while it lasts;</li>
 *     <li>{@code order-rate}, a {@link RateAlertEvaluator} the decoder records each order on, {@code WARNING} when
 *     two bursts come close together;</li>
 *     <li>{@code large-order}, a {@link RaisedAlertEvaluator} the decoder raises on an order above the limit, which
 *     expires on its own, with labels and a runbook URL a publisher passes on.</li>
 * </ul>
 * Their alerts go through the SLF4J alerts publisher the sessions name by instance id, so the log shows each alert
 * raised, changed, reminded and cleared.
 * <p>
 * Read {@link OrderFlowEvaluator} for what a probe evaluates, then {@link OrderReceivingApplication#onLogon} for how
 * they are added. Every session also carries the engine's built-in {@code session-down} probe, which the same
 * publisher reports.
 * <p>
 * Run with -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints --enable-native-access=ALL-UNNAMED --add-opens java.base/jdk.internal.ref=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED
 */
@Slf4j
@CommandLine.Command(name = "AlertingExample", mixinStandardHelpOptions = true, version = "1.0",
        description = "A probe watching the order flow, its alerts logged through SLF4J", showDefaultValues = true)
public class AlertingExample extends FixExamplesBase implements Callable<Integer> {

    private static final String ALERTS_PUBLISHER_INSTANCE_ID = "alerts";
    private static final String ORDER_FLOW_PROBE = "order-flow";
    private static final String ORDER_RATE_PROBE = "order-rate";
    private static final String LARGE_ORDER_PROBE = "large-order";

    @CommandLine.Option(names = "-bs", description = "Orders sent in each burst", defaultValue = "5")
    private int burstSize;

    @CommandLine.Option(names = "-p", description = "Pauses in seconds between bursts, cycled through", split = ",", defaultValue = "1,6,20")
    private long[] pausesInSeconds;

    @CommandLine.Option(names = "-ws", description = "Seconds without an order before a warning", defaultValue = "4")
    private long warningAfterSeconds;

    @CommandLine.Option(names = "-ms", description = "Seconds without an order before a major alert", defaultValue = "10")
    private long majorAfterSeconds;

    @CommandLine.Option(names = "-ri", description = "Seconds between two publications of an ongoing alert", defaultValue = "5")
    private long reminderIntervalInSeconds;

    @CommandLine.Option(names = "-rw", description = "Orders within 5s before a rate warning", defaultValue = "8")
    private int rateWarningOrders;

    @CommandLine.Option(names = "-ql", description = "Order quantity above which an order raises an alert", defaultValue = "10000")
    private long quantityLimit;

    @CommandLine.Option(names = "-lb", description = "Every how many bursts an order above the limit is sent", defaultValue = "4")
    private int largeOrderEveryBursts;

    @CommandLine.ArgGroup(exclusive = false)
    private ExampleOptions exampleOptions = new ExampleOptions();

    @CommandLine.ArgGroup(exclusive = false)
    private Profiling.ProfilingOptions profilingOptions = new Profiling.ProfilingOptions();

    public static void main(String... args) {
        System.exit(new CommandLine(new AlertingExample()).execute(args));
    }

    @Override
    public Integer call() {
        logConfiguration(log, this);
        Duration exampleDuration = Duration.ofSeconds(exampleOptions.getDuration());

        waitForUserInput(log);

        FixEngineBuilder.FixEngineBuilderBuilder<?, ?> engineBuilder = fixEngineBuilder(exampleOptions, true,
                sid -> new OrderReceivingApplication(Duration.ofSeconds(warningAfterSeconds), Duration.ofSeconds(majorAfterSeconds),
                        Duration.ofSeconds(reminderIntervalInSeconds), rateWarningOrders, quantityLimit),
                sid -> new OrderSendingApplication(burstSize, Arrays.stream(pausesInSeconds).mapToObj(Duration::ofSeconds).toArray(Duration[]::new),
                        largeOrderEveryBursts, quantityLimit * 10),
                sid -> Collections.emptyMap(),
                sid -> Collections.emptyMap());

        // Registered on the engine like a store or a logger, and named by the sessions in getFixSessionSetting.
        engineBuilder.fixAlertsPublisher(Slf4jAlertsPublisherSettings.builder()
                .instanceId(ALERTS_PUBLISHER_INSTANCE_ID)
                .build());

        FixEngine fixEngine = engineBuilder.build().instance().start();

        FixAcceptor fixAcceptor = fixEngine.newAcceptor(getFixAcceptorBuilder(exampleOptions).build()).start();

        List<FixInitiator> initiators = new ArrayList<>();
        for (int i = 1; i <= exampleOptions.getClientsCount(); i++) {
            initiators.add(fixEngine.newInitiator(getFixInitiatorBuilder(getInitiatorSessionId(i),
                    getInitiatorSenderCompId(i), exampleOptions).build()).start());
        }

        Profiling.startProfilingIfNeeded(profilingOptions, AlertingExample.class);
        registerShutdownHook(initiators, null, fixAcceptor, fixEngine);
        LockSupport.parkNanos(exampleDuration.toNanos());
        shutdown(initiators, null, fixAcceptor, fixEngine);
        return 0;
    }

    @Override
    public FixSessionSettings getFixSessionSetting(FixSessionId sid, FixSession.FixSessionType sessionType,
                                                   String targetInstancesId, boolean resetSequenceOnLogon,
                                                   Function<FixSessionId, Map<FixApplicationSessionSettingDescriptor, String>> fixApplicationSessionSettings,
                                                   ExampleOptions options) {
        return super.getFixSessionSetting(sid, sessionType, targetInstancesId, resetSequenceOnLogon,
                        fixApplicationSessionSettings, options).toBuilder()
                .fixAlertsPublisherInstanceId(ALERTS_PUBLISHER_INSTANCE_ID)
                .build();
    }

    /**
     * Runs on the engine's evaluation thread, not the session's, which is why the time of the last order is volatile.
     * One alert per level is built up front, so an evaluation allocates nothing.
     */
    private static final class OrderFlowEvaluator implements ProbeEvaluator {

        private final long warningAfterNanos;
        private final long majorAfterNanos;
        private final Optional<Alert> warning;
        private final Optional<Alert> major;
        private volatile long lastOrderNanos = System.nanoTime();

        OrderFlowEvaluator(Duration warningAfter, Duration majorAfter) {
            this.warningAfterNanos = warningAfter.toNanos();
            this.majorAfterNanos = majorAfter.toNanos();
            this.warning = Optional.of(Alert.warning("no order for more than " + warningAfter.toSeconds() + "s"));
            this.major = Optional.of(Alert.major("no order for more than " + majorAfter.toSeconds() + "s"));
        }

        void onOrder() {
            lastOrderNanos = System.nanoTime();
        }

        @Override
        public Optional<Alert> evaluate() {
            long silence = System.nanoTime() - lastOrderNanos;
            if (silence >= majorAfterNanos) {
                return major;
            }
            if (silence >= warningAfterNanos) {
                return warning;
            }
            return Optional.empty();
        }
    }

    /**
     * Owns the probes for as long as its session is logged on.
     */
    @RequiredArgsConstructor
    private static final class OrderReceivingApplication implements FixApplication {

        private final Duration warningAfter;
        private final Duration majorAfter;
        private final Duration reminderInterval;
        private final int rateWarningOrders;
        private final long quantityLimit;
        private volatile OrderFlowEvaluator orderFlow;
        private volatile RateAlertEvaluator orderRate;
        private volatile RaisedAlertEvaluator largeOrder;

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
            return List.of(new NewOrderSingleDecoder(this));
        }

        /**
         * Fresh evaluators per logon, so nothing measured before a disconnection is carried over.
         */
        @Override
        public void onLogon(FixSession session, DecodedFixMessage logon) {
            orderFlow = new OrderFlowEvaluator(warningAfter, majorAfter);
            orderRate = RateAlertEvaluator.builder()
                    .window(Duration.ofSeconds(5))
                    .threshold(AlertLevel.WARNING, rateWarningOrders)
                    .build();
            largeOrder = new RaisedAlertEvaluator();
            session.getProbes().add(Probe.builder()
                    .name(ORDER_FLOW_PROBE)
                    .description("Time since the last NewOrderSingle")
                    .evaluator(orderFlow)
                    .reminderInterval(reminderInterval)
                    .build());
            session.getProbes().add(Probe.builder()
                    .name(ORDER_RATE_PROBE)
                    .description("NewOrderSingle received over the last 5 seconds")
                    .evaluator(orderRate)
                    .build());
            session.getProbes().add(Probe.builder()
                    .name(LARGE_ORDER_PROBE)
                    .description("NewOrderSingle above the quantity limit")
                    .evaluator(largeOrder)
                    .label("team", "trading_desk")
                    .runbookUrl(URI.create("https://wiki.example.com/runbooks/large-order"))
                    .build());
            log.info("Logon acceptor {}, probes {}, {} and {} added", session.getFixSessionId(), ORDER_FLOW_PROBE,
                    ORDER_RATE_PROBE, LARGE_ORDER_PROBE);
        }

        /**
         * Removing a probe clears its alert, if one is ongoing: a session nobody can send on is not short of orders.
         */
        @Override
        public void onLogout(FixSession session, String message, DecodedFixMessage logoutMessage) {
            session.getProbes().remove(ORDER_FLOW_PROBE);
            session.getProbes().remove(ORDER_RATE_PROBE);
            session.getProbes().remove(LARGE_ORDER_PROBE);
            log.info("Logout acceptor {} : {}, probes removed", session.getFixSessionId(), message);
        }

        /**
         * On the session thread: recording and raising are cheap enough for it.
         */
        void onOrder(double quantity) {
            OrderFlowEvaluator currentFlow = orderFlow;
            if (currentFlow == null) {
                return;
            }
            currentFlow.onOrder();
            orderRate.record();
            if (quantity > quantityLimit) {
                largeOrder.raise(Alert.major("order of " + (long) quantity + " above the limit of " + quantityLimit),
                        Duration.ofSeconds(15));
            }
        }

        @RequiredArgsConstructor
        private static final class NewOrderSingleDecoder implements FixMessageDecoder {

            private final OrderReceivingApplication application;
            private double quantity;

            @Override
            public MessageType getMessageType() {
                return MessageTypes.NewOrderSingle;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper mapper, FieldsRegistry fieldsRegistry) {
                mapper.mapDoubleField(OrderQty.get(), value -> quantity = value, 0d);
            }

            @Override
            public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
                application.onOrder(quantity);
            }
        }
    }

    /**
     * Sends bursts of orders from a thread of its own, pausing longer each time, and every few bursts one large order.
     */
    @RequiredArgsConstructor
    private static final class OrderSendingApplication implements FixApplication {

        private final int burstSize;
        private final Duration[] pauses;
        private final int largeOrderEveryBursts;
        private final long largeOrderQuantity;
        private volatile boolean sending;

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
            return List.of();
        }

        @Override
        public void onLogon(FixSession session, DecodedFixMessage logon) {
            log.info("Logon initiator {}", session.getFixSessionId());
            sending = true;
            Thread sender = new Thread(() -> sendBursts(session), session.getFixSessionId().getName() + "-orders");
            sender.setDaemon(true);
            sender.start();
        }

        @Override
        public void onLogout(FixSession session, String message, DecodedFixMessage logoutMessage) {
            log.info("Logout initiator {} : {}", session.getFixSessionId(), message);
            sending = false;
        }

        private void sendBursts(FixSession session) {
            for (int burst = 0; sending; burst++) {
                try {
                    for (int i = 0; i < burstSize; i++) {
                        sendOrder(session, 100);
                    }
                    if (burst % largeOrderEveryBursts == largeOrderEveryBursts - 1) {
                        sendOrder(session, largeOrderQuantity);
                        log.info("{} sent an order of {}", session.getFixSessionId().getName(), largeOrderQuantity);
                    }
                    Duration pause = pauses[burst % pauses.length];
                    log.info("{} sent {} orders, pausing {}s", session.getFixSessionId().getName(), burstSize, pause.toSeconds());
                    Thread.sleep(pause.toMillis());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException ex) {
                    // the session going down under us while shutting the example down
                    log.info("{} stopped sending: {}", session.getFixSessionId().getName(), ex.toString());
                    return;
                }
            }
        }

        private static void sendOrder(FixSession session, long quantity) {
            // a fresh encoder per message: a reusable one belongs to a single thread, and this one is not the
            // session's I/O thread - see docs/threading-model.md
            NewOrderSingleEncoder order = session.newEncoder(NewOrderSingleEncoder.class);
            order.begin()
                    .setClOrdID(UUID.randomUUID().toString())
                    .setTransactTime(session.getClock().now(), TimeUnit.MICROSECONDS)
                    .setSymbol("XAG/USD")
                    .setSide(Side.SideValues.BUY)
                    .setOrdType(OrdType.OrdTypeValues.MARKET)
                    .setOrderQty(quantity);
            session.send(order, null);
        }
    }
}
