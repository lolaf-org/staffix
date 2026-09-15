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
package org.lolaf.staffix.benchmarks.impl;

import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import lombok.extern.slf4j.Slf4j;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.lolaf.ringos.clib.CLibraryApi;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.Side;
import uk.co.real_logic.artio.builder.QuoteEncoder;
import uk.co.real_logic.artio.builder.QuoteRequestEncoder;
import uk.co.real_logic.artio.decoder.QuoteDecoder;
import uk.co.real_logic.artio.decoder.QuoteRequestDecoder;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.library.*;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.SessionState;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;
import uk.co.real_logic.artio.validation.AuthenticationStrategy;
import uk.co.real_logic.artio.validation.MessageValidationStrategy;

import java.io.File;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.aeron.driver.ThreadingMode.DEDICATED;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static java.util.Collections.singletonList;

@Slf4j
@State(Scope.Benchmark)
public class ArtioEngine extends AbstractBenchmark {

    private static final String ACCEPTOR_COMP_ID = "TARGET44_TEST";
    private static final String INITIATOR_COMP_ID = "SENDER44_TEST";
    private static final int FIX_PORT = 7002;

    // The initiator FixLibrary is only polled on the benchmark thread while a measured
    // send is in flight (see startAndWaitForConnection), so it goes silent between JMH
    // iterations and during GC pauses. Artio drives FIX session liveness from
    // FixLibrary.poll() (LibraryPoller.pollSessions -> InternalSession.poll emits
    // heartbeats and detects timeouts), so with the default 10s heartbeat any such gap
    // lets the continuously-polled acceptor time the initiator out and disconnect it,
    // after which trySend()/poll() wedge forever. Negotiate a very long heartbeat so
    // benchmark gaps can never trip the test-request / timeout disconnect.
    private static final int HEARTBEAT_INTERVAL_IN_S = 3600;

    // Safety net for sendMessageAndWaitForResponse(): if this many library polls elapse
    // without a Quote, assume the request or its response was dropped and resend rather
    // than wedge forever. A poll count (not a clock read) keeps the measured path free of
    // System.nanoTime() calls; it is set far above any real round-trip poll count so it
    // never fires spuriously in steady state.
    private static final int RESEND_AFTER_POLLS = 5_000_000;

    private static final String BASE_DIR = "target/artio-benchmark";
    private static final String ACCEPTOR_AERON_DIR = BASE_DIR + "/acceptor-aeron";
    private static final String ACCEPTOR_ARCHIVE_DIR = BASE_DIR + "/acceptor-archive";
    private static final String ACCEPTOR_LOG_DIR = BASE_DIR + "/acceptor-logs";
    private static final String ACCEPTOR_AERON_CHANNEL = "aeron:udp?endpoint=localhost:10010";
    private static final String ACCEPTOR_CONTROL_REQUEST = "aeron:udp?endpoint=localhost:8010";
    private static final String ACCEPTOR_CONTROL_RESPONSE = "aeron:udp?endpoint=localhost:8020";
    private static final String ACCEPTOR_REPLICATION = "aeron:udp?endpoint=localhost:0";
    private static final String ACCEPTOR_RECORDING_EVENTS = "aeron:udp?control-mode=dynamic|control=localhost:8030";
    private static final String ACCEPTOR_MONITORING = BASE_DIR + "/acceptor-monitoring";

    private static final String INITIATOR_AERON_DIR = BASE_DIR + "/initiator-aeron";
    private static final String INITIATOR_ARCHIVE_DIR = BASE_DIR + "/initiator-archive";
    private static final String INITIATOR_LOG_DIR = BASE_DIR + "/initiator-logs";
    private static final String INITIATOR_AERON_CHANNEL = "aeron:udp?endpoint=localhost:10020";
    private static final String INITIATOR_CONTROL_REQUEST = "aeron:udp?endpoint=localhost:9010";
    private static final String INITIATOR_CONTROL_RESPONSE = "aeron:udp?endpoint=localhost:9020";
    private static final String INITIATOR_REPLICATION = "aeron:udp?endpoint=localhost:0";
    private static final String INITIATOR_RECORDING_EVENTS = "aeron:udp?control-mode=dynamic|control=localhost:9030";
    private static final String INITIATOR_MONITORING = BASE_DIR + "/initiator-monitoring";
    private final AtomicBoolean receivedResponse = new AtomicBoolean(false);
    // Set once the acceptor library has acquired its session. Application messages sent
    // before this happens race the acquisition and are silently dropped (inbound logging
    // is disabled, so there is nothing to replay), which wedges the response wait forever.
    private final AtomicBoolean acceptorSessionAcquired = new AtomicBoolean(false);
    private final QuoteRequestEncoder quoteRequestEncoder = new QuoteRequestEncoder();
    private ArchivingMediaDriver acceptorDriver;
    private ArchivingMediaDriver initiatorDriver;
    private FixEngine acceptorGateway;
    private FixEngine initiatorGateway;
    private FixLibrary acceptorLibrary;
    private FixLibrary initiatorLibrary;
    private Session initiatorSession;
    // Diagnostic: number of QuoteRequests that had to be resent because no Quote came back.
    private volatile long lostResponseResends;
    private volatile boolean running;
    private Thread acceptorPollingThread;

    private static FixLibrary blockingConnect(final LibraryConfiguration configuration) {
        final FixLibrary library = FixLibrary.connect(configuration);
        while (!library.isConnected()) {
            library.poll(128);
            Thread.yield();
        }
        return library;
    }

    @Override
    void fixEngineSetup(FixEngineSettings fixEngineSettings) {

        IoUtil.delete(new File(BASE_DIR), true);
        new File(BASE_DIR).mkdirs();
    }

    @Override
    void setupAcceptor(FixEngineSettings fixEngineSettings) throws Exception {
        // Launch acceptor MediaDriver + Archive
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
                .threadingMode(DEDICATED)
                .dirDeleteOnStart(true)
                .aeronDirectoryName(ACCEPTOR_AERON_DIR);

        final Archive.Context archiveCtx = new Archive.Context()
                .idleStrategySupplier(BackoffIdleStrategy::new)
                .threadingMode(ArchiveThreadingMode.DEDICATED)
                .deleteArchiveOnStart(true)
                .aeronDirectoryName(ACCEPTOR_AERON_DIR)
                .archiveDirectoryName(ACCEPTOR_ARCHIVE_DIR)
                .controlChannel(ACCEPTOR_CONTROL_REQUEST)
                .replicationChannel(ACCEPTOR_REPLICATION)
                .recordingEventsChannel(ACCEPTOR_RECORDING_EVENTS);

        acceptorDriver = ArchivingMediaDriver.launch(driverCtx, archiveCtx);

        // Configure and launch acceptor FixEngine
        final MessageValidationStrategy validationStrategy = MessageValidationStrategy.targetCompId(ACCEPTOR_COMP_ID)
                .and(MessageValidationStrategy.senderCompId(Collections.singletonList(INITIATOR_COMP_ID)));
        final AuthenticationStrategy authenticationStrategy = AuthenticationStrategy.of(validationStrategy);

        final EngineConfiguration engineConfig = new EngineConfiguration()
                .bindTo("localhost", FIX_PORT)
                .libraryAeronChannel(ACCEPTOR_AERON_CHANNEL)
                .logInboundMessages(false)
                .logOutboundMessages(false)
                .logFileDir(ACCEPTOR_LOG_DIR)
                .deleteLogFileDirOnStart(true)
                .monitoringFile(ACCEPTOR_MONITORING)
                .authenticationStrategy(authenticationStrategy);

        engineConfig.aeronArchiveContext()
                .idleStrategy(new BackoffIdleStrategy()) // value by default
                .aeronDirectoryName(ACCEPTOR_AERON_DIR)
                .controlRequestChannel(ACCEPTOR_CONTROL_REQUEST)
                .controlResponseChannel(ACCEPTOR_CONTROL_RESPONSE);

        engineConfig.aeronContext()
                .idleStrategy(new BackoffIdleStrategy()) // value by default
                .aeronDirectoryName(ACCEPTOR_AERON_DIR);

        acceptorGateway = FixEngine.launch(engineConfig);

        // Connect acceptor FixLibrary
        final LibraryConfiguration libConfig = new LibraryConfiguration()
                .libraryIdleStrategy(new BackoffIdleStrategy()) // value by default
                .sessionAcquireHandler((session, acquiredInfo) -> {
                    acceptorSessionAcquired.set(true);
                    return new AcceptorSessionHandler();
                })
                .sessionExistsHandler(new AcquiringSessionExistsHandler())
                .libraryAeronChannels(singletonList(ACCEPTOR_AERON_CHANNEL));
        libConfig.defaultHeartbeatIntervalInS(HEARTBEAT_INTERVAL_IN_S);

        libConfig.aeronContext()
                .idleStrategy(new BackoffIdleStrategy()) // value by default
                .aeronDirectoryName(ACCEPTOR_AERON_DIR);

        acceptorLibrary = blockingConnect(libConfig);

        super.setupAcceptor(fixEngineSettings);
    }

    @Override
    void setupInitiator(FixEngineSettings fixEngineSettings) throws Exception {
        // Launch initiator MediaDriver + Archive
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
                .threadingMode(DEDICATED)
                .dirDeleteOnStart(true)
                .aeronDirectoryName(INITIATOR_AERON_DIR);

        final Archive.Context archiveCtx = new Archive.Context()
                .idleStrategySupplier(BackoffIdleStrategy::new)
                .threadingMode(ArchiveThreadingMode.DEDICATED)
                .deleteArchiveOnStart(true)
                .aeronDirectoryName(INITIATOR_AERON_DIR)
                .archiveDirectoryName(INITIATOR_ARCHIVE_DIR)
                .controlChannel(INITIATOR_CONTROL_REQUEST)
                .replicationChannel(INITIATOR_REPLICATION)
                .recordingEventsChannel(INITIATOR_RECORDING_EVENTS);

        initiatorDriver = ArchivingMediaDriver.launch(driverCtx, archiveCtx);

        // Configure and launch initiator FixEngine (no bindTo - it's a client)
        final EngineConfiguration engineConfig = new EngineConfiguration()
                .libraryAeronChannel(INITIATOR_AERON_CHANNEL)
                .logInboundMessages(false)
                .logOutboundMessages(false)
                .logFileDir(INITIATOR_LOG_DIR)
                .deleteLogFileDirOnStart(true)
                .monitoringFile(INITIATOR_MONITORING)
                .printErrorMessages(false);

        engineConfig.aeronArchiveContext()
                .idleStrategy(new BackoffIdleStrategy()) // value by default
                .aeronDirectoryName(INITIATOR_AERON_DIR)
                .controlRequestChannel(INITIATOR_CONTROL_REQUEST)
                .controlResponseChannel(INITIATOR_CONTROL_RESPONSE);

        engineConfig.aeronContext()
                .idleStrategy(new BackoffIdleStrategy()) // value by default
                .aeronDirectoryName(INITIATOR_AERON_DIR);

        initiatorGateway = FixEngine.launch(engineConfig);

        // Connect initiator FixLibrary
        final LibraryConfiguration libConfig = new LibraryConfiguration()
                .libraryIdleStrategy(new BackoffIdleStrategy()) // value by default
                .sessionAcquireHandler((session, acquiredInfo) -> new InitiatorSessionHandler())
                .libraryAeronChannels(singletonList(INITIATOR_AERON_CHANNEL));
        // Governs the negotiated HeartBtInt: LibraryPoller reads defaultHeartbeatIntervalInS()
        // to build the initiator Logon, so this is the value the acceptor adopts for the session.
        libConfig.defaultHeartbeatIntervalInS(HEARTBEAT_INTERVAL_IN_S);

        libConfig.aeronContext()
                .idleStrategy(new BackoffIdleStrategy()) // value by default
                .aeronDirectoryName(INITIATOR_AERON_DIR);

        initiatorLibrary = blockingConnect(libConfig);

        // Pre-configure the reusable QuoteRequest encoder
        quoteRequestEncoder.quoteReqID("testQuoteRequest");
        quoteRequestEncoder.relatedSymGroup(1).instrument().symbol("FOO/BAR");

        super.setupInitiator(fixEngineSettings);
    }

    @Override
    void startAndWaitForConnection() {
        running = true;

        // Start acceptor polling thread first (must be running to accept incoming connections)
        acceptorPollingThread = new Thread(() -> {
            CLibraryApi.get().setTimerSlack(Duration.ofNanos(5000));
            IdleStrategy acceptorIdleStrategy = getIdleStrategy();
            while (running) {
                try {
                    acceptorIdleStrategy.idle(acceptorLibrary.poll(128));
                } catch (final Throwable t) {
                    // Without this the daemon thread would die silently on any poll error,
                    // leaving sendMessageAndWaitForResponse() waiting for a response forever.
                    log.error("Acceptor library poll failed", t);
                }
            }
        }, "artio-acceptor-poll");
        acceptorPollingThread.setDaemon(true);
        acceptorPollingThread.start();

        // Initiate FIX session (polls initiator library internally, so do NOT start
        // the initiator polling thread yet to avoid concurrent library.poll())
        final SessionConfiguration sessionConfig = SessionConfiguration.builder()
                .address("localhost", FIX_PORT)
                .targetCompId(ACCEPTOR_COMP_ID)
                .senderCompId(INITIATOR_COMP_ID)
                .build();

        initiatorSession = LibraryUtil.initiate(
                initiatorLibrary,
                sessionConfig,
                30_000,
                getIdleStrategy());

        // Wait for the initiator session to become active AND for the acceptor to have
        // acquired its session. Sending before the acceptor has acquired races the first
        // QuoteRequest against acquisition and drops it (see acceptorSessionAcquired).
        final IdleStrategy connectIdleStrategy = new BackoffIdleStrategy();
        while (!initiatorSession.isActive() || !acceptorSessionAcquired.get()) {
            connectIdleStrategy.idle(initiatorLibrary.poll(128));
        }

        // The initiator library is polled from the benchmark thread itself in
        // sendMessageAndWaitForResponse() to preserve Artio's single-threaded
        // FixLibrary/Session ownership (trySend and poll must be on the same thread).

        // Prove the request/response path end-to-end (and warm the JIT) before JMH starts
        // measuring, so a first-message loss can never wedge the first measured iteration.
        primeRoundTrip();

        log.info("Artio FIX session established (acceptor acquired, path primed)");
    }

    private IdleStrategy getIdleStrategy() {
        return fixEngineSettings.equals(FixEngineSettings.LOW_LATENCY)
                ? YieldingIdleStrategy.INSTANCE : new BackoffIdleStrategy(20, 40,
                TimeUnit.MICROSECONDS.toNanos(5), TimeUnit.MICROSECONDS.toNanos(10));
    }

    @Override
    void shutdownEngine() throws Exception {
        running = false;
        log.info("Artio shutdown: lostResponseResends={}", lostResponseResends);

        if (initiatorSession != null && initiatorSession.state() == SessionState.ACTIVE) {
            initiatorSession.startLogout();
            initiatorSession.requestDisconnect();
        }

        if (acceptorPollingThread != null) {
            acceptorPollingThread.join(5000);
        }

        if (initiatorLibrary != null) {
            initiatorLibrary.close();
        }
        if (acceptorLibrary != null) {
            acceptorLibrary.close();
        }
        if (initiatorGateway != null) {
            initiatorGateway.close();
        }
        if (acceptorGateway != null) {
            acceptorGateway.close();
        }
        if (initiatorDriver != null) {
            initiatorDriver.close();
        }
        if (acceptorDriver != null) {
            acceptorDriver.close();
        }

        IoUtil.delete(new File(BASE_DIR), true);

        super.shutdownEngine();
    }

    @Override
    public void sendMessageAndWaitForResponse() {
        receivedResponse.set(false);
        encodeQuoteRequest();

        sendUntilAccepted();

        int pollsSinceSend = 0;
        while (!receivedResponse.get()) {
            initiatorLibrary.poll(128);
            if (++pollsSinceSend >= RESEND_AFTER_POLLS) {
                // No Quote after a very long wait: the request or its response was dropped.
                // Resend instead of wedging forever (see the stack.txt / Loop-B hang).
                lostResponseResends++;
                sendUntilAccepted();
                pollsSinceSend = 0;
            }
        }
    }

    private void encodeQuoteRequest() {
        quoteRequestEncoder.reset();
        quoteRequestEncoder.quoteReqID("testQuoteRequest");
        quoteRequestEncoder.relatedSymGroup(1).instrument().symbol("FOO/BAR");
    }

    // Publishes the QuoteRequest to the outbound stream, retrying on Aeron back-pressure.
    private void sendUntilAccepted() {
        long result;
        do {
            result = initiatorSession.trySend(quoteRequestEncoder);
            initiatorLibrary.poll(128);
        }
        while (result < 0);
    }

    // Resends the QuoteRequest every ~50ms until a Quote comes back, so the connection is
    // proven end-to-end before measurement starts. With the acceptor already acquired this
    // normally succeeds on the first attempt; the retry only covers residual races.
    private void primeRoundTrip() {
        final long resendIntervalNs = TimeUnit.MILLISECONDS.toNanos(50);
        for (int attempt = 1; ; attempt++) {
            receivedResponse.set(false);
            encodeQuoteRequest();
            sendUntilAccepted();
            final long deadline = System.nanoTime() + resendIntervalNs;
            while (System.nanoTime() < deadline) {
                initiatorLibrary.poll(128);
                if (receivedResponse.get()) {
                    if (attempt > 1) {
                        log.info("Artio round-trip primed after {} attempts", attempt);
                    }
                    return;
                }
            }
            log.warn("Artio priming attempt {} got no Quote (initiator active={}), resending",
                    attempt, initiatorSession.isActive());
        }
    }

    private class AcceptorSessionHandler implements SessionHandler {

        private final QuoteEncoder quoteEncoder = new QuoteEncoder();
        private final QuoteRequestDecoder quoteRequestDecoder = new QuoteRequestDecoder();
        private final long quoteRequestMsgType = new QuoteRequestEncoder().messageType();
        private final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer();

        @Override
        public Action onMessage(
                final DirectBuffer buffer, final int offset, final int length,
                final int libraryId, final Session session, final int sequenceIndex,
                final long messageType, final long timestampInNs, final long position,
                final OnMessageInfo messageInfo) {
            if (messageType == quoteRequestMsgType) {
                asciiBuffer.wrap(buffer, offset, length);
                quoteRequestDecoder.decode(asciiBuffer, 0, length);
                QuoteRequestDecoder.RelatedSymGroupDecoder relatedSymGroupDecoder = quoteRequestDecoder.relatedSymGroup();

                quoteEncoder.reset();
                quoteEncoder.quoteReqID(quoteRequestDecoder.quoteReqID());
                quoteEncoder.quoteID("testQuoteId");
                quoteEncoder.side(Side.BUY);
                quoteEncoder.instrument().symbol(relatedSymGroupDecoder.symbol());
                quoteEncoder.bidPx(1234567123456789L, 9);
                quoteEncoder.bidSize(1234567L, 0);

                return Pressure.apply(session.trySend(quoteEncoder));
            }

            return CONTINUE;
        }

        @Override
        public void onTimeout(final int libraryId, final Session session) {
        }

        @Override
        public void onSlowStatus(final int libraryId, final Session session, final boolean hasBecomeSlow) {
        }

        @Override
        public Action onDisconnect(final int libraryId, final Session session, final DisconnectReason reason) {
            log.info("Acceptor FIX session disconnected: {}", reason);
            return CONTINUE;
        }

        @Override
        public void onSessionStart(final Session session) {
        }
    }

    private class InitiatorSessionHandler implements SessionHandler {

        private final long quoteMsgType = new QuoteEncoder().messageType();
        private final QuoteDecoder quoteDecoder = new QuoteDecoder();
        private final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer();

        @Override
        public Action onMessage(
                final DirectBuffer buffer, final int offset, final int length,
                final int libraryId, final Session session, final int sequenceIndex,
                final long messageType, final long timestampInNs, final long position,
                final OnMessageInfo messageInfo) {

            if (messageType == quoteMsgType) {
                asciiBuffer.wrap(buffer, offset, length);
                quoteDecoder.decode(asciiBuffer, 0, length);
                char[] quoteReqId = quoteDecoder.quoteReqID();
                char[] quoteId = quoteDecoder.quoteID();
                char[] symbol = quoteDecoder.symbol();
                Side side = quoteDecoder.sideAsEnum();
                double bidPx = quoteDecoder.bidPx().toDouble();
                double bidSizePx = quoteDecoder.bidSize().toDouble();
                receivedResponse.set(true);
            }
            return CONTINUE;
        }

        @Override
        public void onTimeout(final int libraryId, final Session session) {
        }

        @Override
        public void onSlowStatus(final int libraryId, final Session session, final boolean hasBecomeSlow) {
        }

        @Override
        public Action onDisconnect(final int libraryId, final Session session, final DisconnectReason reason) {
            log.info("Initiator FIX session disconnected: {}", reason);
            return CONTINUE;
        }

        @Override
        public void onSessionStart(final Session session) {
        }
    }
}
