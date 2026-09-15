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
import org.lolaf.staffix.api.FixAcceptor;
import org.lolaf.staffix.api.FixInitiator;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Spring Boot example demonstrating the {@code staffix-spring-boot-starter}: an acceptor and an
 * initiator are declared entirely in {@code application.properties}, and the user's only job is
 * to provide {@link FixApplication} beans referenced by name from each session config.
 *
 * <p>Run with: {@code mvn -pl examples -am package} then
 * {@code java -jar examples/target/staffix-examples.jar} (or run this main directly
 * from your IDE). The acceptor binds to {@code localhost:17001} and the initiator connects to it.
 * <p>
 * Run with -XX:+UnlockDiagnosticVMOptions -XX:-RestrictContended -XX:ContendedPaddingWidth=64 -XX:+DebugNonSafepoints --enable-native-access=ALL-UNNAMED --add-opens java.base/jdk.internal.ref=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED
 */
@Slf4j
@SpringBootApplication
public class SpringBootExample {

    public static void main(String[] args) {
        ApplicationContext ctx = SpringApplication.run(SpringBootExample.class, args);
        FixAcceptor acceptor = ctx.getBean("fixAcceptor-primary", FixAcceptor.class);
        FixInitiator initiator = ctx.getBean("fixInitiator-primary", FixInitiator.class);
        log.info("Started: acceptor started={} initiator started ={} initiator connected={}",
                acceptor.isStarted(),
                initiator.isStarted(),
                initiator.isConnected());
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(60));
    }

    @Bean(name = "staffixScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService staffixScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "staffix-spring-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean("acceptorApp")
    public FixApplication acceptorApp() {
        return new LoggingFixApplication("ACCEPTOR");
    }

    @Bean("initiatorApp")
    public FixApplication initiatorApp() {
        return new LoggingFixApplication("INITIATOR");
    }

    /**
     * Minimal {@link FixApplication} that just logs lifecycle events. Real applications would
     * register message decoders and implement business logic in the {@code on*} callbacks.
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
