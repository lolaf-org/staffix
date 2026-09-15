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
package org.lolaf.staffix.examples.quickstart;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.*;
import org.lolaf.staffix.api.application.FixApplication;
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
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.fix44.encoders.QuoteEncoder;
import org.lolaf.staffix.fix44.encoders.QuoteRequestEncoder;
import org.lolaf.staffix.fix44.fields.QuoteReqID;
import org.lolaf.staffix.fix44.fields.Symbol;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.stores.loggers.slf4j.Slf4jMessagesLoggerSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The smallest complete Staffix application: an acceptor and an initiator in one process, a QuoteRequest sent, a
 * Quote sent back, and both printed.
 * <p>
 * Everything is here on purpose - no shared base class, no options parsing, no persistence to configure. This is the
 * whole of what a first Staffix program needs, and it is the code the README quotes.
 * <p>
 * Run it from the repository root with:
 * <pre>
 * mvn compile exec:java -pl examples/quickstart \
 *     -Dexec.mainClass=org.lolaf.staffix.examples.quickstart.QuickstartExample
 * </pre>
 * Without {@code -q}: {@code exec:java} runs inside Maven's own JVM, so Maven's SLF4J provider handles the
 * application's logging and {@code -q} would hide every INFO line this example prints.
 */
@Slf4j
public final class QuickstartExample {

    private static final String ACCEPTOR = "acceptor";
    private static final String INITIATOR = "initiator";
    private static final int PORT = 7101;

    /**
     * Released once the initiator has seen the Quote come back, so the example can shut itself down.
     */
    private static final CountDownLatch RESPONSE_RECEIVED = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        // 1. Who talks to whom. A session id is the FIX version plus the CompID pair that identifies the session.
        FixSessionId acceptorSessionId = sessionId("acceptor-session", ACCEPTOR, INITIATOR);
        FixSessionId initiatorSessionId = sessionId("initiator-session", INITIATOR, ACCEPTOR);

        // 2. The engine: what stores messages, what logs them, which application handles which session, and where
        //    the session settings come from. Every one of these is an interface with several implementations; these
        //    are the in-memory ones, which need nothing on disk.
        FixEngine engine = FixEngineBuilder.builder()
                .instanceId("quickstart-engine")
                .fixMessagesStore(MemoryMessageStoreSettings.builder().instanceId(ACCEPTOR).build())
                .fixMessagesStore(MemoryMessageStoreSettings.builder().instanceId(INITIATOR).build())
                .fixMessagesLogger(Slf4jMessagesLoggerSettings.builder().instanceId(ACCEPTOR).build())
                .fixMessagesLogger(Slf4jMessagesLoggerSettings.builder().instanceId(INITIATOR).build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .instanceId(ACCEPTOR)
                        .application(acceptorSessionId.getId(), new AcceptorApplication())
                        .build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .instanceId(INITIATOR)
                        .application(initiatorSessionId.getId(), new InitiatorApplication())
                        .build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .instanceId(ACCEPTOR)
                        .fixSessionSetting(sessionSettings(acceptorSessionId, FixSession.FixSessionType.ACCEPTOR, ACCEPTOR))
                        .build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .instanceId(INITIATOR)
                        .fixSessionSetting(sessionSettings(initiatorSessionId, FixSession.FixSessionType.INITIATOR, INITIATOR))
                        .build())
                .build().instance().start();

        // 3. The acceptor listens; the initiator dials. Both take their sessions from the settings stores above.
        FixAcceptor acceptor = engine.newAcceptor(FixAcceptorBuilder.builder()
                .instanceId(ACCEPTOR)
                .bindAddress(new InetSocketAddress("localhost", PORT))
                .targetFixSessionsSettingsStoreInstancesIds(List.of(ACCEPTOR))
                .build()).start();

        FixInitiator initiator = engine.newInitiator(FixInitiatorBuilder.builder()
                .instanceId(INITIATOR)
                .fixSessionId(initiatorSessionId)
                .connectAddress(new InetSocketAddress("localhost", PORT))
                .build()).start();

        // 4. Logon, QuoteRequest and Quote all happen on the engine's threads; wait for the round trip.
        if (!RESPONSE_RECEIVED.await(30, TimeUnit.SECONDS)) {
            log.error("No Quote came back within 30 seconds");
        }

        initiator.stop();
        acceptor.stop();
        engine.stop(Deadline.of(Duration.ofSeconds(5)));
    }

    private static FixSessionId sessionId(String id, String senderCompId, String targetCompId) {
        return FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                .id(id)
                .senderCompID(senderCompId)
                .targetCompID(targetCompId)
                .build());
    }

    /**
     * Which engine components this session uses, by instance id, plus whatever protocol behaviour it needs. Sequence
     * numbers restart on every logon here so the example can be run repeatedly without resetting anything.
     */
    private static FixSessionSettings sessionSettings(FixSessionId sessionId, FixSession.FixSessionType type,
                                                      String instanceId) {
        return FixSessionSettings.builder()
                .fixSessionId(sessionId)
                .fixSessionType(type)
                .fixMessageStoreInstanceId(instanceId)
                .fixMessageLoggerInstanceId(instanceId)
                .fixApplicationFactoryInstanceId(instanceId)
                .fixApplicationInstanceId(sessionId.getId())
                .resetSeqNumOnLogon(true)
                .build();
    }

    /**
     * Answers every QuoteRequest with a Quote.
     */
    private static final class AcceptorApplication implements FixApplication {

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings settings, FixSession session,
                                             Set<MessageType> encodedMessagesTypes) {
            // declare what this side sends, and return a decoder per message type it receives
            encodedMessagesTypes.add(MessageTypes.Quote);
            return List.of(new QuoteRequestDecoder(session));
        }

        @Override
        public void onLogon(FixSession session, DecodedFixMessage logon) {
            log.info("acceptor: {} logged on", session.getFixSessionId());
        }

        /**
         * A decoder maps the fields it cares about onto setters, and is told when the message is complete. Fields
         * nobody maps are never parsed - that is where the latency goes.
         */
        @Setter
        private static final class QuoteRequestDecoder implements FixMessageDecoder {

            private final QuoteEncoder quoteEncoder;
            private String quoteReqId;
            private String symbol;

            QuoteRequestDecoder(FixSession session) {
                // reusable: the same encoder instance is filled in and sent for every message
                quoteEncoder = session.newEncoder(QuoteEncoder.class).asReusable();
            }

            @Override
            public MessageType getMessageType() {
                return MessageTypes.QuoteRequest;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper mapper, FieldsRegistry fieldsRegistry) {
                mapper.mapStringField(QuoteReqID.get(), this::setQuoteReqId, null)
                        .forGroup(org.lolaf.staffix.fix44.fields.NoRelatedSym.get())
                        // group fields are reset after each entry by default, so that one entry's values never leak
                        // into the next; this message has a single entry and wants to read it in onDecoded
                        .withFieldsAutoResetDisabled()
                        .mapStringField(Symbol.get(), this::setSymbol, null);
            }

            @Override
            public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
                log.info("acceptor: QuoteRequest {} for {}", quoteReqId, symbol);
                session.send(quoteEncoder.begin()
                        .setQuoteReqID(quoteReqId)
                        .setQuoteID(quoteReqId + "-1")
                        .setSymbol(symbol)
                        .setBidPx(101.25d)
                        .setOfferPx(101.75d), null);
            }
        }
    }

    /**
     * Sends one QuoteRequest as soon as it is logged on, and stops the example when the Quote arrives.
     */
    private static final class InitiatorApplication implements FixApplication {

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings settings, FixSession session,
                                             Set<MessageType> encodedMessagesTypes) {
            encodedMessagesTypes.add(MessageTypes.QuoteRequest);
            return List.of(new QuoteDecoder());
        }

        @Override
        public void onLogon(FixSession session, DecodedFixMessage logon) {
            log.info("initiator: {} logged on, sending a QuoteRequest", session.getFixSessionId());
            QuoteRequestEncoder quoteRequest = session.newEncoder(QuoteRequestEncoder.class);
            // addNoRelatedSym returns the group's encoder, so the message is built first and sent afterwards
            quoteRequest.begin()
                    .setQuoteReqID("quickstart-1")
                    .addNoRelatedSym(1)
                    .setSymbol("EUR/USD");
            session.send(quoteRequest, null);
        }

        @Setter
        private static final class QuoteDecoder implements FixMessageDecoder {

            private String quoteId;
            private double bidPx;

            @Override
            public MessageType getMessageType() {
                return MessageTypes.Quote;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper mapper, FieldsRegistry fieldsRegistry) {
                mapper.mapStringField(org.lolaf.staffix.fix44.fields.QuoteID.get(), this::setQuoteId, null)
                        .mapDoubleField(org.lolaf.staffix.fix44.fields.BidPx.get(), this::setBidPx, 0d);
            }

            @Override
            public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
                log.info("initiator: Quote {} bid {}", quoteId, bidPx);
                RESPONSE_RECEIVED.countDown();
            }
        }
    }
}
