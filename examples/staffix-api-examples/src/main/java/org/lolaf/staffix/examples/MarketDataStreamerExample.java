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

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.ringos.clib.CLibraryApi;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.codec.FixMessageEncoderFactory;
import org.lolaf.staffix.api.codec.FixMessageEncodersPool;
import org.lolaf.staffix.api.executor.MessageExecutor;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.fix44.encoders.MarketDataRequestEncoder;
import org.lolaf.staffix.fix44.encoders.MarketDataSnapshotFullRefreshEncoder;
import org.lolaf.staffix.fix44.encoders.group.MarketDataSnapshotFullRefreshNoMDEntriesEncoder;
import org.lolaf.staffix.fix44.fields.*;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import picocli.CommandLine;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

import static org.lolaf.staffix.api.codec.FixFieldsDecoderMapper.ObjectInstanceStrategy.CACHED;

/**
 * Run with -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints --enable-native-access=ALL-UNNAMED --add-opens java.base/jdk.internal.ref=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED
 */
@Slf4j
@CommandLine.Command(name = "MarketDataStreamerExample", mixinStandardHelpOptions = true, version = "1.0",
        description = "MarketDataSnapshotFullRefresh streaming example", showDefaultValues = true)
public class MarketDataStreamerExample extends FixExamplesBase implements Callable<Integer> {

    @CommandLine.Option(names = "-p", description = "Interval in micros to publish quotes", defaultValue = "50")
    private long publishIntervalInMicros;

    @CommandLine.Option(names = "-i", description = "Comma separated list of instruments to publish", defaultValue = "XAU/USD, XAG/USD")
    private String instruments;

    @CommandLine.ArgGroup(exclusive = false)
    private ExampleOptions exampleOptions = new ExampleOptions();

    @CommandLine.ArgGroup(exclusive = false)
    private Profiling.ProfilingOptions profilingOptions = new Profiling.ProfilingOptions();

    public static void main(String... args) {
        System.exit(new CommandLine(new MarketDataStreamerExample()).execute(args));
    }

    @Override
    public Integer call() {
        logConfiguration(log, this);
        Duration quotesPublishingInterval = Duration.ofNanos(publishIntervalInMicros * 1000);
        Duration exampleDuration = Duration.ofSeconds(exampleOptions.getDuration());
        Set<String> instrumentsSet = Arrays.stream(instruments.split(",")).map(String::trim).collect(Collectors.toSet());

        waitForUserInput(log);

        FixApplication acceptorApp = new FixAcceptorApplication(quotesPublishingInterval);
        FixApplication initiatorApp = new FixInitiatorApplication(instrumentsSet);
        FixEngine fixEngine = fixEngineBuilder(exampleOptions, true,
                sid -> acceptorApp,
                sid -> initiatorApp,
                sid -> Collections.emptyMap(),
                sid -> Collections.emptyMap())
                .build()
                .instance()
                .start();

        FixAcceptor fixAcceptor = fixEngine.newAcceptor(getFixAcceptorBuilder(exampleOptions)
                .ioSettings(IOSettings.builder()
                        .writeHighWatermark(16 * 1024)
                        .writeLowWatermark(1024).build())
                .build()).start();

        List<FixInitiator> initiators = new ArrayList<>();
        for (int i = 1; i <= exampleOptions.getClientsCount(); i++) {
            FixInitiator initiator = fixEngine.newInitiator(getFixInitiatorBuilder(getInitiatorSessionId(i), getInitiatorSenderCompId(i), exampleOptions)
                    .ioSettings(IOSettings.builder().readBufferSize(16 * 1024).build())
                    .build()).start();
            initiators.add(initiator);
        }

        Profiling.startProfilingIfNeeded(profilingOptions, QuoteRequestExample.class);
        registerShutdownHook(initiators, null, fixAcceptor);
        LockSupport.parkNanos(exampleDuration.toNanos());
        shutdown(initiators, null, fixAcceptor);
        return 0;
    }

    @RequiredArgsConstructor
    private static class MarketDataStreamer {

        private final Map<RegistrationKey, MDRegistration> activeRegistrations = new HashMap<>();
        private final Map<String, MDSymbolStreamer> mdStreamers = new HashMap<>();
        private final Duration quotesPublishingInterval;

        public synchronized void register(FixSession fixSession, Consumer<MarketDataSnapshotFullRefreshEncoder> quoteUpdates, String mdReqId, String symbol) {
            MDRegistration reg = activeRegistrations.computeIfAbsent(new RegistrationKey(fixSession.getFixSessionId(), mdReqId),
                    r -> new MDRegistration(fixSession.getFixSessionId(), quoteUpdates,
                            fixSession.newEncodersPool("mdEncoders-" + symbol, fixSession.getWriteTasksQueueCapacity() * 2, false, MarketDataSnapshotFullRefreshEncoder.class),
                            mdReqId, symbol));

            mdStreamers.computeIfAbsent(reg.getSymbol(),
                    s -> new MDSymbolStreamer(s, quotesPublishingInterval.toNanos(), fixSession.getClock())).add(reg);
        }

        public synchronized void unregister(FixSessionId fixSessionId, String mdReqId) {
            MDRegistration reg = activeRegistrations.remove(new RegistrationKey(fixSessionId, mdReqId));
            if (reg != null) {
                mdStreamers.get(reg.getSymbol()).remove(reg);
            }
        }

        @Value
        private static class MDRegistration {
            FixSessionId fixSessionId;
            Consumer<MarketDataSnapshotFullRefreshEncoder> quoteUpdatesConsumer;
            FixMessageEncodersPool<MarketDataSnapshotFullRefreshEncoder> encodersPool;
            String mdReqId;
            String symbol;
        }

        @Value
        private static class RegistrationKey {
            FixSessionId fixSessionId;
            String mdReqId;
        }

        @RequiredArgsConstructor
        private static class MDSymbolStreamer {
            private final String symbol;
            private final long quotesPublishingIntervalInNanos;
            private final Set<MDRegistration> activeRegistrations = new HashSet<>();
            private final AtomicBoolean running = new AtomicBoolean();
            private final Clock clock;
            private MDRegistration[] activeRegistrationsArray = new MDRegistration[0];
            private Thread streamerThread;

            public synchronized void add(MDRegistration r) {
                activeRegistrations.add(r);
                activeRegistrationsArray = activeRegistrations.toArray(MDRegistration[]::new);
                if (streamerThread == null) {
                    streamerThread = new Thread(this::run, "MD-streamer-" + symbol);
                    streamerThread.start();
                }
            }

            public synchronized void remove(MDRegistration r) {
                activeRegistrations.remove(r);
                activeRegistrationsArray = activeRegistrations.toArray(MDRegistration[]::new);
                if (activeRegistrationsArray.length == 0 && streamerThread != null) {
                    log.info("No more active subscriptions for MD streamer {}, stopping it", symbol);
                    running.set(false);
                    try {
                        streamerThread.join(Duration.ofSeconds(10).toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    streamerThread = null;
                }
            }

            private void run() {
                running.set(true);
                log.info("Market data stream for {} is started", symbol);
                int layersCount = 4;
                IdleStrategy is = new BackoffIdleStrategy();
                log.info("Current thread timer slack is {} micros", CLibraryApi.get().getTimerSlack().toNanos() / 1000);
                CLibraryApi.get().setTimerSlack(Duration.ofNanos(1000)); // 1 micro
                log.info("Current thread timer slack set to {} micros", CLibraryApi.get().getTimerSlack().toNanos() / 1000);
                Duration maxWaitTimeoutForEncoderPoll = Duration.of(25, ChronoUnit.MICROS);

                FixMessageEncoderFactory encoderFactory = FixMessageEncoderFactory.Registry.find(MarketDataSnapshotFullRefreshEncoder.class);
                MarketDataSnapshotFullRefreshEncoder marketDataSnapshotFullRefreshEncoder =
                        encoderFactory.newInstance(MarketDataSnapshotFullRefreshEncoder.class, null, null, null, null);
                Random random = new Random(System.nanoTime());
                while (running.get()) {
                    LockSupport.parkNanos(quotesPublishingIntervalInNanos);
                    MDRegistration[] localActiveRegistrationsArray = activeRegistrationsArray;
                    UTCTime now = clock.now();
                    MDEntryTime mdEntryTime = MDEntryTime.get();
                    MarketDataSnapshotFullRefreshNoMDEntriesEncoder mdEncoder = marketDataSnapshotFullRefreshEncoder
                            .begin()
                            .setSymbol(symbol)
                            .addNoMDEntries(layersCount);
                    for (int i = 0; i < layersCount; i++) {
                        mdEncoder.setMDEntryPx(random.nextDouble())
                                .setMDEntrySize(random.nextInt(100000))
                                .setMDEntryType(i % 2 == 0 ? MDEntryType.MDEntryTypeValues.OFFER : MDEntryType.MDEntryTypeValues.BID)
                                .addUtcTime(mdEntryTime, now, TimeUnit.MICROSECONDS);
                    }

                    for (MDRegistration localActiveRegistration : localActiveRegistrationsArray) {
                        MarketDataSnapshotFullRefreshEncoder pooled = localActiveRegistration.encodersPool.borrowBlocking(is, maxWaitTimeoutForEncoderPoll);
                        if (pooled == null) {
                            // producing too fast, should not happen as the pool size is 2 time bigger than IO tasks queue size
                            log.info("Starved MarketDataSnapshotFullRefreshEncoder pool for session {}", localActiveRegistration.fixSessionId);
                            continue;
                        }
                        // add the mdReqId for the message and copy the resto of the body of the main quote message
                        pooled.begin().setMDReqID(localActiveRegistration.getMdReqId()).copy(marketDataSnapshotFullRefreshEncoder);
                        localActiveRegistration.getQuoteUpdatesConsumer().accept(pooled);
                    }
                    marketDataSnapshotFullRefreshEncoder.release();
                }
                log.info("Market data stream for {} is stopped", symbol);
            }
        }
    }

    private static final class FixAcceptorApplication implements FixApplication {

        private final MarketDataStreamer marketDataStreamer;

        FixAcceptorApplication(Duration quotesPublishingInterval) {
            marketDataStreamer = new MarketDataStreamer(quotesPublishingInterval);
        }

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
            encodedMessagesTypes.add(MessageTypes.MarketDataSnapshotFullRefresh);
            return List.of(new MarketDataRequestDecoder(marketDataStreamer));
        }

        @Override
        public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
            log.info("Logon acceptor {}", fixSession.getFixSessionId());
        }

        @Override
        public void onLogout(FixSession fixSession, String message, DecodedFixMessage logoutMessage) {
            log.info("Logout acceptor {} : {}", fixSession.getFixSessionId(), message);
        }

        @Override
        public void onNetworkWatermarkEvent(FixSession fixSession, boolean highWatermarkReached, long bytesLeftToWrite) {
            log.info("Network watermark {} on session {} : {}", highWatermarkReached ? "reached" : "back to normal", fixSession.getFixSessionId(), bytesLeftToWrite);
        }

        @Setter
        private static class MarketDataRequestDecoder implements FixMessageDecoder {

            private final MarketDataStreamer marketDataStreamer;
            private String symbol;
            private MDUpdateType.MDUpdateTypeValues mdUpdateType;
            private SubscriptionRequestType.SubscriptionRequestTypeValues subscriptionRequestType;
            private List<MDEntryType.MDEntryTypeValues> noMDEntryTypes = new ArrayList<>();
            private MDEntryType.MDEntryTypeValues noMDEntryType;
            private String mdReqID;

            public MarketDataRequestDecoder(MarketDataStreamer marketDataStreamer) {
                this.marketDataStreamer = marketDataStreamer;
            }

            @Override
            public MessageType getMessageType() {
                return MessageTypes.MarketDataRequest;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {
                fixFieldsDecoderMapper
                        .mapIntValuesEnumField(MDUpdateType.get(), this::setMdUpdateType, null)
                        .mapCharValuesEnumField(SubscriptionRequestType.get(), this::setSubscriptionRequestType, null)
                        .mapStringField(MDReqID.get(), this::setMdReqID, null)
                        .forGroup(NoRelatedSym.get())
                        .withFieldsAutoResetDisabled()
                        // symbol is part of a group and will be reset to null when group is finished to be processed if withFieldsAutoResetDisabled is not disabled
                        .mapStringField(Symbol.get(), this::setSymbol, null, CACHED)
                        .forGroup(NoMDEntryTypes.get())
                        .mapCharValuesEnumField(MDEntryType.get(), this::setNoMDEntryType, null);
            }

            @Override
            public void onBegin(long localReceiveTimeInNanos, UTCTime localReceiveTime) {
                noMDEntryTypes.clear();
            }

            @Override
            public void onGroupEntryEnd(FixField parentGroup, FixField groupField, FixField fixField) {
                if (groupField.equals(NoMDEntryTypes.get())) {
                    noMDEntryTypes.add(noMDEntryType);
                }
            }

            @Override
            public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
                if (!mdUpdateType.equals(MDUpdateType.MDUpdateTypeValues.FULL_REFRESH)) {
                    // will log this outgoing message BEFORE the incoming message..
                    fixSession.sendBusinessMessageReject("Only supporting FULL_REFRESH subscriptions",
                            BusinessRejectReason.BusinessRejectReasonValues.OTHER.code(), null, getMessageType());
                    return;
                }
                log.info("Received MD subscription request {}:{}:{}:{}:{}", fixSession.getFixSessionId(), mdReqID, noMDEntryTypes, symbol, subscriptionRequestType);
                if (subscriptionRequestType.equals(SubscriptionRequestType.SubscriptionRequestTypeValues.SNAPSHOT_AND_UPDATES)) {
                    marketDataStreamer.register(fixSession, qe -> dispatchPrice(fixSession, qe), mdReqID, symbol);
                } else if (subscriptionRequestType.equals(SubscriptionRequestType.SubscriptionRequestTypeValues.DISABLE_PREVIOUS_SNAPSHOT)) {
                    marketDataStreamer.unregister(fixSession.getFixSessionId(), mdReqID);
                }
            }

            private void dispatchPrice(FixSession fixSession, MarketDataSnapshotFullRefreshEncoder marketDataSnapshotFullRefresh) {
                fixSession.send(marketDataSnapshotFullRefresh, null);
            }
        }
    }

    @RequiredArgsConstructor
    private static final class FixInitiatorApplication implements FixApplication {

        private final Set<String> instruments;

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        public void sendMarketDataRequest(FixSession fixSession, SubscriptionRequestType.SubscriptionRequestTypeValues type, String symbol) {
            MarketDataRequestEncoder marketDataRequestEncoder = fixSession.newEncoder(MarketDataRequestEncoder.class).begin()
                    .setMarketDepth(0)
                    .setSubscriptionRequestType(type)
                    .setMDUpdateType(MDUpdateType.MDUpdateTypeValues.FULL_REFRESH)
                    .setMDReqID("sub" + symbol.hashCode());

            marketDataRequestEncoder.addNoMDEntryTypes(2)
                    .setMDEntryType(MDEntryType.MDEntryTypeValues.OFFER)
                    .setMDEntryType(MDEntryType.MDEntryTypeValues.BID);
            marketDataRequestEncoder.addNoRelatedSym(1)
                    .setSymbol(symbol);
            fixSession.send(marketDataRequestEncoder, null);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
            encodedMessagesTypes.add(MessageTypes.MarketDataRequest);
            return List.of(new MarketDataSnapshotFullRefreshDecoder());
        }

        @Override
        public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
            log.info("Logon initiator {}", fixSession.getFixSessionId());
            instruments.forEach(i ->
                    sendMarketDataRequest(fixSession, SubscriptionRequestType.SubscriptionRequestTypeValues.SNAPSHOT_AND_UPDATES, i));
        }

        @Override
        public void onLogoutInitiated(FixSession fixSession, String message) {
            log.info("Logout initiated {} : {}", fixSession.getFixSessionId(), message);
            instruments.forEach(i ->
                    sendMarketDataRequest(fixSession, SubscriptionRequestType.SubscriptionRequestTypeValues.DISABLE_PREVIOUS_SNAPSHOT, i));
        }

        @Override
        public void onLogout(FixSession fixSession, String message, DecodedFixMessage logoutMessage) {
            log.info("Logout initiator {} : {}", fixSession.getFixSessionId(), message);
        }

        @Setter
        private static class MarketDataSnapshotFullRefreshDecoder implements FixMessageDecoder {

            private final IdleStrategy messageExecutorIdleStrategy = new BackoffIdleStrategy();
            private final MarketDataSnapshotFullRefresh.MarketDataSnapshotFullRefreshBuilder builder = MarketDataSnapshotFullRefresh.builder();
            private final MarketDataSnapshotFullRefresh.Quote.QuoteBuilder quoteBuilder = MarketDataSnapshotFullRefresh.Quote.builder();
            private long nextLogTimestamp = System.currentTimeMillis();
            private int receivedQuotes;
            private IntSupplier symbolIndex;

            @Override
            public MessageType getMessageType() {
                return MessageTypes.MarketDataSnapshotFullRefresh;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper fixFieldsDecoderMapper, FieldsRegistry fieldsRegistry) {

                fixFieldsDecoderMapper.mapStringField(MDReqID.get(), builder::mdReqId, null, CACHED)
                        .mapStringField(Symbol.get(), builder::symbol, null, CACHED)
                        .indexer(Symbol.get(), this::setSymbolIndex);

                fixFieldsDecoderMapper.forGroup(NoMDEntries.get())
                        .mapDoubleField(MDEntryPx.get(), quoteBuilder::price, 0)
                        .mapDoubleField(MDEntrySize.get(), quoteBuilder::quantity, 0)
                        .mapLongField(MDEntryID.get(), quoteBuilder::quoteId, 0L)
                        .mapUtcTimeOnlyField(MDEntryTime.get(), quoteBuilder::time, 0)
                        .mapCharValuesEnumField(MDEntryType.get(), quoteBuilder::side, MarketDataSnapshotFullRefresh.Side::from, null);
            }

            @Override
            public void onBegin(long localReceiveTimeInNanos, UTCTime localReceiveTime) {
                builder.clearQuotes();
            }

            @Override
            public void onGroupEntryEnd(FixField parentGroup, FixField groupField, FixField fixField) {
                if (groupField.equals(NoMDEntries.get())) {
                    builder.quote(quoteBuilder.build());
                }
            }

            @Override
            public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
                // process the quote into another thread using the MessageExecutor API,
                // using this API is generating zero memory allocation when offering the task to process in the MessageExecutor queue
                MessageExecutor<MarketDataSnapshotFullRefresh, FixSessionId, Void, Void> exec = fixSession.getMessageExecutor(MarketDataSnapshotFullRefresh.class, symbolIndex);
                MarketDataSnapshotFullRefresh quote = builder.build();
                boolean enqueued = exec.execute(this::processDecodedQuote, quote, fixSession.getFixSessionId(), null, null);
                if (!enqueued) {
                    log.debug("Queue to process quotes is full, retrying");
                    exec.execute(this::processDecodedQuote, quote, fixSession.getFixSessionId(), null, null, messageExecutorIdleStrategy);
                }
            }

            void processDecodedQuote(MarketDataSnapshotFullRefresh quote, FixSessionId fixSessionId, Void param2, Void param3) {
                receivedQuotes++;
                long now = System.currentTimeMillis();
                if (now > nextLogTimestamp) {
                    log.info("Received {} quotes on FIX session {}", receivedQuotes / 5, fixSessionId);
                    receivedQuotes = 0;
                    nextLogTimestamp = now + TimeUnit.SECONDS.toMillis(5);
                }
            }
        }

        @Value
        @Builder
        public static class MarketDataSnapshotFullRefresh {

            String symbol;
            String mdReqId;

            @Singular
            List<Quote> quotes;

            enum Side {
                BID,
                ASK;

                static Side from(MDEntryType.MDEntryTypeValues mdEntryTypeValues) {
                    switch (mdEntryTypeValues) {
                        case BID:
                            return BID;
                        case OFFER:
                            return ASK;
                        default:
                            throw new IllegalStateException("Unmapped " + mdEntryTypeValues);
                    }
                }
            }

            @Value
            @Builder
            public static class Quote {
                double price;
                double quantity;
                long quoteId;
                long time; // nanosOfTheDay
                Side side;
            }
        }
    }
}