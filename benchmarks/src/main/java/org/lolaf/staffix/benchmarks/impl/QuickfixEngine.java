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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.benchmarks.quickfix.fix44.Quote;
import org.lolaf.staffix.benchmarks.quickfix.fix44.QuoteRequest;
import org.lolaf.staffix.benchmarks.quickfix.fix44.field.*;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import quickfix.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

@Slf4j
@State(Scope.Benchmark)
public class QuickfixEngine extends AbstractBenchmark {

    Acceptor quickfixAcceptor;
    Initiator quickfixInitiator;
    InitiatorApplication initiatorApplication;
    AcceptorApplication acceptorApplication;

    private static void ensureStockSettingsProvided(FixEngineSettings fixEngineSettings) {
        if (!fixEngineSettings.equals(FixEngineSettings.STOCK)) {
            throw new IllegalStateException("Only STOCK settings supported");
        }
    }

    @Override
    void setupAcceptor(FixEngineSettings fixEngineSettings) throws Exception {
        ensureStockSettingsProvided(fixEngineSettings);
        acceptorApplication = new AcceptorApplication();
        quickfixAcceptor = buildQuickFixAcceptor(getSessionSettings("quickfix-acceptor-config.cfg"));
        super.setupAcceptor(fixEngineSettings);
    }

    @Override
    void setupInitiator(FixEngineSettings fixEngineSettings) throws Exception {
        ensureStockSettingsProvided(fixEngineSettings);
        initiatorApplication = new InitiatorApplication();
        quickfixInitiator = buildQuickFixInitiator(getSessionSettings("quickfix-initiator-config.cfg"));
        super.setupInitiator(fixEngineSettings);
    }

    @Override
    void startAndWaitForConnection() throws Exception {
        quickfixAcceptor.start();
        quickfixInitiator.start();
        while (!initiatorApplication.connected.get()) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }
    }

    @Override
    void shutdownEngine() throws Exception {
        quickfixInitiator.stop();
        quickfixAcceptor.stop();
        while (initiatorApplication.connected.get()) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }
        super.shutdownEngine();
    }

    private SessionSettings getSessionSettings(String name) throws ConfigError, IOException {
        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(name);
        SessionSettings sessionSettings = new SessionSettings(inputStream);
        inputStream.close();
        return sessionSettings;
    }

    private Initiator buildQuickFixInitiator(SessionSettings sessionSettings) throws ConfigError {
        return SocketInitiator.newBuilder()
                .withSettings(sessionSettings)
                .withApplication(initiatorApplication)
                .withMessageFactory(new DefaultMessageFactory())
                .withLogFactory(new ScreenLogFactory(false, false, true, false))
                .withMessageStoreFactory(new NoopStoreFactory()).build();
    }

    private SocketAcceptor buildQuickFixAcceptor(SessionSettings sessionSettings) throws ConfigError {
        return SocketAcceptor.newBuilder()
                .withSettings(sessionSettings)
                .withApplication(acceptorApplication)
                .withMessageFactory(new DefaultMessageFactory())
                .withLogFactory(new ScreenLogFactory(false, false, true, false))
                .withMessageStoreFactory(new NoopStoreFactory()).build();
    }

    @Override
    public void sendMessageAndWaitForResponse() {
        initiatorApplication.sendMessage();
        while (!initiatorApplication.receivedResponse.get()) {
            Thread.onSpinWait();
        }
    }

    private static class InitiatorApplication implements Application {

        Session session;
        AtomicBoolean receivedResponse = new AtomicBoolean();
        AtomicBoolean connected = new AtomicBoolean();

        @Override
        public void onCreate(SessionID sessionId) {
            // nothing to do
        }

        public void sendMessage() {
            QuoteRequest request = new QuoteRequest();
            request.setString(QuoteReqID.FIELD, "testQuoteRequest");
            QuoteRequest.NoRelatedSym noRelatedSym = new QuoteRequest.NoRelatedSym();
            noRelatedSym.setString(Symbol.FIELD, "FOO/BAR");
            request.addGroup(noRelatedSym);
            receivedResponse.set(false);
            session.send(request);
        }

        @Override
        public void onLogon(SessionID sessionId) {
            session = Session.lookupSession(sessionId);
            connected.set(true);
        }

        @Override
        public void onLogout(SessionID sessionId) {
            connected.set(false);
        }

        @Override
        public void toAdmin(Message message, SessionID sessionId) {
            // nothing to do
        }

        @Override
        public void fromAdmin(Message message, SessionID sessionId) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
            // nothing to do
        }

        @Override
        public void toApp(Message message, SessionID sessionId) throws DoNotSend {
            // nothing to do
        }

        @Override
        public void fromApp(Message message, SessionID sessionId) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
            receivedResponse.set(true);
        }
    }

    private static class AcceptorApplication implements Application {

        Session session;
        QuoteRequest.NoRelatedSym noRelatedSym = new QuoteRequest.NoRelatedSym();

        @Override
        public void onCreate(SessionID sessionId) {
            // nothing to do
        }

        @Override
        public void onLogon(SessionID sessionId) {
            session = Session.lookupSession(sessionId);
        }

        @Override
        public void onLogout(SessionID sessionId) {
            // nothing to do
        }

        @Override
        public void toAdmin(Message message, SessionID sessionId) {
            // nothing to do
        }

        @Override
        public void fromAdmin(Message message, SessionID sessionId) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
            // nothing to do
        }

        @Override
        public void toApp(Message message, SessionID sessionId) throws DoNotSend {
            // nothing to do
        }

        @Override
        public void fromApp(Message message, SessionID sessionId) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
            Quote quote = new Quote();
            quote.setString(QuoteReqID.FIELD, message.getString(QuoteReqID.FIELD));
            quote.setString(QuoteID.FIELD, "testQuoteId");
            Group noRelatedSymGrp = message.getGroup(1, noRelatedSym.getFieldTag());
            quote.setString(Symbol.FIELD, noRelatedSymGrp.getString(Symbol.FIELD));
            quote.setChar(Side.FIELD, Side.BUY);
            quote.setDouble(BidPx.FIELD, 1234567.123456789d);
            quote.setDouble(BidSize.FIELD, 1234567d);
            session.send(quote);
        }
    }
}