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
import org.lolaf.staffix.api.alerting.Probe;
import org.lolaf.staffix.api.alerting.ProbeEvaluator;
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
import org.lolaf.staffix.fix44.fields.Side;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import picocli.CommandLine;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/**
 * An application watching its own business flow with a probe.
 * <p>
 * Each initiator sends NewOrderSingle in bursts, with pauses that grow from one burst to the next. The acceptor's
 * application adds an {@code order-flow} probe to its session on logon and removes it on logout: the probe measures
 * the time since the last order, {@code WARNING} after a short silence and {@code MAJOR} after a longer one, and is
 * re-published while it lasts. Its alerts go through the SLF4J alerts publisher the sessions name by instance id, so
 * the log shows each alert raised, changed, reminded and cleared.
 * <p>
 * Read {@link OrderFlowEvaluator} for what a probe evaluates, then {@link OrderReceivingApplication#onLogon} for how
 * it is added. Every session also carries the engine's built-in {@code session-down} probe, which the same publisher
 * reports.
 * <p>
 * Run with -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints --enable-native-access=ALL-UNNAMED --add-opens java.base/jdk.internal.ref=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED
 */
@Slf4j
@CommandLine.Command(name = "AlertingExample", mixinStandardHelpOptions = true, version = "1.0",
        description = "A probe watching the order flow, its alerts logged through SLF4J", showDefaultValues = true)
public class AlertingExample extends FixExamplesBase implements Callable<Integer> {

    private static final String ALERTS_PUBLISHER_INSTANCE_ID = "alerts";
    private static final String ORDER_FLOW_PROBE = "order-flow";

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
                        Duration.ofSeconds(reminderIntervalInSeconds)),
                sid -> new OrderSendingApplication(burstSize, Arrays.stream(pausesInSeconds).mapToObj(Duration::ofSeconds).toArray(Duration[]::new)),
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
     * Owns the probe for as long as its session is logged on.
     */
    @RequiredArgsConstructor
    private static final class OrderReceivingApplication implements FixApplication {

        private final Duration warningAfter;
        private final Duration majorAfter;
        private final Duration reminderInterval;
        private volatile OrderFlowEvaluator orderFlow;

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
         * A fresh evaluator per logon, so a silence measured before a disconnection is not carried over.
         */
        @Override
        public void onLogon(FixSession session, DecodedFixMessage logon) {
            orderFlow = new OrderFlowEvaluator(warningAfter, majorAfter);
            session.getProbes().add(Probe.builder()
                    .name(ORDER_FLOW_PROBE)
                    .description("Time since the last NewOrderSingle")
                    .evaluator(orderFlow)
                    .reminderInterval(reminderInterval)
                    .build());
            log.info("Logon acceptor {}, probe {} added", session.getFixSessionId(), ORDER_FLOW_PROBE);
        }

        /**
         * Removing the probe clears its alert, if one is ongoing: a session nobody can send on is not short of orders.
         */
        @Override
        public void onLogout(FixSession session, String message, DecodedFixMessage logoutMessage) {
            session.getProbes().remove(ORDER_FLOW_PROBE);
            log.info("Logout acceptor {} : {}, probe {} removed", session.getFixSessionId(), message, ORDER_FLOW_PROBE);
        }

        void onOrder() {
            OrderFlowEvaluator current = orderFlow;
            if (current != null) {
                current.onOrder();
            }
        }

        @RequiredArgsConstructor
        private static final class NewOrderSingleDecoder implements FixMessageDecoder {

            private final OrderReceivingApplication application;

            @Override
            public MessageType getMessageType() {
                return MessageTypes.NewOrderSingle;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper mapper, FieldsRegistry fieldsRegistry) {
                // the order's content does not matter here, only that one arrived
            }

            @Override
            public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
                application.onOrder();
            }
        }
    }

    /**
     * Sends bursts of orders from a thread of its own, pausing longer each time.
     */
    @RequiredArgsConstructor
    private static final class OrderSendingApplication implements FixApplication {

        private final int burstSize;
        private final Duration[] pauses;
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
                        sendOrder(session);
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

        private static void sendOrder(FixSession session) {
            // a fresh encoder per message: a reusable one belongs to a single thread, and this one is not the
            // session's I/O thread - see docs/threading-model.md
            NewOrderSingleEncoder order = session.newEncoder(NewOrderSingleEncoder.class);
            order.begin()
                    .setClOrdID(UUID.randomUUID().toString())
                    .setTransactTime(session.getClock().now(), TimeUnit.MICROSECONDS)
                    .setSymbol("XAG/USD")
                    .setSide(Side.SideValues.BUY)
                    .setOrdType(OrdType.OrdTypeValues.MARKET)
                    .setOrderQty(100);
            session.send(order, null);
        }
    }
}
