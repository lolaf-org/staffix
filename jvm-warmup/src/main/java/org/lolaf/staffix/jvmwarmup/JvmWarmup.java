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
package org.lolaf.staffix.jvmwarmup;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.betty.api.settings.IOWorkersGroupSettings;
import org.lolaf.betty.api.ss.IdleStrategySelectStrategy;
import org.lolaf.betty.api.ss.WakeupSelectStrategy;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BusySpinIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.ringos.idling.TimedWaitNotifyIdleStrategy;
import org.lolaf.ringos.idling.WaitNotifyIdleStrategy;
import org.lolaf.ringos.timer.WheelTimer;
import org.lolaf.staffix.api.FixAcceptorBuilder;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.FixInitiatorBuilder;
import org.lolaf.staffix.api.executor.MessageExecutorSettings;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.stores.loggers.slf4j.Slf4jMessagesLoggerSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * In-process FIX 4.4 warmup driver. Spins up an acceptor and an initiator on loopback, exchanges
 * synthetic {@code JVMWarmup} messages for a configurable duration, then tears everything down and
 * completes the future it handed back.
 *
 * <p>The call is non-blocking: {@link #run(JvmWarmupOptions)} returns immediately and the warmup
 * proceeds on a daemon controller thread. The future is completed only once the engines have been
 * stopped and the loopback port released, so blocking on it is all that is needed to sequence the
 * real sessions behind the warmup.
 *
 * <pre>{@code
 * // block until warm
 * JvmWarmup.run(JvmWarmupOptions.builder().build()).join();
 *
 * // or carry on, and be told when it is done
 * JvmWarmup.run(options).thenAccept(result -> log.info("warmup done: {}", result));
 * }</pre>
 */
@Slf4j
public final class JvmWarmup {

    private JvmWarmup() {
    }

    /**
     * Starts a warmup on a daemon thread.
     *
     * @return a future completed with the {@link JvmWarmupResult} once the warmup has finished and torn
     * its engines down, or completed exceptionally if it failed. Cancelling it asks the warmup to stop
     * early — see {@link WarmupFuture#cancel(boolean)}.
     */
    public static WarmupFuture run(JvmWarmupOptions options) {
        Objects.requireNonNull(options, "options");
        Controller controller = new Controller(options);
        Thread t = new Thread(controller, "jvm-warmup-controller");
        t.setDaemon(true);
        t.start();
        return controller.future;
    }

    /**
     * The future returned by {@link #run(JvmWarmupOptions)}. An ordinary {@link CompletableFuture} in
     * every respect but one, {@link #cancel(boolean)}.
     */
    public static final class WarmupFuture extends CompletableFuture<JvmWarmupResult> {

        private final Controller controller;

        private WarmupFuture(Controller controller) {
            this.controller = controller;
        }

        /**
         * Asks the warmup to stop early. Unlike {@link CompletableFuture#cancel(boolean)}, this does not
         * abandon the computation: the warmup stops driving traffic, tears its engines down as usual, and
         * this future then <em>completes normally</em> with a {@link JvmWarmupResult} whose
         * {@link JvmWarmupResult#isCancelled()} is {@code true} and whose counters hold what was exchanged
         * before the stop. Keeping that partial result is the point — a warmup cut short still warmed
         * everything it ran through, and the caller usually wants to know how far it got.
         *
         * <p>So this returns {@code false} and {@link #isCancelled()} stays {@code false}: the future was
         * never cancelled in the {@link java.util.concurrent.Future} sense, and {@code join()} returns a
         * result rather than throwing. {@code mayInterruptIfRunning} is ignored — the controller thread is
         * unparked either way, and the engines are always shut down cleanly.
         */
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            controller.requestCancel();
            return false;
        }
    }

    private static final class Controller implements Runnable {

        private final JvmWarmupOptions options;
        private final WarmupFuture future;
        private volatile Thread runner;
        private volatile boolean cancelRequested;

        Controller(JvmWarmupOptions options) {
            this.options = options;
            this.future = new WarmupFuture(this);
        }

        private static void shutdownQuietly(FixEngine engine, WheelTimer throttlingTimer) {
            try {
                if (engine != null) {
                    engine.stop(Deadline.of(Duration.ofSeconds(5)));
                }
            } catch (Exception e) {
                log.warn("Engine stop failed", e);
            }
            try {
                if (throttlingTimer != null) {
                    throttlingTimer.stop(Duration.ofSeconds(5));
                }
            } catch (Exception e) {
                log.warn("Throttling timer stop failed", e);
            }
        }

        private static int nextPowerOfTwo(int v) {
            if (v <= 1) {
                return 1;
            }
            return Integer.highestOneBit(v - 1) << 1;
        }

        void requestCancel() {
            cancelRequested = true;
            Thread r = runner;
            if (r != null) {
                LockSupport.unpark(r);
            }
        }

        @Override
        public void run() {
            runner = Thread.currentThread();
            long startNanos = System.nanoTime();
            WheelTimer throttlingTimer = null;
            FixEngine engine = null;
            List<JvmWarmupInitiatorApplication> initiatorApps = new ArrayList<>();
            try {
                throttlingTimer = startThrottlingTimerIfNeeded();
                Supplier<IdleStrategy> idle = options.isLowLatency() ? BusySpinIdleStrategy::getInstance : WaitNotifyIdleStrategy::new;

                FixEngineBuilder sourceBuilder = options.getFixEngineBuilder() != null
                        ? options.getFixEngineBuilder()
                        : FixEngineBuilder.builder()
                        .fixMessagesStore(MemoryMessageStoreSettings.builder().maxEntriesInMemory(0).build())
                        .fixMessagesLogger(Slf4jMessagesLoggerSettings.builder()
                                .logIncoming(false).logOutgoing(false).build())
                        .build();

                List<? extends FixMessagesStoreSettings> storeSettings = sourceBuilder.getFixMessagesStores();
                List<? extends FixMessagesLoggerSettings> loggerSettings = sourceBuilder.getFixMessagesLoggers();
                List<? extends FixSessionsPluginSettings<?>> pluginSettings = sourceBuilder.getFixSessionsPlugins();

                Map<Class<? extends FixSessionsPlugin<?>>, String> pluginInstanceIds = new LinkedHashMap<>();
                for (FixSessionsPluginSettings<?> ps : pluginSettings) {
                    FixSessionsPlugin<?> tmp = ps.instance();
                    @SuppressWarnings("unchecked")
                    Class<? extends FixSessionsPlugin<?>> pluginClass = (Class<? extends FixSessionsPlugin<?>>) tmp.getClass();
                    pluginInstanceIds.put(pluginClass, ps.getInstanceId());
                }

                int pairsCount = Math.max(pluginSettings.size(),
                        Math.max(storeSettings.size(), Math.max(1, loggerSettings.size())));

                SimpleApplicationFactorySettings.SimpleApplicationFactorySettingsBuilder appFactoryBuilder =
                        SimpleApplicationFactorySettings.builder();
                MemorySessionsSettingsStoreSettings.MemorySessionsSettingsStoreSettingsBuilder sessionsBuilder =
                        MemorySessionsSettingsStoreSettings.builder();
                List<FixSessionId> initiatorSids = new ArrayList<>();

                for (int i = 0; i < pairsCount; i++) {
                    String suffix = "-" + i;
                    FixSessionId acceptorSid;
                    FixSessionId initiatorSid;
                    FixSessionId.FixSessionIdBuilder acceptorBuilder = FixSessionId.FixSessionIdBuilder.builder()
                            .id("jvm-warmup-acceptor" + suffix)
                            .targetCompID(options.getSender() + suffix)
                            .senderCompID(options.getTarget() + suffix)
                            .group("jvm-warmup-acceptor")
                            .build();
                    FixSessionId.FixSessionIdBuilder initiatorBuilder = FixSessionId.FixSessionIdBuilder.builder()
                            .id("jvm-warmup-initiator" + suffix)
                            .targetCompID(options.getTarget() + suffix)
                            .senderCompID(options.getSender() + suffix)
                            .group("jvm-warmup-initiator")
                            .build();

                    if (options.isFixt()) {
                        acceptorSid = FixSessionId.ofFIXT11(FixApplVerID.FIX44, acceptorBuilder);
                        initiatorSid = FixSessionId.ofFIXT11(FixApplVerID.FIX44, initiatorBuilder);
                    } else {
                        acceptorSid = FixSessionId.of(FixRegularVersion.VERSION_44, acceptorBuilder);
                        initiatorSid = FixSessionId.of(FixRegularVersion.VERSION_44, initiatorBuilder);
                    }
                    initiatorSids.add(initiatorSid);

                    JvmWarmupAcceptorApplication acceptorApp = new JvmWarmupAcceptorApplication();
                    JvmWarmupInitiatorApplication initiatorApp =
                            new JvmWarmupInitiatorApplication(options.getThrottling(), throttlingTimer);
                    initiatorApps.add(initiatorApp);

                    appFactoryBuilder.application(acceptorSid.getId(), acceptorApp);
                    appFactoryBuilder.application(initiatorSid.getId(), initiatorApp);

                    String storeInstanceId = storeSettings.get(i % storeSettings.size()).getInstanceId();
                    FixSessionSettings.FixSessionSettingsBuilder<?, ?> acceptorSettings = FixSessionSettings.builder()
                            .fixSessionId(acceptorSid)
                            .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                            .dictionaryId("jvm-warmup")
                            .fixApplicationInstanceId(acceptorSid.getId())
                            .fixMessageStoreInstanceId(storeInstanceId)
                            .rttMeasurementSettings(FixSessionSettings.RttMeasurementSettings.builder().probeInterval(Duration.ofSeconds(2)).build())
                            .resetSeqNumOnLogon(true);
                    FixSessionSettings.FixSessionSettingsBuilder<?, ?> initiatorSettings = FixSessionSettings.builder()
                            .fixSessionId(initiatorSid)
                            .fixSessionType(FixSession.FixSessionType.INITIATOR)
                            .dictionaryId("jvm-warmup")
                            .fixApplicationInstanceId(initiatorSid.getId())
                            .fixMessageStoreInstanceId(storeInstanceId)
                            .rttMeasurementSettings(FixSessionSettings.RttMeasurementSettings.builder().probeInterval(Duration.ofSeconds(2)).build())
                            .resetSeqNumOnLogon(true);

                    if (!loggerSettings.isEmpty()) {
                        String loggerInstanceId = loggerSettings.get(i % loggerSettings.size()).getInstanceId();
                        acceptorSettings.fixMessageLoggerInstanceId(loggerInstanceId);
                        initiatorSettings.fixMessageLoggerInstanceId(loggerInstanceId);
                    }

                    pluginInstanceIds.forEach(acceptorSettings::fixSessionPluginsInstanceId);
                    pluginInstanceIds.forEach(initiatorSettings::fixSessionPluginsInstanceId);

                    sessionsBuilder.fixSessionSetting(acceptorSettings.build());
                    sessionsBuilder.fixSessionSetting(initiatorSettings.build());
                }

                engine = sourceBuilder.toBuilder()
                        .instanceId(options.getAcceptorInstanceId())
                        .clearFixApplicationFactories()
                        .fixApplicationFactory(appFactoryBuilder.build())
                        .clearFixSessionsSettingsStores()
                        .fixSessionsSettingsStore(sessionsBuilder.build())
                        .adminApiExporter(null)
                        .build().instance();

                engine.start();

                engine.newAcceptor(FixAcceptorBuilder.builder()
                        .instanceId(options.getAcceptorInstanceId())
                        .bindAddress(new InetSocketAddress("localhost", options.getPort()))
                        .messageExecutorSettings(MessageExecutorSettings.builder().build())
                        .ioWorkersGroup(IOWorkersGroupSettings.builder()
                                .ioThreadGroup(IOWorkersGroupSettings.IOThreadGroup.builder()
                                        .ioThreadCount(Math.min(pairsCount, Runtime.getRuntime().availableProcessors()))
                                        .selectStrategy(options.isLowLatency() ? new IdleStrategySelectStrategy(BusySpinIdleStrategy.getInstance()) : new WakeupSelectStrategy(10))
                                        .build())
                                .build().newInstance())
                        .build()).start();

                for (int i = 0; i < pairsCount; i++) {
                    engine.newInitiator(FixInitiatorBuilder.builder()
                            .instanceId(options.getInitiatorInstanceId() + "-" + i)
                            .connectAddress(new InetSocketAddress("localhost", options.getPort()))
                            .connectionRetry(Duration.ofSeconds(1))
                            .fixSessionId(initiatorSids.get(i))
                            .messageExecutorSettings(MessageExecutorSettings.builder()
                                    .executorsThreadsCount(2)
                                    .idleStrategy(idle).build())
                            .build()).start();
                }

                log.info("Warmup running on loopback:{} for {} (throttling={}, sessions={})",
                        options.getPort(), options.getWarmupDuration(),
                        options.getThrottling(), pairsCount);

                parkInterruptibly(options.getWarmupDuration().toNanos());

                long totalSent = initiatorApps.stream().mapToLong(JvmWarmupInitiatorApplication::getMessagesSent).sum();
                long totalReceived = initiatorApps.stream().mapToLong(JvmWarmupInitiatorApplication::getMessagesReceived).sum();

                JvmWarmupResult result = JvmWarmupResult.builder()
                        .messagesSent(totalSent)
                        .messagesReceived(totalReceived)
                        .actualDuration(Duration.ofNanos(System.nanoTime() - startNanos))
                        .cancelled(cancelRequested)
                        .build();

                log.info("Warmup ending: sent={}, received={}, duration={}",
                        result.getMessagesSent(), result.getMessagesReceived(), result.getActualDuration());

                // shut down before completing: whoever is waiting on the future is usually about to bind
                // the same port for the real sessions
                shutdownQuietly(engine, throttlingTimer);
                future.complete(result);
            } catch (Throwable cause) {
                log.error("Warmup failed", cause);
                shutdownQuietly(engine, throttlingTimer);
                future.completeExceptionally(cause);
            }
        }

        private WheelTimer startThrottlingTimerIfNeeded() {
            Duration throttling = options.getThrottling();
            if (throttling.isZero()) {
                return null;
            }
            long delayNanos = throttling.toNanos();
            long tickNanos = Math.max(1_000L, delayNanos / 16);
            int wheelSize = nextPowerOfTwo(Math.max(64, (int) ((delayNanos / tickNanos) * 4)));
            WheelTimer timer = new WheelTimer(tickNanos, wheelSize, 64, true);
            IdleStrategy idle = options.isLowLatency() ? BusySpinIdleStrategy.getInstance() : new TimedWaitNotifyIdleStrategy(Duration.ofNanos(tickNanos));
            timer.startOwnThread(idle, r -> {
                Thread t = new Thread(r);
                t.setName("jvm-warmup-throttling-timer");
                t.setDaemon(true);
                return t;
            });
            return timer;
        }

        private void parkInterruptibly(long nanos) {
            long deadline = System.nanoTime() + nanos;
            while (!cancelRequested) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                LockSupport.parkNanos(this, remaining);
            }
        }
    }
}
