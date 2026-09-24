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
import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.examples.plugins.StampingFixSessionsPlugin;
import org.lolaf.staffix.examples.plugins.StampingFixSessionsPluginSettings;
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.fix44.fields.EmailThreadID;
import org.lolaf.staffix.fix44.fields.EmailType;
import org.lolaf.staffix.fix44.fields.Subject;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import picocli.CommandLine;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/**
 * A session plugin that adds a field the application never wrote.
 * <p>
 * Each initiator streams Email messages from an application thread of its own. A
 * {@link StampingFixSessionsPlugin} registered on the engine appends a user defined field — tag {@code -sf},
 * 20001 by default — to every one of them on the way out, carrying the name of the desk that produced the message,
 * which only the producing thread knows. The acceptor decodes that field and prints it, which is the proof it went
 * on the wire inside a well-formed message.
 * <p>
 * Neither {@code FixApplication} mentions the field. That is the point of the plugin API: cross-cutting behaviour
 * (tracing, tagging, compliance stamps, metrics) attaches to a session from the outside, and the applications stay
 * about the business messages.
 * <p>
 * Read {@link StampingFixSessionsPlugin} first — it is the whole of the API — then {@link #call()} for how it is
 * registered, and {@link #getFixSessionSetting} for how a session asks for it. The guide is
 * {@code docs/session-plugins.md}.
 * <p>
 * Run with -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints --enable-native-access=ALL-UNNAMED --add-opens java.base/jdk.internal.ref=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED
 */
@Slf4j
@CommandLine.Command(name = "PluginApiExample", mixinStandardHelpOptions = true, version = "1.0",
        description = "Session plugin stamping a user defined field onto every outbound Email", showDefaultValues = true)
public class PluginApiExample extends FixExamplesBase implements Callable<Integer> {

    private static final String STAMPING_PLUGIN_INSTANCE_ID = "email-stamping";

    /**
     * Which desk the current thread is sending for. Stands in for whatever per-thread context a real application
     * has — a tenant, a user, a trace span — and is the reason the plugin reads its value on the producing thread
     * rather than when the message is encoded: this thread local is not visible from the session's I/O thread.
     */
    private static final ThreadLocal<String> SENDING_DESK = new ThreadLocal<>();

    @CommandLine.Option(names = "-ei", description = "Interval in millis between two Emails sent by a session", defaultValue = "1000")
    private long emailIntervalInMillis;

    @CommandLine.Option(names = "-sf", description = "User defined tag the plugin stamps outbound Emails with, in the 5000..39999 range", defaultValue = "20001")
    private int stampFieldCode;

    @CommandLine.ArgGroup(exclusive = false)
    private ExampleOptions exampleOptions = new ExampleOptions();

    @CommandLine.ArgGroup(exclusive = false)
    private Profiling.ProfilingOptions profilingOptions = new Profiling.ProfilingOptions();

    public static void main(String... args) {
        System.exit(new CommandLine(new PluginApiExample()).execute(args));
    }

    @Override
    public Integer call() {
        logConfiguration(log, this);
        Duration exampleDuration = Duration.ofSeconds(exampleOptions.getDuration());
        Duration emailInterval = Duration.ofMillis(emailIntervalInMillis);

        waitForUserInput(log);

        FixEngineBuilder.FixEngineBuilderBuilder<?, ?> engineBuilder = fixEngineBuilder(exampleOptions, true,
                sid -> new EmailReceivingApplication(stampFieldCode),
                sid -> new EmailSendingApplication(emailInterval),
                sid -> Collections.emptyMap(),
                sid -> Collections.emptyMap());

        // The plugin is registered on the engine, like a store or a logger, and named by the sessions that want it
        // in getFixSessionSetting below. One instance serves every session: it is asked per session for a
        // per-session plugin, and declines the sessions that do not send Email — which here is the acceptor's.
        engineBuilder.fixSessionsPlugin(StampingFixSessionsPluginSettings.builder()
                .instanceId(STAMPING_PLUGIN_INSTANCE_ID)
                .stampedMessageType(MessageTypes.Email)
                .stampFieldCode(stampFieldCode)
                // called on the producing thread, from every session that has the plugin, so it must be
                // thread-safe: reading a thread local is, by construction
                .stampSupplier(SENDING_DESK::get)
                .build());

        FixEngine fixEngine = engineBuilder.build().instance().start();

        FixAcceptor fixAcceptor = fixEngine.newAcceptor(getFixAcceptorBuilder(exampleOptions).build()).start();

        List<FixInitiator> initiators = new ArrayList<>();
        for (int i = 1; i <= exampleOptions.getClientsCount(); i++) {
            initiators.add(fixEngine.newInitiator(getFixInitiatorBuilder(getInitiatorSessionId(i),
                    getInitiatorSenderCompId(i), exampleOptions).build()).start());
        }

        Profiling.startProfilingIfNeeded(profilingOptions, PluginApiExample.class);
        registerShutdownHook(initiators, null, fixAcceptor);
        LockSupport.parkNanos(exampleDuration.toNanos());
        shutdown(initiators, null, fixAcceptor);
        return 0;
    }

    /**
     * Both sessions name the plugin by its instance id and by the class it is addressed as — the same indirection
     * stores, loggers and applications use, so which plugin a session gets is configuration rather than code. The
     * acceptor's session names it too and is declined: a session that does not send the stamped message type never
     * acquires a plugin instance.
     */
    @Override
    public FixSessionSettings getFixSessionSetting(FixSessionId sid, FixSession.FixSessionType sessionType,
                                                   String targetInstancesId, boolean resetSequenceOnLogon,
                                                   Function<FixSessionId, Map<FixApplicationSessionSettingDescriptor, String>> fixApplicationSessionSettings,
                                                   ExampleOptions options) {
        return super.getFixSessionSetting(sid, sessionType, targetInstancesId, resetSequenceOnLogon,
                        fixApplicationSessionSettings, options).toBuilder()
                .fixSessionPluginsInstanceId(StampingFixSessionsPlugin.class, STAMPING_PLUGIN_INSTANCE_ID)
                .build();
    }

    /**
     * Sends the Emails, from a thread of its own, and knows nothing about the stamp.
     */
    @RequiredArgsConstructor
    private static final class EmailSendingApplication implements FixApplication {

        private final Duration emailInterval;
        private volatile boolean sending;

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings settings, FixSession session,
                                             Set<MessageType> encodedMessagesTypes) {
            // this declaration is what the plugin reads to decide it wants this session
            encodedMessagesTypes.add(MessageTypes.Email);
            return List.of();
        }

        @Override
        public void onLogon(FixSession session, DecodedFixMessage logon) {
            String desk = session.getFixSessionId().getSenderCompID().getValue() + "-desk";
            log.info("Logon initiator {}, sending an Email every {}", session.getFixSessionId(), emailInterval);
            sending = true;
            // Off the I/O thread on purpose: the plugin's getMessageEncodingToken runs here, on this thread, while
            // its onMessageEncodedBody runs later on the session's I/O thread. That hop is what the encoding token
            // exists for, and sending from an application thread is what makes it visible.
            Thread sender = new Thread(() -> sendEmails(session, desk), desk);
            sender.setDaemon(true);
            sender.start();
        }

        @Override
        public void onLogout(FixSession session, String message, DecodedFixMessage logoutMessage) {
            log.info("Logout initiator {} : {}", session.getFixSessionId(), message);
            sending = false;
        }

        private void sendEmails(FixSession session, String desk) {
            SENDING_DESK.set(desk);
            for (long i = 1; sending; i++) {
                try {
                    // a fresh encoder per message: a reusable one belongs to a single thread, and this one is not
                    // the session's I/O thread - see docs/threading-model.md
                    EmailEncoder email = session.newEncoder(EmailEncoder.class);
                    email.begin()
                            .setEmailThreadID("thread-" + i)
                            .setEmailType(EmailType.EmailTypeValues.NEW)
                            .setSubject("Daily commentary " + i)
                            .addNoLinesOfText(1)
                            .setText("Nothing to report.");
                    session.send(email, null);
                    Thread.sleep(emailInterval.toMillis());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException ex) {
                    // the session going down under us while shutting the example down
                    log.info("{} stopped sending: {}", desk, ex.toString());
                    return;
                }
            }
        }
    }

    /**
     * Decodes the Emails, including the field no application wrote.
     */
    @RequiredArgsConstructor
    private static final class EmailReceivingApplication implements FixApplication {

        private final int stampFieldCode;

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings settings, FixSession session,
                                             Set<MessageType> encodedMessagesTypes) {
            return List.of(new EmailDecoder(stampFieldCode));
        }

        @Override
        public void onLogon(FixSession session, DecodedFixMessage logon) {
            log.info("Logon acceptor {}", session.getFixSessionId());
        }

        @Override
        public void onLogout(FixSession session, String message, DecodedFixMessage logoutMessage) {
            log.info("Logout acceptor {} : {}", session.getFixSessionId(), message);
        }

        @Setter
        @RequiredArgsConstructor
        private static final class EmailDecoder implements FixMessageDecoder {

            private final int stampFieldCode;
            private String emailThreadId;
            private String subject;
            private String sendingDesk;

            @Override
            public MessageType getMessageType() {
                return MessageTypes.Email;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper mapper, FieldsRegistry fieldsRegistry) {
                // The receiving side has to know the tag as well: registering it here puts it in this session's
                // registry, so it is a known field rather than an unknown one, and mapping it is what makes it
                // parsed at all. A field nobody maps is never read.
                FixField stampField = fieldsRegistry
                        .addUserDefinedField(stampFieldCode, FieldType.STRING, FieldLocation.BODY);
                mapper.mapStringField(EmailThreadID.get(), this::setEmailThreadId, null)
                        .mapStringField(Subject.get(), this::setSubject, null)
                        .mapStringField(stampField, this::setSendingDesk, "<unstamped>");
            }

            @Override
            public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
                log.info("acceptor: Email {} \"{}\": tag {} says it came from {}",
                        emailThreadId, subject, stampFieldCode, sendingDesk);
            }
        }
    }
}
