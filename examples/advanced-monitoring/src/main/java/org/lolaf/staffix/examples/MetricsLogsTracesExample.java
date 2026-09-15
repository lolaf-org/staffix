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

import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.tracing.otlp.FixTracer;
import org.lolaf.staffix.tracing.otlp.VoidFixTracer;
import org.lolaf.ringos.timer.MutableTimeout;
import org.lolaf.ringos.timer.Timeout;
import org.lolaf.ringos.timer.WheelTimer;
import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringContext;
import org.lolaf.staffix.api.monitoring.Timer;
import org.lolaf.staffix.api.monitoring.VoidFixSessionsMonitoringContext;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.fix44.encoders.QuoteEncoder;
import org.lolaf.staffix.fix44.encoders.QuoteRequestEncoder;
import org.lolaf.staffix.fix44.fields.*;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.jvmwarmup.JvmWarmup;
import org.lolaf.staffix.jvmwarmup.JvmWarmupOptions;
import picocli.CommandLine;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/**
 * Run with -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints --enable-native-access=ALL-UNNAMED --add-opens java.base/jdk.internal.ref=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED
 */
@Slf4j
@CommandLine.Command(name = "MetricsLogsTracesExample", mixinStandardHelpOptions = true, version = "1.0",
        description = "Example showcasing full monitoring capabilities", showDefaultValues = true)
public class MetricsLogsTracesExample extends FixExamplesBase implements Callable<Integer> {

    @CommandLine.Option(names = "-w", description = "Wait delay in micros to respond to a quote request", defaultValue = "0")
    private long waitDelayInMicros;

    @CommandLine.Option(names = "-jw", description = "Run JVM warmup before starting the example", defaultValue = "false")
    private boolean jvmWarmup;

    @CommandLine.ArgGroup(exclusive = false)
    private ExampleOptions exampleOptions = new ExampleOptions();

    @CommandLine.ArgGroup(exclusive = false)
    private Profiling.ProfilingOptions profilingOptions = new Profiling.ProfilingOptions();

    public static void main(String... args) {
        System.exit(new CommandLine(new MetricsLogsTracesExample()).execute(args));
    }

    @Override
    public Integer call() {
        logConfiguration(log, this);
        Duration exampleDuration = Duration.ofSeconds(exampleOptions.getDuration());
        Duration quotesThrottling = Duration.ofNanos(waitDelayInMicros * 1000);
        log.info("Quotes will be published for {} with a throttling of {}", exampleDuration, quotesThrottling);

        waitForUserInput(log);

        WheelTimer throttlingTimer = createThrottlingTimer(quotesThrottling, exampleOptions.getClientsCount(), exampleOptions);

        FixApplication acceptorApp = new FixAcceptorApplication();
        FixEngineBuilder fixEngineBuilder = fixEngineBuilder(exampleOptions, true,
                sid -> acceptorApp,
                sid -> new MetricsLogsTracesExample.FixInitiatorApplication(quotesThrottling, throttlingTimer),
                sid -> Collections.emptyMap(),
                sid -> Collections.emptyMap())
                .build();

        if (jvmWarmup) {
            log.info("Starting JVM warmup for 2 mins");
            JvmWarmup.run(JvmWarmupOptions.builder()
                            .lowLatency(exampleOptions.isLowLatency())
                            .warmupDuration(Duration.ofMinutes(2))
                            .throttling(quotesThrottling.isZero() ? Duration.ofMillis(10) : quotesThrottling)
                            .fixEngineBuilder(fixEngineBuilder).build())
                    .thenAccept(result -> log.info("JVM warmup complete: sent={}, received={}, duration={}",
                            result.getMessagesSent(), result.getMessagesReceived(), result.getActualDuration()))
                    .join();
        }

        FixEngine fixEngine = fixEngineBuilder.instance().start();
        FixAcceptor fixAcceptor = fixEngine.newAcceptor(getFixAcceptorBuilder(exampleOptions).build()).start();
        List<FixInitiator> initiators = new ArrayList<>();
        for (int i = 1; i <= exampleOptions.getClientsCount(); i++) {
            FixInitiator initiator = fixEngine.newInitiator(getFixInitiatorBuilder(getInitiatorSessionId(i), getInitiatorSenderCompId(i), exampleOptions)
                    .build()).start();
            initiators.add(initiator);
        }

        Profiling.startProfilingIfNeeded(profilingOptions, MetricsLogsTracesExample.class);

        LockSupport.parkNanos(exampleDuration.toNanos());
        log.info("Shutting down {} initiators and 1 acceptor", initiators.size());
        stopThrottlingTimer(throttlingTimer);
        shutdownInitiators(initiators);
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
        fixAcceptor.stop();
        return 0;
    }

    @Override
    public FixSessionSettings getFixSessionSetting(FixSessionId sid, FixSession.FixSessionType sessionType, String targetInstancesId, boolean resetSequenceOnLogon, Function<FixSessionId, Map<FixApplicationSessionSettingDescriptor, String>> fixApplicationSessionSettings, ExampleOptions options) {
        FixSessionSettings settings = super.getFixSessionSetting(sid, sessionType, targetInstancesId, resetSequenceOnLogon, fixApplicationSessionSettings, options);
        return settings.toBuilder()
                .sendingTimeAccuracy(TimeUnit.NANOSECONDS)
                .validationSettings(FixSessionSettings.ValidationSettings.builder()
                        .allowUserDefinedFields(true) // important w3c trace are propagated using a field over range 5000 (user define field)
                        .build())
                .rttMeasurementSettings(FixSessionSettings.RttMeasurementSettings.builder()
                        .probeInterval(Duration.ofSeconds(1))
                        .build())
                .build();
    }

    private static final class FixAcceptorApplication implements FixApplication {

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
            encodedMessagesTypes.add(MessageTypes.Quote);
            return List.of(new QuoteRequestDecoder(fixSession));
        }

        @Override
        public void onSessionCreated(FixSession fixSession, FieldsRegistry fieldsRegistry, MessageTypeRegistry messageTypeRegistry, List<FixMessageDecoder> decoders) {
            Timer customTimer = fixSession.getPluginContext(FixSessionsMonitoringContext.class)
                    .orElse(VoidFixSessionsMonitoringContext.getInstance())
                    .getTimer("quote.encoding", "Quote encoding and sending", Map.of("test-tag", "test-tag-value"));
            FixTracer tracer = fixSession.getPluginContext(FixTracer.class).orElse(VoidFixTracer.getInstance());

            decoders.stream().filter(QuoteRequestDecoder.class::isInstance)
                    .map(d -> (QuoteRequestDecoder) d)
                    .findFirst().orElseThrow().setMonitoringComponents(customTimer, tracer);
        }

        @Override
        public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
            log.info("Logon acceptor {}", fixSession.getFixSessionId());
        }

        @Override
        public void onLogout(FixSession fixSession, String message, DecodedFixMessage logoutMessage) {
            log.info("Logout acceptor {} : {}", fixSession.getFixSessionId(), message);
        }

        @Setter
        @RequiredArgsConstructor
        private static class QuoteRequestDecoder implements FixMessageDecoder {

            private final QuoteEncoder quoteEncoder;
            private Timer quoteEncodingAndSendingTimer;
            private FixTracer tracer;
            private String symbol;
            private String quoteReqId;

            QuoteRequestDecoder(FixSession fixSession) {
                quoteEncoder = fixSession.newEncoder(QuoteEncoder.class).asReusable();
            }

            void setMonitoringComponents(Timer quoteEncodingAndSendingTimer, FixTracer tracer) {
                this.quoteEncodingAndSendingTimer = quoteEncodingAndSendingTimer;
                this.tracer = tracer;
            }

            @Override
            public MessageType getMessageType() {
                return MessageTypes.QuoteRequest;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
                fixFieldsDecoderMapper
                        .mapStringField(QuoteReqID.get(), this::setQuoteReqId, null, FixFieldsDecoderMapper.ObjectInstanceStrategy.THREAD_LOCAL)
                        .forGroup(NoRelatedSym.get())
                        .withFieldsAutoResetDisabled()
                        .mapStringField(Symbol.get(), this::setSymbol, null, FixFieldsDecoderMapper.ObjectInstanceStrategy.CACHED);
            }

            @Override
            public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
                // since we are in the fix session IO thread, we can reuse the quoteEncoder instance
                // as the message will be sent directly to the socket and not enqueued in the IO thread tasks queue
                quoteEncodingAndSendingTimer.start();
                Span encoding = tracer.spanBuilder("quote-response").startSpan().addEvent("encoding");
                quoteEncoder.begin()
                        .setQuoteReqID(quoteReqId)
                        .addLong(QuoteID.get(), System.nanoTime())
                        .setSymbol(symbol)
                        .setSide(Side.SideValues.BUY)
                        .setBidPx(1234.1234d)
                        .setBidSize(1234567d);
                encoding.addEvent("sending");
                fixSession.send(quoteEncoder, null);
                encoding.addEvent("sent").end();
                quoteEncodingAndSendingTimer.stop();
            }
        }
    }

    private static final class FixInitiatorApplication implements FixApplication {

        private final Duration quotesThrottling;
        private final WheelTimer throttlingTimer;
        private final MutableTimeout reusableTimeout;
        private final Runnable sendQuoteRequestInIOThreadRunnable = this::sendQuoteRequestInIOThread;
        private final Runnable sendQuoteRequestRunnable = this::sendQuoteRequest;
        private FixSession fixSession;
        private Timer quoteRequestEncodingAndSendingTimer;
        private FixTracer tracer;
        private QuoteRequestEncoder quoteRequestEncoder;
        private long nextLogTimestamp = System.currentTimeMillis();
        private int sentQuotes;

        public FixInitiatorApplication(Duration quotesThrottling, WheelTimer throttlingTimer) {
            this.quotesThrottling = quotesThrottling;
            this.throttlingTimer = throttlingTimer;
            this.reusableTimeout = throttlingTimer != null ? throttlingTimer.newReusableTimeout() : null;
        }

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        private void scheduleSendQuoteRequest() {
            if (quotesThrottling.isZero()) {
                sendQuoteRequest();
                return;
            }
            tracer.currentSpan().addEvent("quote request scheduling");
            Timeout t = throttlingTimer.schedule(reusableTimeout, sendQuoteRequestInIOThreadRunnable,
                    quotesThrottling.toNanos(), TimeUnit.NANOSECONDS);
            if (t.isRejected()) {
                log.warn("Throttling submission queue full for session {} - dropping send", fixSession.getFixSessionId());
            }
            tracer.currentSpan().addEvent("quote request scheduling done");
        }

        private void sendQuoteRequestInIOThread() {
            fixSession.processTask(sendQuoteRequestRunnable);
        }

        private void sendQuoteRequest() {
            quoteRequestEncodingAndSendingTimer.start();
            quoteRequestEncoder.begin()
                    .setQuoteReqID("testQuoteRequest")
                    .addNoRelatedSym(1).setSymbol("FOO/BAR");
            fixSession.send(quoteRequestEncoder, null);
            quoteRequestEncodingAndSendingTimer.stop();
            sentQuotes++;
            long now = System.currentTimeMillis();
            if (now > nextLogTimestamp) {
                log.info("Sent {} quote/s requests on FIX session {}", sentQuotes / 5, fixSession.getFixSessionId());
                sentQuotes = 0;
                nextLogTimestamp = now + TimeUnit.SECONDS.toMillis(5);
            }
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
            this.fixSession = fixSession;
            this.quoteRequestEncoder = fixSession.newEncoder(QuoteRequestEncoder.class).asReusable();
            encodedMessagesTypes.add(MessageTypes.QuoteRequest);
            return List.of(new QuoteDecoder(this::scheduleSendQuoteRequest));
        }

        @Override
        public void onSessionCreated(FixSession fixSession, FieldsRegistry fieldsRegistry, MessageTypeRegistry messageTypeRegistry, List<FixMessageDecoder> decoders) {
            quoteRequestEncodingAndSendingTimer = fixSession.getPluginContext(FixSessionsMonitoringContext.class)
                    .orElse(VoidFixSessionsMonitoringContext.getInstance())
                    .getTimer("quote.request.encoding", "Quote requests encoding and sending", Map.of("test-tag", "test-tag-value"));
            tracer = fixSession.getPluginContext(FixTracer.class).orElse(VoidFixTracer.getInstance());

            decoders.stream().filter(FixInitiatorApplication.QuoteDecoder.class::isInstance)
                    .map(d -> (FixInitiatorApplication.QuoteDecoder) d)
                    .findFirst().orElseThrow().setMonitoringComponents(tracer);
        }

        @Override
        public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
            log.info("Logon initiator {}", fixSession.getFixSessionId());
            scheduleSendQuoteRequest();
        }

        @Override
        public void onLogout(FixSession fixSession, String message, DecodedFixMessage logoutMessage) {
            log.info("Logout initiator {} : {}", fixSession.getFixSessionId(), message);
        }

        @Setter
        private static class QuoteDecoder implements FixMessageDecoder {

            private final Runnable scheduleSendQuoteRequest;
            private FixTracer tracer;
            private String symbol;
            private String quoteReqId;
            private long quoteId;
            private Side.SideValues side;
            private double bidPx;
            private double bidSize;

            QuoteDecoder(Runnable scheduleSendQuoteRequest) {
                this.scheduleSendQuoteRequest = scheduleSendQuoteRequest;
            }

            void setMonitoringComponents(FixTracer tracer) {
                this.tracer = tracer;
            }

            @Override
            public MessageType getMessageType() {
                return MessageTypes.Quote;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
                fixFieldsDecoderMapper.mapStringField(QuoteReqID.get(), this::setQuoteReqId, null, FixFieldsDecoderMapper.ObjectInstanceStrategy.THREAD_LOCAL)
                        .mapLongField(QuoteID.get(), this::setQuoteId, 0L)
                        .mapCharValuesEnumField(Side.get(), this::setSide, null)
                        .mapDoubleField(BidPx.get(), this::setBidPx, 0D)
                        .mapDoubleField(BidSize.get(), this::setBidSize, 0D)
                        .mapStringField(Symbol.get(), this::setSymbol, null, FixFieldsDecoderMapper.ObjectInstanceStrategy.CACHED);
            }

            @Override
            public void onBegin(long localReceiveTimeInNanos, UTCTime localReceiveTime) {
                tracer.currentSpan().addEvent("quote decoding start");
            }

            @Override
            public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
                tracer.currentSpan().addEvent("quote decoded");
                scheduleSendQuoteRequest.run();
            }
        }
    }
}