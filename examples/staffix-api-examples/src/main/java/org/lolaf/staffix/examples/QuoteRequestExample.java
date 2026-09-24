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
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.fix44.encoders.QuoteEncoder;
import org.lolaf.staffix.fix44.encoders.QuoteRequestEncoder;
import org.lolaf.staffix.fix44.fields.*;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import picocli.CommandLine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.lolaf.staffix.api.codec.FixFieldsDecoderMapper.ObjectInstanceStrategy.CACHED;
import static org.lolaf.staffix.api.codec.FixFieldsDecoderMapper.ObjectInstanceStrategy.THREAD_LOCAL;

/**
 * Run with -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints --enable-native-access=ALL-UNNAMED --add-opens java.base/jdk.internal.ref=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED
 */
@Slf4j
@CommandLine.Command(name = "QuoteRequestExample", mixinStandardHelpOptions = true, version = "1.0",
        description = "Quotes request/response streaming example", showDefaultValues = true)
public class QuoteRequestExample extends FixExamplesBase implements Callable<Integer> {

    @CommandLine.Option(names = "-w", description = "Wait delay in micros to respond to a quote request", defaultValue = "0")
    private long waitDelayInMicros;

    @CommandLine.ArgGroup(exclusive = false)
    private ExampleOptions exampleOptions = new ExampleOptions();

    @CommandLine.ArgGroup(exclusive = false)
    private Profiling.ProfilingOptions profilingOptions = new Profiling.ProfilingOptions();

    public static void main(String... args) {
        System.exit(new CommandLine(new QuoteRequestExample()).execute(args));
    }

    @Override
    public Integer call() {
        logConfiguration(log, this);
        Duration exampleDuration = Duration.ofSeconds(exampleOptions.getDuration());
        Duration quotesThrottling = Duration.ofNanos(waitDelayInMicros * 1000);

        waitForUserInput(log);

        WheelTimer throttlingTimer = createThrottlingTimer(quotesThrottling, exampleOptions.getClientsCount(), exampleOptions);

        FixApplication acceptorApp = new FixAcceptorApplication();
        FixEngine fixEngine = fixEngineBuilder(exampleOptions, true,
                sid -> acceptorApp,
                sid -> new FixInitiatorApplication(quotesThrottling, throttlingTimer),
                sid -> Collections.emptyMap(),
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

        Profiling.startProfilingIfNeeded(profilingOptions, QuoteRequestExample.class);
        registerShutdownHook(initiators, throttlingTimer, fixAcceptor);
        LockSupport.parkNanos(exampleDuration.toNanos());
        shutdown(initiators, throttlingTimer, fixAcceptor);
        return 0;
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
        public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
            log.info("Logon acceptor {}", fixSession.getFixSessionId());
        }

        @Override
        public void onLogout(FixSession fixSession, String message, DecodedFixMessage logoutMessage) {
            log.info("Logout acceptor {} : {}", fixSession.getFixSessionId(), message);
        }

        @Setter
        private static class QuoteRequestDecoder implements FixMessageDecoder {

            private final QuoteEncoder quoteEncoder;
            private String symbol;
            private String quoteReqId;
            private long userDefinedField;

            QuoteRequestDecoder(FixSession fixSession) {
                quoteEncoder = fixSession.newEncoder(QuoteEncoder.class).asReusable();
            }

            @Override
            public MessageType getMessageType() {
                return MessageTypes.QuoteRequest;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
                FixField userField = fieldsRegistry.addUserDefinedField(5001, FieldType.INT, FieldLocation.BODY);
                // sample code for adding a user defined field
                fixFieldsDecoderMapper
                        .mapLongField(userField, this::setUserDefinedField, -1)
                        .mapStringField(QuoteReqID.get(), this::setQuoteReqId, null, THREAD_LOCAL)
                        .forGroup(NoRelatedSym.get())
                        .withFieldsAutoResetDisabled()
                        .mapStringField(Symbol.get(), this::setSymbol, null, CACHED);
            }

            @Override
            public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
                // since we are in the fix session IO thread, we can reuse the quoteEncoder instance
                // as the message will be sent directly to the socket and not enqueued in the IO thread tasks queue
                fixSession.send(quoteEncoder
                        .begin()
                        .setQuoteReqID(quoteReqId)
                        .addLong(QuoteID.get(), System.nanoTime())
                        .setSymbol(symbol)
                        .setSide(Side.SideValues.BUY)
                        .setBidPx(1234.1234d)
                        .setBidSize(1234567d), null);
            }
        }
    }

    private static final class FixInitiatorApplication implements FixApplication {

        private final Duration quotesThrottling;
        private final WheelTimer throttlingTimer;
        private final MutableTimeout reusableTimeout;
        private final Runnable sendQuoteRequestInIOThreadRunnable = this::sendQuoteRequestInIOThread;
        private final Runnable sendQuoteRequestNowRunnable = this::sendQuoteRequestNow;
        private FixSession fixSession;
        private QuoteRequestEncoder quoteRequestEncoder;
        private FixField userField;

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
                sendQuoteRequestNow();
                return;
            }
            // Hop back to the FIX IO thread after the delay so the reusable encoder
            // stays single-threaded.
            Timeout t = throttlingTimer.schedule(reusableTimeout, sendQuoteRequestInIOThreadRunnable,
                    quotesThrottling.toNanos(), TimeUnit.NANOSECONDS);
            if (t.isRejected()) {
                log.warn("Throttling submission queue full for session {} — dropping send", fixSession.getFixSessionId());
            }
        }

        private void sendQuoteRequestInIOThread() {
            fixSession.processTask(sendQuoteRequestNowRunnable);
        }

        private void sendQuoteRequestNow() {
            quoteRequestEncoder.begin()
                    .setQuoteReqID("testQuoteRequest")
                    .addLong(userField, System.nanoTime())
                    .addNoRelatedSym(1).setSymbol("FOO/BAR");
            fixSession.send(quoteRequestEncoder, null);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
            this.fixSession = fixSession;
            this.quoteRequestEncoder = fixSession.newEncoder(QuoteRequestEncoder.class).asReusable();
            this.userField = fixSession.getFieldsRegistry().addUserDefinedField(5001, FieldType.INT, FieldLocation.BODY);
            encodedMessagesTypes.add(MessageTypes.QuoteRequest);
            return List.of(new QuoteDecoder(this::scheduleSendQuoteRequest));
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
        @RequiredArgsConstructor
        private static class QuoteDecoder implements FixMessageDecoder {

            private final Runnable scheduleSendQuoteRequest;
            private long nextLogTimestamp = System.currentTimeMillis();
            private int sentQuotes;
            private String symbol;
            private String quoteReqId;
            private long quoteId;
            private Side.SideValues side;
            private double bidPx;
            private double bidSize;

            @Override
            public MessageType getMessageType() {
                return MessageTypes.Quote;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
                fixFieldsDecoderMapper.mapStringField(QuoteReqID.get(), this::setQuoteReqId, null, THREAD_LOCAL)
                        .mapLongField(QuoteID.get(), this::setQuoteId, 0L)
                        .mapCharValuesEnumField(Side.get(), this::setSide, null)
                        .mapDoubleField(BidPx.get(), this::setBidPx, 0D)
                        .mapDoubleField(BidSize.get(), this::setBidSize, 0D)
                        .mapStringField(Symbol.get(), this::setSymbol, null, CACHED);
            }

            @Override
            public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
                scheduleSendQuoteRequest.run();
                sentQuotes++;
                long now = System.currentTimeMillis();
                if (now > nextLogTimestamp) {
                    log.info("Sent {} quote/s requests on FIX session {}", sentQuotes / 5, fixSession.getFixSessionId());
                    sentQuotes = 0;
                    nextLogTimestamp = now + TimeUnit.SECONDS.toMillis(5);
                }
            }
        }
    }
}