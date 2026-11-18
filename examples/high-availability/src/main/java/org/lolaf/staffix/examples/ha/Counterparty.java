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
package org.lolaf.staffix.examples.ha;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.FixInitiatorBuilder;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionDesiredState;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.fix44.fields.Subject;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.stores.messages.file.FileMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.lolaf.staffix.examples.ha.Topology.*;

/**
 * The client of the pair: an initiator given both nodes' addresses, which logs on to whichever leads and sends it an
 * Email every second, logging the answer and every gap it asks the gateway to fill.
 */
@Slf4j
public final class Counterparty implements FixApplication {

    private volatile FixSession loggedOn;
    private long sent;

    public static void main(String[] args) throws InterruptedException {
        Counterparty counterparty = new Counterparty();
        FixEngine engine = FixEngineBuilder.builder()
                .instanceId("counterparty")
                .fixMessagesStore(FileMessageStoreSettings.builder()
                        .storageDirectoryPath(Path.of("target", "counterparty", "store").toString())
                        .build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .instanceId("app")
                        .application("app", counterparty)
                        .build())
                .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                        .fixSessionSetting(FixSessionSettings.builder()
                                .fixSessionId(CLIENT_SESSION)
                                .fixSessionType(FixSession.FixSessionType.INITIATOR)
                                .fixApplicationFactoryInstanceId("app")
                                .fixApplicationInstanceId("app")
                                .desiredSessionState(FixSessionDesiredState.LOGGED_IN)
                                .build())
                        .build())
                .build().instance().start();

        engine.newInitiator(FixInitiatorBuilder.builder()
                .connectionRetry(Duration.ofSeconds(1))
                .mainTarget(FixInitiatorTarget.builder()
                        .fixSessionId(CLIENT_SESSION)
                        .connectAddress(fixAddress(1))
                        .connectAddress(fixAddress(2))
                        .build())
                .build()).start();

        ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor();
        sender.scheduleAtFixedRate(counterparty::sendRequest, 1, 1, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sender.shutdownNow();
            engine.stop(Deadline.of(Duration.ofSeconds(5)));
        }, "counterparty-stop"));
        Thread.currentThread().join();
    }

    private void sendRequest() {
        FixSession session = loggedOn;
        if (session != null) {
            session.send(session.newEncoder(EmailEncoder.class).begin().setSubject("request " + ++sent), null);
        }
    }

    @Override
    public FixApiVersion getFixApiVersion() {
        return FixApiVersion.of(FixRegularVersion.VERSION_44);
    }

    @Override
    public FixDictionaryId getDictionaryId() {
        return FixDictionaryId.of(FixRegularVersion.VERSION_44);
    }

    @Override
    public void onLogon(FixSession session, DecodedFixMessage logon) {
        log.info("Logged on");
        loggedOn = session;
    }

    @Override
    public void onLogout(FixSession session, String message, DecodedFixMessage logout) {
        loggedOn = null;
        log.info("Logged out: {}", message);
    }

    @Override
    public void onDisconnected(FixSession session) {
        loggedOn = null;
        log.info("Disconnected");
    }

    @Override
    public void onResendRequestInitiated(FixSession session, long fromSeqNum, long toSeqNum) {
        log.warn("Gap in the gateway's MsgSeqNum: asked for {} to {} again", fromSeqNum, toSeqNum);
    }

    @Override
    public void onResendRequestTerminated(FixSession session, long fromSeqNum, long toSeqNum) {
        log.info("Gap {} to {} filled", fromSeqNum, toSeqNum);
    }

    @Override
    public List<FixMessageDecoder> setup(FixSessionSettings settings, FixSession session, Set<MessageType> encodedMessagesTypes) {
        encodedMessagesTypes.add(MessageTypes.Email);
        return List.of(new FixMessageDecoder() {
            private String subject;

            @Override
            public MessageType getMessageType() {
                return MessageTypes.Email;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper mapper, FieldsRegistry fieldsRegistry) {
                mapper.mapStringField(Subject.get(), value -> subject = value, null);
            }

            @Override
            public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
                log.info("{}{}", subject, possDupFlag ? " (possible duplicate)" : "");
            }
        });
    }
}
