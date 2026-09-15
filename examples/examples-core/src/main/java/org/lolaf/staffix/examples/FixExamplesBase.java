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

import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import lombok.extern.slf4j.Slf4j;
import net.openhft.affinity.Affinity;
import net.openhft.chronicle.queue.RollCycles;
import org.lolaf.betty.api.settings.IOWorkersGroupSettings;
import org.lolaf.betty.api.ss.IdleStrategySelectStrategy;
import org.lolaf.betty.api.ss.WakeupSelectStrategy;
import org.lolaf.staffix.tracing.otlp.OtelTracing;
import org.lolaf.staffix.tracing.otlp.OtelTracingSettings;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.BusySpinIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.ringos.idling.WaitNotifyIdleStrategy;
import org.lolaf.ringos.threading.FastThreadLocalThread;
import org.lolaf.ringos.timer.WheelTimer;
import org.lolaf.staffix.admin.jmx.JmxAdminApiSettings;
import org.lolaf.staffix.api.*;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.executor.MessageExecutorSettings;
import org.lolaf.staffix.api.http.HttpSender;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.api.http.HttpVersion;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.monitoring.FixMonitoringConstants;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.http.jetty.JettyGrpcSender;
import org.lolaf.staffix.http.jetty.JettyHttpSender;
import org.lolaf.staffix.impl.FixEngineVersion;
import org.lolaf.staffix.monitoring.micrometer.otlp.OtlpMicrometerMonitoringManagerSettings;
import org.lolaf.staffix.tracing.otlp.sender.StaffixHttpSenderProvider;
import org.lolaf.staffix.tracing.otlp.sender.grpc.StaffixGrpcSenderProvider;
import org.lolaf.staffix.plugins.async.AsyncFixSessionsPluginSettings;
import org.lolaf.staffix.plugins.throttling.ThrottlingFixSessionsPluginSettings;
import org.lolaf.staffix.stores.core.async.AsyncStoreSettings;
import org.lolaf.staffix.stores.core.async.QueueSizeThresholdsListener;
import org.lolaf.staffix.stores.loggers.async.AsyncMessagesLoggerSettings;
import org.lolaf.staffix.stores.loggers.file.FileMessagesLoggerSettings;
import org.lolaf.staffix.stores.loggers.otlp.OtlpMessagesLoggerSettings;
import org.lolaf.staffix.stores.loggers.slf4j.Slf4jMessagesLoggerSettings;
import org.lolaf.staffix.stores.messages.async.AsyncMessagesStoreSettings;
import org.lolaf.staffix.stores.messages.file.FileMessageStoreSettings;
import org.lolaf.staffix.stores.messages.jdbc.JdbcMessageStoreSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.slf4j.Logger;
import picocli.CommandLine;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.regex.Pattern;

@Slf4j
class FixExamplesBase {

    static final String ACCEPTOR = "ACCEPTOR";
    static final String INITIATOR = "INITIATOR";

    private static final Pattern SECRET_OPTION = Pattern.compile("(?i).*(auth|password|secret|token|credential).*");

    static String getInitiatorSenderCompId(int i) {
        return INITIATOR + "_" + i;
    }

    static String getInitiatorSessionId(int i) {
        return "initiator-session-" + i;
    }

    static FixSessionId makeFixSessionId(String id, String senderCompId, String targetCompId, String group) {
        return FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                .id(id)
                .senderCompID(senderCompId)
                .targetCompID(targetCompId)
                .group(group).build());
    }

    /**
     * What the OTLP senders bind beyond their endpoint: the collector's credentials, when the example was
     * given any.
     */
    private static HttpSenderSettings otlpHttpSenderSettings(ExampleOptions options) {
        HttpSenderSettings.HttpSenderSettingsBuilder settings = HttpSenderSettings.builder();
        if (options.getMonitoringAuthHeader() != null) {
            settings.header("Authorization", "Basic " + options.getMonitoringAuthHeader());
        }
        return settings.build();
    }

    /**
     * The HTTP client the OTLP paths publish through, on the version the example was asked for.
     *
     * <p>Jetty on all of them, so one client serves logs, metrics and traces. The version is a
     * constructor argument rather than something {@link HttpSenderSettings} carries, because each client
     * asks for it differently - which is also why a factory, not a setting, is where an application
     * chooses it.
     *
     * @param options the example's options
     * @return a factory binding the chosen version
     */
    private static Function<HttpSenderSettings, HttpSender> otlpHttpSenderFactory(ExampleOptions options) {
        HttpVersion version = options.getOtlpHttpVersion();
        return settings -> new JettyHttpSender(settings, null, version);
    }

    /**
     * Logs every command-line option the example was started with: the example's own options, then
     * {@link ExampleOptions} and {@link Profiling.ProfilingOptions}, each under the class declaring them.
     * <p>
     * The list is read back from picocli's model of the already-populated instance, so an example gets its
     * custom options printed without registering them anywhere — anything annotated with
     * {@code @CommandLine.Option}, directly or through an {@code @CommandLine.ArgGroup}, shows up.
     * Values left at their declared default are dimmed with a {@code -} marker and the ones actually passed
     * on the command line with a {@code *}, so the configuration in effect is readable at a glance.
     * <p>
     * Purely diagnostic: any failure to build the report is swallowed, an example never fails to start
     * because its configuration could not be printed.
     */
    public static void logConfiguration(Logger log, Object example) {
        log.info("Example running with Java {}", Runtime.version());
        try {
            log.info("{}", describeConfiguration(example));
        } catch (RuntimeException e) {
            log.warn("Could not log the {} configuration", example.getClass().getSimpleName(), e);
        }
    }

    private static String describeConfiguration(Object example) {
        Map<Class<?>, List<CommandLine.Model.OptionSpec>> optionsByOwner = new LinkedHashMap<>();
        for (CommandLine.Model.OptionSpec option : new CommandLine(example).getCommandSpec().options()) {
            if (option.usageHelp() || option.versionHelp()) {
                // picocli's own --help/--version mixin: nothing the example was configured with
                continue;
            }
            Object userObject = option.userObject();
            Class<?> owner = userObject instanceof Field ? ((Field) userObject).getDeclaringClass() : example.getClass();
            optionsByOwner.computeIfAbsent(owner, key -> new ArrayList<>()).add(option);
        }

        StringBuilder report = new StringBuilder("Starting ").append(example.getClass().getSimpleName())
                .append(" with ('*' set on the command line, '-' left at its default):");
        optionsByOwner.forEach((owner, options) -> {
            report.append("\n  ").append(owner.getSimpleName());
            int switchWidth = options.stream().mapToInt(option -> option.longestName().length()).max().orElse(0);
            int nameWidth = options.stream().mapToInt(option -> optionName(option).length()).max().orElse(0);
            for (CommandLine.Model.OptionSpec option : options) {
                String value = renderValue(option);
                report.append("\n    ").append(isDefault(option, value) ? '-' : '*').append(' ')
                        .append(pad(option.longestName(), switchWidth)).append("  ")
                        .append(pad(optionName(option), nameWidth)).append(" = ").append(value);
            }
        });
        return report.toString();
    }

    private static String optionName(CommandLine.Model.OptionSpec option) {
        Object userObject = option.userObject();
        return userObject instanceof Field ? ((Field) userObject).getName() : option.longestName();
    }

    /**
     * Renders an option's value, masking anything that looks like a credential — {@code -oa} carries an OTLP
     * authorization header, which must not end up in the console or in a pasted log.
     */
    private static String renderValue(CommandLine.Model.OptionSpec option) {
        Object value = option.getValue();
        if (value == null) {
            return "<none>";
        }
        String rendered = value.getClass().isArray() ? Arrays.deepToString(new Object[]{value}) : String.valueOf(value);
        if (SECRET_OPTION.matcher(optionName(option)).matches() && !rendered.isEmpty()) {
            return "<set, masked>";
        }
        return rendered.isEmpty() ? "<empty>" : rendered;
    }

    private static boolean isDefault(CommandLine.Model.OptionSpec option, String rendered) {
        String defaultValue = option.defaultValue();
        return defaultValue == null ? "<none>".equals(rendered) : defaultValue.equals(rendered)
                || (defaultValue.isEmpty() && "<empty>".equals(rendered));
    }

    private static String pad(String value, int width) {
        StringBuilder padded = new StringBuilder(value);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    public static void waitForUserInput(Logger log) {
        log.info("Press any key to start the example");
        try {
            while (System.in.available() == 0) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Builds and starts a shared {@link WheelTimer} sized for response-throttling in the examples.
     * Returns {@code null} when {@code throttling} is zero so callers can short-circuit and skip
     * the timer entirely.
     * <p>
     * Sizing rationale: tick is the throttling delay / 16 with a 1µs floor; wheel covers ~4× the
     * delay so {@code remainingRounds} stays 0; submission queue holds {@code clientsCount * 4}
     * in-flight schedules (one outstanding per session in a request/reply pattern, plus burst).
     */
    public static WheelTimer createThrottlingTimer(Duration throttling, int clientsCount, ExampleOptions options) {
        if (throttling.isZero()) {
            return null;
        }
        long delayNanos = throttling.toNanos();
        long tickNanos = Math.max(1_000L, delayNanos / 16);
        int wheelSize = nextPowerOfTwo(Math.max(64, (int) ((delayNanos / tickNanos) * 4)));
        int submissionQueueSize = nextPowerOfTwo(Math.max(64, clientsCount * 4));
        WheelTimer timer = new WheelTimer(tickNanos, wheelSize, submissionQueueSize, true);
        IdleStrategy idle = options.isLowLatency() ? BusySpinIdleStrategy.getInstance() : new BackoffIdleStrategy();
        ThreadFactory tf = r -> {
            Thread t = options.isCpuAffinity() ? new FastThreadLocalThreadWithAffinity(r) : new Thread(r);
            t.setName("throttling-timer");
            return t;
        };
        log.info("Starting throttling WheelTimer: tickNanos={}, wheelSize={}, submissionQueueSize={}, idle={}",
                tickNanos, wheelSize, submissionQueueSize, idle.getClass().getSimpleName());
        timer.startOwnThread(idle, tf);
        return timer;
    }

    public static void stopThrottlingTimer(WheelTimer timer) {
        if (timer != null) {
            timer.stop(Duration.ofSeconds(5));
        }
    }

    private static int nextPowerOfTwo(int v) {
        if (v <= 1) {
            return 1;
        }
        return Integer.highestOneBit(v - 1) << 1;
    }

    public static void shutdownInitiators(Collection<FixInitiator> initiators) {
        shutdownInitiators(initiators.toArray(initiators.toArray(new FixInitiator[0])));
    }

    public static void shutdownInitiators(FixInitiator... initiators) {
        List<Thread> threads = new ArrayList<>();
        for (FixInitiator client : initiators) {
            Thread t = new Thread(client::stop);
            threads.add(t);
            t.start();

        }
        threads.forEach(t -> {
            try {
                t.join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public FixEngineBuilder.FixEngineBuilderBuilder fixEngineBuilder(ExampleOptions options, boolean resetSequenceOnLogon,
                                                                     Function<FixSessionId, FixApplication> acceptorFixApplicationSupplier,
                                                                     Function<FixSessionId, FixApplication> initiatorFixApplicationSupplier,
                                                                     Function<FixSessionId, Map<FixApplicationSessionSettingDescriptor, String>> acceptorFixApplicationSessionSettings,
                                                                     Function<FixSessionId, Map<FixApplicationSessionSettingDescriptor, String>> initiatorFixApplicationSessionSettings) {

        FastThreadLocalThreadWithAffinity.nextPinnedCore.set(options.getCpuAffinityStartCpu());
        SimpleApplicationFactorySettings.SimpleApplicationFactorySettingsBuilder acceptorAppFactoryBuilder =
                SimpleApplicationFactorySettings.builder().instanceId(ACCEPTOR);
        MemorySessionsSettingsStoreSettings.MemorySessionsSettingsStoreSettingsBuilder acceptorSessionsSettingsBuilder =
                MemorySessionsSettingsStoreSettings.builder().instanceId(ACCEPTOR);
        for (int i = 1; i <= options.getClientsCount(); i++) {
            FixSessionId sid = makeFixSessionId("acceptor-session-" + i, ACCEPTOR, getInitiatorSenderCompId(i), "acceptor");
            acceptorAppFactoryBuilder.application(sid.getId(), acceptorFixApplicationSupplier.apply(sid));
            acceptorSessionsSettingsBuilder.fixSessionSetting(getFixSessionSetting(sid, FixSession.FixSessionType.ACCEPTOR, ACCEPTOR,
                    resetSequenceOnLogon, acceptorFixApplicationSessionSettings, options));
        }

        SimpleApplicationFactorySettings.SimpleApplicationFactorySettingsBuilder initiatorAppFactoryBuilder =
                SimpleApplicationFactorySettings.builder().instanceId(INITIATOR);
        MemorySessionsSettingsStoreSettings.MemorySessionsSettingsStoreSettingsBuilder initiatorSessionsSettingsBuilder =
                MemorySessionsSettingsStoreSettings.builder().instanceId(INITIATOR);
        for (int i = 1; i <= options.getClientsCount(); i++) {
            FixSessionId sid = makeFixSessionId(getInitiatorSessionId(i), getInitiatorSenderCompId(i), ACCEPTOR, "initiators");
            initiatorAppFactoryBuilder.application(sid.getId(), initiatorFixApplicationSupplier.apply(sid));
            initiatorSessionsSettingsBuilder.fixSessionSetting(getFixSessionSetting(sid, FixSession.FixSessionType.INITIATOR, INITIATOR,
                    resetSequenceOnLogon, initiatorFixApplicationSessionSettings, options));
        }

        FixEngineBuilder.FixEngineBuilderBuilder<?, ?> fixEngineBuilder = FixEngineBuilder.builder()
                .adminApiExporter(JmxAdminApiSettings.builder().instanceId("demo-fix-jmx-admin").build())
                .fixMessagesStore(getFixMessagesStoreSettings(options, ACCEPTOR, options.getClientsCount()))
                .fixMessagesStore(getFixMessagesStoreSettings(options, INITIATOR, 1))
                .fixMessagesLogger(getLoggerSettings(options, ACCEPTOR, options.getClientsCount()))
                .fixMessagesLogger(getLoggerSettings(options, INITIATOR, 1))
                .fixApplicationFactory(acceptorAppFactoryBuilder.build())
                .fixApplicationFactory(initiatorAppFactoryBuilder.build())
                .fixSessionsSettingsStore(acceptorSessionsSettingsBuilder.build())
                .fixSessionsSettingsStore(initiatorSessionsSettingsBuilder.build());

        if (options.isEnableMonitoring()) {
            fixEngineBuilder.fixSessionsPlugin(getMonitoringSettings(ACCEPTOR, options));
            fixEngineBuilder.fixSessionsPlugin(getMonitoringSettings(INITIATOR, options));
        }
        if (options.isEnableTracing()) {
            fixEngineBuilder.fixSessionsPlugin(getTracingSettings(ACCEPTOR, options));
            fixEngineBuilder.fixSessionsPlugin(getTracingSettings(INITIATOR, options));
        }
        return fixEngineBuilder.instanceId("demo-fix-engine");
    }

    public FixAcceptorBuilder.FixAcceptorBuilderBuilder<?, ?> getFixAcceptorBuilder(ExampleOptions options) {
        return FixAcceptorBuilder.builder()
                .instanceId(ACCEPTOR)
                .bindAddress(new InetSocketAddress("localhost", 7001))
                .fixSessionEventsListener(new FixAcceptor.FixSessionEventsListener() {
                    @Override
                    public void onFixSessionAccepted(FixSessionId fixSession) {
                        log.info("Fix session {} accepted", fixSession);
                    }

                    @Override
                    public void onFixSessionRejected(FixSessionId fixSession, Exception rejectionException) {
                        log.info("Fix session {} rejected", fixSession, rejectionException);
                    }
                })
                .messageExecutorSettings(MessageExecutorSettings.builder().idleStrategy(options.isLowLatency() ? BusySpinIdleStrategy::getInstance : WaitNotifyIdleStrategy::new).build())
                .targetFixSessionsSettingsStoreInstancesId(ACCEPTOR)
                .ioWorkersGroup(IOWorkersGroupSettings.builder()
                        .ioThreadGroup(IOWorkersGroupSettings.IOThreadGroup.builder()
                                .ioThreadCount(options.getClientsCount())
                                .selectStrategy(options.isLowLatency() ? new IdleStrategySelectStrategy(BusySpinIdleStrategy.getInstance()) : new WakeupSelectStrategy(10))
                                .threadFactory(options.isCpuAffinity() ? FastThreadLocalThreadWithAffinity::new : FastThreadLocalThread::new)
                                .build())
                        .build().newInstance());
    }

    public FixInitiatorBuilder.FixInitiatorBuilderBuilder<?, ?> getFixInitiatorBuilder(String sessionId, String senderCompId, ExampleOptions options) {
        return FixInitiatorBuilder.builder()
                .instanceId(senderCompId)
                .connectionRetry(Duration.ofSeconds(1))
                .fixSessionId(FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                        .id(sessionId)
                        .senderCompID(senderCompId)
                        .targetCompID(ACCEPTOR).build()))
                .connectAddress(new InetSocketAddress("localhost", 7001))
                .messageExecutorSettings(MessageExecutorSettings.builder().idleStrategy(options.isLowLatency() ? BusySpinIdleStrategy::getInstance : WaitNotifyIdleStrategy::new).build())
                .ioWorkersGroup(IOWorkersGroupSettings.builder()
                        .ioThreadGroup(IOWorkersGroupSettings.IOThreadGroup.builder()
                                .selectStrategy(options.isLowLatency() ? new IdleStrategySelectStrategy(BusySpinIdleStrategy.getInstance()) : new WakeupSelectStrategy(10))
                                .threadFactory(options.isCpuAffinity() ? FastThreadLocalThreadWithAffinity::new : FastThreadLocalThread::new)
                                .build())
                        .build().newInstance());
    }

    public FixSessionSettings getFixSessionSetting(FixSessionId sid, FixSession.FixSessionType sessionType, String targetInstancesId, boolean resetSequenceOnLogon,
                                                   Function<FixSessionId, Map<FixApplicationSessionSettingDescriptor, String>> fixApplicationSessionSettings, ExampleOptions options) {
        FixSessionSettings.FixSessionSettingsBuilder<?, ?> builder = FixSessionSettings.builder()
                .fixSessionId(sid)
                .fixSessionType(sessionType)
                .validationSettings(FixSessionSettings.ValidationSettings.builder()
                        .validateRequiredFields(true)
                        .allowUserDefinedFields(options.isEnableTracing()) // w3c trace are propagated using a field over range 5000 (user define field)
                        .build())
                .fixApplicationSessionSettings(fixApplicationSessionSettings.apply(sid))
                .fixMessageStoreInstanceId(targetInstancesId)
                .fixMessageLoggerInstanceId(targetInstancesId)
                .fixApplicationFactoryInstanceId(targetInstancesId)
                .fixApplicationInstanceId(sid.getId())
                .resetSeqNumOnLogon(resetSequenceOnLogon);
        if (options.isEnableMonitoring()) {
            builder.fixSessionPluginsInstanceId(FixSessionsMonitoringManager.class, "monitoring-" + targetInstancesId);
        }
        if (options.isEnableTracing()) {
            builder.fixSessionPluginsInstanceId(OtelTracing.class, "tracing-" + targetInstancesId);
        }
        return builder.build();
    }

    public FixMessagesLoggerSettings getLoggerSettings(ExampleOptions options, String instanceId, int asyncReaderThreadsCount) {
        FixMessagesLoggerSettings loggerSettings;
        switch (options.getFixMessageLoggerType()) {
            case VOID:
                loggerSettings = Slf4jMessagesLoggerSettings.builder()
                        .instanceId(instanceId)
                        .logOutgoing(false)
                        .logIncoming(false)
                        .build();
                break;
            case FILE:
                loggerSettings = FileMessagesLoggerSettings.builder()
                        .instanceId(instanceId)
                        .logDirectory("./target/logs/")
                        .compressFileTimeUnit(TimeUnit.MINUTES)
                        .compressFileValue(1)
                        .maxCompressedFiles(3)
                        .build();
                break;
            case OTLP:
                OtlpMessagesLoggerSettings.OtlpMessagesLoggerSettingsBuilder<?, ?> otlpLogger =
                        OtlpMessagesLoggerSettings.builder()
                                .resourceAttribute(FixMonitoringConstants.OTLP_SERVICE_NAME, getClass().getSimpleName())
                                .resourceAttribute(FixMonitoringConstants.OTLP_SERVICE_INSTANCE_ID, instanceId)
                                .resourceAttribute(FixMonitoringConstants.OTLP_SERVICE_VERSION, FixEngineVersion.getInstance().getVersion().toString())
                                .instanceId(instanceId)
                                .otlpEndpointUrl(options.getOtlpEndpointUrl())
                                .httpSenderFactory(otlpHttpSenderFactory(options))
                                .httpSenderSettings(otlpHttpSenderSettings(options))
                                .fixMessageFieldsDelimiter('|');
                if (options.getOtlpTransport() == ExampleOptions.OtlpTransport.GRPC) {
                    // The logs path takes its gRPC client directly, unlike tracing below, because these
                    // settings are ours rather than OpenTelemetry's.
                    otlpLogger.transport(OtlpMessagesLoggerSettings.OtlpTransport.GRPC)
                            .grpcSenderFactory(JettyGrpcSender::new);
                }
                loggerSettings = otlpLogger.build();
                break;
            case SLF4J:
                loggerSettings = Slf4jMessagesLoggerSettings.builder()
                        .instanceId(instanceId)
                        .messageFieldsDelimiter('|')
                        .build();
                break;
            default:
                throw new IllegalArgumentException("Unsupported logger type: " + options.getFixMessageLoggerType());
        }

        if (!options.isAsyncMessagesLogger()) {
            return loggerSettings;
        }
        return AsyncMessagesLoggerSettings.builder()
                .wrappedLoggerSettings(loggerSettings)
                .asyncStoreSettings(getAsyncStoreSettings("Fix logs").toBuilder().readerThreadsCount(asyncReaderThreadsCount).build())
                .useDirectByteBuffer(true)
                .build();
    }

    public FixMessagesStoreSettings getFixMessagesStoreSettings(ExampleOptions options, String instanceId, int asyncReaderThreadsCount) {
        FixMessagesStoreSettings settings;
        switch (options.getFixMessageStoreType()) {
            case VOID:
                settings = MemoryMessageStoreSettings.builder()
                        .instanceId(instanceId)
                        .maxEntriesInMemory(0).build();
                break;
            case MEMORY:
                settings = MemoryMessageStoreSettings.builder()
                        .instanceId(instanceId)
                        .maxEntriesInMemory(8 * 1024).build();
                break;
            case FILE:
                settings = FileMessageStoreSettings.builder()
                        .instanceId(instanceId)
                        .storageDirectoryPath("./target/messages-store")
                        .blocksCount(FileMessageStoreSettings.calculateBlocksCount(FileMessageStoreSettings.DEFAULT_BLOCKS_SIZE, 128 * 1024, 256))
                        .build();
                break;
            case JDBC:
                settings = JdbcMessageStoreSettings.builder()
                        .instanceId(instanceId)
                        .dataSource(EmbeddedDbSetup.setupDatabase())
                        .build();
                break;
            default:
                throw new IllegalArgumentException("Unknown fix message store type: " + options.getFixMessageStoreType());
        }

        if (!options.isAsyncMessagesStore()) {
            return settings;
        }
        return AsyncMessagesStoreSettings.builder()
                .wrappedFixMessagesStoreSettings(settings)
                .asyncStoreSettings(getAsyncStoreSettings("Fix messages").toBuilder().readerThreadsCount(asyncReaderThreadsCount).build())
                .useDirectByteBuffer(true)
                .build();
    }

    private AsyncStoreSettings getAsyncStoreSettings(String prefix) {
        return AsyncStoreSettings.builder()
                .asyncQueueDirectory("./target/async-queue/")
                .rollCycleProvider(sid -> RollCycles.FAST_HOURLY)
                .eventsBatching(1024)
                .queueSizeThresholdsSettings(AsyncStoreSettings.QueueSizeThresholdsSettings.builder()
                        .normal(100)
                        .warn(2000)
                        .critical(4000)
                        .build())
                .queueSizeThresholdsListener(new QueueSizeThresholdsListener.LoggingQueueSizeThresholdsListener(prefix))
                .batchingFlushInterval(Duration.ofMillis(250))
                .build();
    }

    private FixSessionsPluginSettings<OtelTracing> getTracingSettings(String serviceName, ExampleOptions options) {
        // Tracing picks its HTTP client by system property, because OpenTelemetry builds its sender
        // through a ServiceLoader and leaves nowhere to pass a factory in - unlike the logs and metrics
        // settings above, which take one directly. Set here rather than in the launch scripts so that
        // running an example from an IDE behaves the same as running it from its script; a -D on the
        // command line still wins, because an already-set property is left alone.
        boolean grpc = options.getOtlpTransport() == ExampleOptions.OtlpTransport.GRPC;
        if (grpc) {
            if (System.getProperty(StaffixGrpcSenderProvider.FACTORY_PROPERTY) == null) {
                System.setProperty(StaffixGrpcSenderProvider.FACTORY_PROPERTY,
                        JettyOtlpGrpcSenderFactory.class.getName());
            }
        } else if (System.getProperty(StaffixHttpSenderProvider.FACTORY_PROPERTY) == null) {
            System.setProperty(StaffixHttpSenderProvider.FACTORY_PROPERTY,
                    JettyOtlpHttpSenderFactory.class.getName());
        }
        return OtelTracingSettings.builder()
                .otlpEndpointType(grpc
                        ? OtelTracingSettings.OtlpEndpointType.GRPC
                        : OtelTracingSettings.OtlpEndpointType.HTTP)
                .instanceId("tracing-" + serviceName)
                .w3cTracePropagationEnabled(true)
                .resourceAttribute(FixMonitoringConstants.OTLP_SERVICE_NAME, getClass().getSimpleName())
                .resourceAttribute(FixMonitoringConstants.OTLP_SERVICE_INSTANCE_ID, serviceName)
                .resourceAttribute(FixMonitoringConstants.OTLP_SERVICE_VERSION, FixEngineVersion.getInstance().getVersion().toString())
                .otlpEndpointUrl(options.getOtlpEndpointUrl())
                .headers(options.getMonitoringAuthHeader() != null ? Map.of("Authorization", "Basic " + options.getMonitoringAuthHeader()) : null)
                .build();
    }

    private FixSessionsPluginSettings<?> getMonitoringSettings(String serviceName, ExampleOptions options) {
        OtlpMicrometerMonitoringManagerSettings.OtlpMicrometerMonitoringManagerSettingsBuilder<?, ?> builder = OtlpMicrometerMonitoringManagerSettings.builder()
                .instanceId("monitoring-" + serviceName)
                // Metrics stay on OTLP/HTTP whatever -otr says: micrometer's OTLP registry has no gRPC
                // sender to plug into.
                .httpSenderFactory(otlpHttpSenderFactory(options))
                .httpSenderSettings(otlpHttpSenderSettings(options))
                .resourceAttribute(FixMonitoringConstants.OTLP_SERVICE_NAME, getClass().getSimpleName())
                .resourceAttribute(FixMonitoringConstants.OTLP_SERVICE_INSTANCE_ID, serviceName)
                .resourceAttribute(FixMonitoringConstants.OTLP_SERVICE_VERSION, FixEngineVersion.getInstance().getVersion().toString())
                .step(Duration.ofSeconds(1))
                .baseTimeUnit(TimeUnit.MICROSECONDS)
                .otlpEndpointUrl(options.getOtlpEndpointUrl())
                .clockOffsetEnabled(true)
                .rttLatencyEnabled(true)
                .startedMeterRegistryConsumer(registry -> {
                    new JvmMemoryMetrics().bindTo(registry);
                    new ProcessorMetrics().bindTo(registry);
                    new JvmThreadMetrics().bindTo(registry);
                });

        if (options.getMonitoringAuthHeader() != null && !options.getMonitoringAuthHeader().trim().isEmpty()) {
            builder.header("Authorization", "Basic " + options.getMonitoringAuthHeader());
        }
        FixSessionsPluginSettings<FixSessionsMonitoringManager> monitoringSettings = builder.build();
        FixSessionsPluginSettings<?> pluginSettings = monitoringSettings;
        if (options.isAsyncMonitoring()) {
            // Defer the monitoring plugin's fire-and-forget callbacks onto the shared async consumer pool,
            // off the latency-critical message path, via the dedicated async-plugin module.
            pluginSettings = AsyncFixSessionsPluginSettings.builder()
                    .delegateSettings(monitoringSettings)
                    .build();
        }
        if (options.getThrottleCap() > 0) {
            // Cap the number of monitoring callbacks (both incoming and outgoing) that reach the delegate per
            // window, dropping the overflow on the hot path. Throttling is the outermost wrapper so overflow is
            // shed before any async-enqueue cost. Transparent: it reports the monitoring plugin's instanceId and
            // matches its plugin class, so the session's fixSessionPluginsInstanceId reference keeps resolving.
            log.info("Throttling {} monitoring callbacks to {} incoming and {} outgoing per {}ms", serviceName,
                    options.getThrottleCap(), options.getThrottleCap(), options.getThrottleWindowMillis());
            pluginSettings = ThrottlingFixSessionsPluginSettings.builder()
                    .delegateSettings(pluginSettings)
                    .maxReceivedMessages(options.getThrottleCap())
                    .maxSentMessages(options.getThrottleCap())
                    .window(Duration.ofMillis(options.getThrottleWindowMillis()))
                    .build();
        }
        return pluginSettings;
    }

    private static final class FastThreadLocalThreadWithAffinity extends FastThreadLocalThread {

        static AtomicInteger nextPinnedCore = new AtomicInteger(0);

        public FastThreadLocalThreadWithAffinity(Runnable target) {
            super(target);
        }

        @Override
        public void run() {
            int cpuCore = nextPinnedCore.getAndIncrement();
            log.info("Setting IO thread {} affinity to core {}", getName(), cpuCore);
            Affinity.setAffinity(cpuCore);
            super.run();
        }
    }
}