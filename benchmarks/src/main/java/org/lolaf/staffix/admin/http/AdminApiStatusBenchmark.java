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
package org.lolaf.staffix.admin.http;

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixAcceptorBuilder;
import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The cost of the console's poll on an engine with one acceptor of {@code sessions} sessions. {@code status} runs
 * what {@code GET status} runs on the engine, so its {@code gc.alloc.rate.norm} is the engine's allocation per poll;
 * {@code httpStatus} is the round trip, whose allocation also counts the client's. In this package for
 * {@link EngineStatus#of}.
 */
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Fork(value = 1, jvmArgsPrepend = {"--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED", "-Xmx1g", "-Xms1g"})
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class AdminApiStatusBenchmark {

    private static final int PORT = 18686;
    private static final String TOKEN = "benchmark";

    @Benchmark
    public byte[] status(EngineState state) throws IOException {
        AdminApi adminApi = state.adminApi;
        Map<FixSessionId, FixSession> running = SessionsDocument.running(adminApi);
        return AdminApiHandler.MAPPER.writeValueAsBytes(EngineStatus.of(adminApi, running, state.sessionsVersion));
    }

    @Benchmark
    public byte[] httpStatus(EngineState state) throws IOException, InterruptedException {
        return state.client.send(state.statusRequest, HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    @State(Scope.Benchmark)
    public static class EngineState {
        @Param({"10", "100", "500", "2000"})
        int sessions;
        FixEngine engine;
        AdminApi adminApi;
        String sessionsVersion;
        HttpClient client;
        HttpRequest statusRequest;

        @Setup
        public void setup() throws IOException {
            SimpleApplicationFactorySettings.SimpleApplicationFactorySettingsBuilder applications = SimpleApplicationFactorySettings.builder();
            MemorySessionsSettingsStoreSettings.MemorySessionsSettingsStoreSettingsBuilder store =
                    MemorySessionsSettingsStoreSettings.builder().instanceId("acceptor-sessions");
            for (int i = 0; i < sessions; i++) {
                FixSessionId id = FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                        .group("alpha").name("session-" + i).senderCompID("ACCEPTOR-" + i).targetCompID("INITIATOR").build());
                applications.application(id.getQualifiedName(), new IdleApplication());
                store.fixSessionSetting(FixSessionSettings.builder()
                        .fixSessionId(id)
                        .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                        .fixApplicationInstanceId(id.getQualifiedName())
                        .dictionaryId("benchmarks")
                        .build());
            }
            engine = FixEngineBuilder.builder()
                    .instanceId("benchmark")
                    .adminApiExporter(HttpAdminApiSettings.builder().bindAddress("127.0.0.1").port(PORT).apiToken(TOKEN).build())
                    .fixMessagesStore(MemoryMessageStoreSettings.builder().build())
                    .fixSessionsSettingsStore(store.build())
                    .fixApplicationFactory(applications.build())
                    .build().instance().start();
            engine.newAcceptor(FixAcceptorBuilder.builder()
                    .instanceId("acceptor")
                    .bindAddress(new InetSocketAddress("127.0.0.1", 0))
                    .targetFixSessionsSettingsStoreInstancesIds(List.of("acceptor-sessions"))
                    .build()).start();
            adminApi = (AdminApi) engine;
            sessionsVersion = SessionsDocument.of(adminApi, SessionsDocument.running(adminApi)).version;
            client = HttpClient.newHttpClient();
            statusRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/engines/benchmark/status"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .build();
        }

        @TearDown
        public void tearDown() {
            engine.stop(Deadline.of(Duration.ofSeconds(5)));
        }
    }

    private static final class IdleApplication implements FixApplication {

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings settings, FixSession session, Set<MessageType> encodedMessagesTypes) {
            return List.of();
        }
    }
}
