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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.betty.api.settings.IOWorkersGroupSettings;
import org.lolaf.betty.api.ss.WakeupSelectStrategy;
import org.lolaf.ringos.idling.WaitNotifyIdleStrategy;
import org.lolaf.ringos.threading.FastThreadLocalThread;
import org.lolaf.staffix.api.*;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.executor.MessageExecutorSettings;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.stores.loggers.slf4j.Slf4jMessagesLoggerSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.file.FileSessionsSettingsStoreSettings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Programmatic example wiring a {@link FixEngine} whose acceptor and initiator sessions are loaded
 * from hand-authored YAML files through the YAML file session settings store
 * ({@code staffix-sessions-settings-store-file}), showing both ways it can be pointed at them.
 *
 * <p>Each store points at a directory holding one YAML file per session plus a {@code default.yaml}
 * whose values are merged into every other file in the directory (so the per-session files only carry
 * what is unique to them). The acceptor accepts two counterparties, {@code INITIATOR_1} and
 * {@code INITIATOR_2}, and two initiators connect back to it.
 *
 * <p>{@code initiator/default.yaml} carries a {@code ${...}} placeholder on its heartbeat interval,
 * resolved from a system property when the file is loaded and falling back to the value written after the
 * second colon - so the example runs unchanged, and {@code -Dstaffix.example.heartbeat=PT5S} changes it
 * without editing the file.
 *
 * <p>The bundled YAML files live on the classpath under {@code sessions/acceptor} and
 * {@code sessions/initiator}. The acceptor store reads a directory, so its files are first copied to
 * {@code ./target/file-sessions/acceptor}; a directory-backed store also writes changed settings back. The
 * initiator store reads its files straight from the classpath as URIs, which needs no copy but is read-only.
 *
 * <p>Run with: {@code mvn -pl examples/file-session-settings -am install} then
 * {@code ./FileSessionSettingsExample.sh} (or run this main directly from your IDE). The acceptor binds
 * to {@code localhost:7001} and both initiators connect to it.
 * <p>
 * Run with --enable-native-access=ALL-UNNAMED --add-opens java.base/jdk.internal.ref=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED
 */
@Slf4j
public final class FileSessionSettingsExample {

    private static final String ACCEPTOR = "ACCEPTOR";
    private static final String INITIATOR = "INITIATOR";

    private static final InetSocketAddress BIND_ADDRESS = new InetSocketAddress("localhost", 7001);

    public static void main(String[] args) throws IOException {
        log.info("File session settings example running with Java {}", Runtime.version());

        File acceptorSessionsDir = materializeSessionsDir("sessions/acceptor", "./target/file-sessions/acceptor",
                "default.yaml", "acceptor-initiator1.yaml", "acceptor-initiator2.yaml");

        FixEngine fixEngine = FixEngineBuilder.builder()
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .instanceId(ACCEPTOR)
                        .application("acceptorApp", new LoggingFixApplication(ACCEPTOR))
                        .build())
                .fixApplicationFactory(SimpleApplicationFactorySettings.builder()
                        .instanceId(INITIATOR)
                        .application("initiatorApp", new LoggingFixApplication(INITIATOR))
                        .build())
                .fixMessagesStore(MemoryMessageStoreSettings.builder().instanceId(ACCEPTOR).maxEntriesInMemory(1024).build())
                .fixMessagesStore(MemoryMessageStoreSettings.builder().instanceId(INITIATOR).maxEntriesInMemory(1024).build())
                .fixMessagesLogger(Slf4jMessagesLoggerSettings.builder().instanceId(ACCEPTOR).messageFieldsDelimiter('|').build())
                .fixMessagesLogger(Slf4jMessagesLoggerSettings.builder().instanceId(INITIATOR).messageFieldsDelimiter('|').build())
                .fixSessionsSettingsStore(FileSessionsSettingsStoreSettings.builder()
                        .instanceId(ACCEPTOR)
                        .fixSessionSettingsDirectory(acceptorSessionsDir)
                        .build())
                .fixSessionsSettingsStore(FileSessionsSettingsStoreSettings.builder()
                        .instanceId(INITIATOR)
                        .fixSessionSettingsUri(classpathUri("sessions/initiator/default.yaml"))
                        .fixSessionSettingsUri(classpathUri("sessions/initiator/initiator1.yaml"))
                        .fixSessionSettingsUri(classpathUri("sessions/initiator/initiator2.yaml"))
                        .build())
                .build()
                .instance()
                .start();

        FixSessionsSettingsStore initiatorsFileStore = fixEngine.getFixSessionsSettingsStores().stream().filter(s -> s.getInstanceId().equals(INITIATOR)).findFirst().orElseThrow();

        FixAcceptor acceptor = fixEngine.newAcceptor(FixAcceptorBuilder.builder()
                .instanceId(ACCEPTOR)
                .bindAddress(BIND_ADDRESS)
                .targetFixSessionsSettingsStoreInstancesId(ACCEPTOR)
                .build()).start();

        List<FixInitiator> initiators = new ArrayList<>();
        for (FixSessionSettings initiatorSettings : initiatorsFileStore.getSettings()) {
            FixInitiator initiator = fixEngine.newInitiator(FixInitiatorBuilder.builder()
                    .instanceId(initiatorSettings.getFixSessionId().getSenderCompID().getValue())
                    .connectionRetry(Duration.ofSeconds(1))
                    .fixSessionId(initiatorSettings.getFixSessionId())
                    .connectAddress(BIND_ADDRESS)
                    .messageExecutorSettings(MessageExecutorSettings.builder().idleStrategy(WaitNotifyIdleStrategy::new).build())
                    .ioWorkersGroup(IOWorkersGroupSettings.builder()
                            .ioThreadGroup(IOWorkersGroupSettings.IOThreadGroup.builder()
                                    .selectStrategy(new WakeupSelectStrategy(10))
                                    .threadFactory(FastThreadLocalThread::new)
                                    .build())
                            .build().newInstance())
                    .build()).start();
            initiators.add(initiator);
        }

        log.info("Started: acceptor started={} bound to {}, {} initiators started", acceptor.isStarted(), BIND_ADDRESS, initiators.size());
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(60));
        log.info("Shutting down {} initiators and 1 acceptor", initiators.size());
        initiators.forEach(FixInitiator::stop);
        acceptor.stop();
    }

    /**
     * Copies the named classpath resources from {@code classpathDir} into {@code targetDir} (creating it
     * if needed) and returns the target directory so the file session settings store can read it.
     */
    private static File materializeSessionsDir(String classpathDir, String targetDir, String... fileNames) throws IOException {
        File dir = new File(targetDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Unable to create sessions directory " + dir);
        }
        for (String fileName : fileNames) {
            String resource = "/" + classpathDir + "/" + fileName;
            try (InputStream in = FileSessionSettingsExample.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("Missing bundled session resource " + resource);
                }
                Files.copy(in, dir.toPath().resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        log.info("Materialized {} session files into {}", fileNames.length, dir.getAbsolutePath());
        return dir;
    }

    private static URI classpathUri(String resource) {
        URL url = FileSessionSettingsExample.class.getResource("/" + resource);
        if (url == null) {
            throw new IllegalStateException("Missing bundled session resource " + resource);
        }
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid URI for session resource " + resource, e);
        }
    }

    /**
     * Minimal {@link FixApplication} that just logs lifecycle events (copied from the Spring Boot example).
     * Real applications would register message decoders and implement business logic in the {@code on*} callbacks.
     */
    @Slf4j
    static final class LoggingFixApplication implements FixApplication {

        private final String role;

        LoggingFixApplication(String role) {
            this.role = role;
        }

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
            return Collections.emptyList();
        }

        @Override
        public void onHeartbeat(FixSession fixSession, UTCTime remoteTimestamp) {
            log.info("[{}] Heartbeat received", role);
        }

        @Override
        public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
            log.info("[{}] Logon for session {}", role, fixSession.getFixSessionId());
        }

        @Override
        public void onLogout(FixSession fixSession, String message, DecodedFixMessage logoutMessage) {
            log.info("[{}] Logout for session {}: {}", role, fixSession.getFixSessionId(), message);
        }
    }
}
