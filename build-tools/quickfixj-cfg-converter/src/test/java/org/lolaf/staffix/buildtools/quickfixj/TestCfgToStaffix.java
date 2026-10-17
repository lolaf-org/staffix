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
package org.lolaf.staffix.buildtools.quickfixj;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.impl.session.FixSessionSettingsValidator;
import org.lolaf.staffix.impl.session.ScheduleProbe;
import org.lolaf.staffix.spring.boot.mapper.AddressUtils;
import org.lolaf.staffix.spring.boot.props.StaffixProperties;
import org.lolaf.staffix.stores.sessions.file.FileFixSessionsSettingsStore;
import org.lolaf.staffix.stores.sessions.file.FileSessionsSettingsStoreSettings;
import org.lolaf.staffix.stores.sessions.memory.spring.FixSessionSettingsMapper;
import org.lolaf.staffix.stores.sessions.memory.spring.MemorySessionsSettingsStoreProps;
import org.lolaf.staffix.stores.sessions.memory.spring.MemoryStoreInstanceProps;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.convert.ApplicationConversionService;
import quickfix.DefaultSessionSchedule;
import quickfix.SessionID;
import quickfix.SystemTime;
import quickfix.SystemTimeSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Converts the fixtures and checks each end: the values QuickFIX/J resolves are the ones converted, the YAML loads
 * into staffix's file store, each schedule is up exactly when QuickFIX/J's is, and the Spring properties bind to the
 * same settings as the YAML.
 */
class TestCfgToStaffix {

    private static final List<String> FIXTURES = List.of("banzai.cfg", "executor.cfg", "options.cfg");
    private static final Path FIXTURE_DIR = Path.of("src/test/resources/cfg");
    private static final Path EXPECTED_DIR = Path.of("src/test/resources/expected");
    /**
     * A week without a daylight saving change, starting on a Monday, sampled off the quarter hours so that no sample
     * falls on an open or a close.
     */
    private static final Instant WEEK_START = Instant.parse("2026-06-01T00:07:00Z");

    @TempDir
    static Path out;

    private static CfgToStaffix yaml;

    @BeforeAll
    static void convert() throws IOException {
        yaml = convert(CfgToStaffix.Format.YAML, "--initiates-reset", "true");
        convert(CfgToStaffix.Format.SPRING, "--initiates-reset", "true");
    }

    @AfterEach
    void restoreQuickfixjClock() {
        SystemTime.setTimeSource(null);
    }

    @Test
    void convertsTheValuesQuickfixjResolves() throws Exception {
        Map<SessionID, Map<String, String>> quickfixj = new HashMap<>();
        for (String fixture : FIXTURES) {
            quickfix.SessionSettings settings = quickfixjSettings(fixture);
            for (Iterator<SessionID> ids = settings.sectionIterator(); ids.hasNext(); ) {
                SessionID id = ids.next();
                quickfixj.put(id, toMap(settings.getSessionProperties(id, true)));
            }
        }

        assertThat(yaml.sessions).hasSize(quickfixj.size());
        for (CfgToStaffix.Session session : yaml.sessions) {
            assertThat(session.values).as(session.where()).isEqualTo(quickfixj.get(sessionId(session)));
        }
    }

    @Test
    void theYamlLoadsIntoTheFileStoreAndValidates() throws IOException {
        Map<String, FixSessionSettings> loaded = loadYaml();

        assertThat(loaded.keySet()).containsExactlyInAnyOrderElementsOf(
                yaml.sessions.stream().map(s -> s.name).collect(Collectors.toList()));
        loaded.values().forEach(FixSessionSettingsValidator::validate);
    }

    @Test
    void eachScheduleIsUpWhenQuickfixjsIs() throws Exception {
        Map<String, FixSessionSettings> loaded = loadYaml();
        for (String fixture : FIXTURES) {
            quickfix.SessionSettings settings = quickfixjSettings(fixture);
            for (CfgToStaffix.Session session : yaml.sessions) {
                if (!session.file.getFileName().toString().equals(fixture)) {
                    continue;
                }
                DefaultSessionSchedule schedule = new DefaultSessionSchedule(settings, sessionId(session));
                for (Instant at = WEEK_START; at.isBefore(WEEK_START.plus(Duration.ofDays(8)));
                     at = at.plus(Duration.ofMinutes(15))) {
                    Instant now = at;
                    SystemTime.setTimeSource(new SystemTimeSource() {
                        @Override
                        public long getTime() {
                            return now.toEpochMilli();
                        }

                        @Override
                        public LocalDateTime getNow() {
                            return LocalDateTime.ofInstant(now, ZoneOffset.UTC);
                        }
                    });

                    assertThat(ScheduleProbe.isWithinSessionTime(loaded.get(session.name), at))
                            .as(session.name + " at " + at).isEqualTo(schedule.isSessionTime());
                }
            }
        }
    }

    @Test
    void theSpringPropertiesBindToTheSettingsTheYamlLoads() throws IOException {
        Binder binder = binder();
        MemorySessionsSettingsStoreProps stores = binder.bind("staffix.sessions-settings-stores-memory",
                MemorySessionsSettingsStoreProps.class).get();
        Map<String, FixSessionSettings> bound = new LinkedHashMap<>();
        for (MemoryStoreInstanceProps store : stores.getInstances().values()) {
            store.getSessions().forEach(props -> {
                FixSessionSettings settings = FixSessionSettingsMapper.toSettings(props).toBuilder()
                        .fixApplicationFactoryInstanceId(InstanceProvider.DEFAULT_INSTANCE_ID).build();
                bound.put(settings.getFixSessionId().getName(), settings);
            });
        }

        assertThat(bound).usingRecursiveComparison().isEqualTo(loadYaml());
    }

    @Test
    void theSpringPropertiesDeclareEachInitiatorAndAcceptor() throws IOException {
        StaffixProperties staffix = binder().bind("staffix", StaffixProperties.class).get();
        Map<String, List<String>> initiators = new LinkedHashMap<>();
        staffix.getInitiators().forEach((name, initiator) -> {
            assertThat(initiator.getMainTarget().getFixSessionId().toFixSessionId().getName()).isEqualTo(name);
            initiators.put(name, initiator.getMainTarget().getConnectAddresses());
            AddressUtils.parseAll(initiator.getMainTarget().getConnectAddresses());
        });
        Map<String, String> acceptors = new LinkedHashMap<>();
        staffix.getAcceptors().forEach((name, acceptor) -> {
            AddressUtils.parse(acceptor.getBindAddress());
            acceptors.put(name, acceptor.getBindAddress() + " " + acceptor.getTargetSessionsSettingsStoreInstanceIds());
        });

        assertThat(initiators).containsOnlyKeys("banzai-exec", "banzai-exec-2", "alt")
                .containsEntry("banzai-exec-2", List.of("localhost:9881", "backup:9891"));
        assertThat(acceptors).containsExactly(
                Map.entry("acceptor-9880", "0.0.0.0:9880 [acceptor-9880]"),
                Map.entry("acceptor-9881", "0.0.0.0:9881 [acceptor-9881]"),
                Map.entry("acceptor-4000", "127.0.0.1:4000 [acceptor-4000]"));
    }

    @Test
    void writesTheExpectedFiles() throws IOException {
        assertThat(files(out)).isEqualTo(files(EXPECTED_DIR));
    }

    private static CfgToStaffix convert(CfgToStaffix.Format format, String... flags) throws IOException {
        List<String> args = new ArrayList<>(List.of("--format", format.name().toLowerCase(), "--out",
                out.resolve(format.name().toLowerCase()).toString()));
        args.addAll(List.of(flags));
        FIXTURES.forEach(fixture -> args.add(FIXTURE_DIR.resolve(fixture).toString()));
        CfgToStaffix converter = new CfgToStaffix(CfgToStaffix.Options.parse(args.toArray(new String[0])));
        for (String fixture : FIXTURES) {
            converter.convert(Path.of(fixture), CfgToStaffix.Cfg.parse(FIXTURE_DIR.resolve(fixture)));
        }
        converter.write();
        return converter;
    }

    private static Map<String, FixSessionSettings> loadYaml() throws IOException {
        Map<String, FixSessionSettings> loaded = new LinkedHashMap<>();
        try (Stream<Path> stores = Files.list(out.resolve("yaml/sessions"))) {
            for (Path store : stores.sorted().collect(Collectors.toList())) {
                FileFixSessionsSettingsStore fileStore = new FileFixSessionsSettingsStore.FileStoreFactoryImpl()
                        .newInstance(FileSessionsSettingsStoreSettings.builder()
                                .instanceId(store.getFileName().toString())
                                .fixSessionSettingsDirectory(store.toFile())
                                .build());
                fileStore.start();
                fileStore.getSettings().forEach(s -> loaded.put(s.getFixSessionId().getName(), s));
                fileStore.stop(org.lolaf.ringos.Deadline.unlimited());
            }
        }
        return loaded;
    }

    private static Binder binder() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(out.resolve("spring/staffix-sessions.properties"))) {
            properties.load(reader);
        }
        return new Binder(List.of(new MapConfigurationPropertySource(properties)), null,
                ApplicationConversionService.getSharedInstance());
    }

    private static quickfix.SessionSettings quickfixjSettings(String fixture) throws Exception {
        try (InputStream in = Files.newInputStream(FIXTURE_DIR.resolve(fixture))) {
            return new quickfix.SessionSettings(in);
        }
    }

    private static SessionID sessionId(CfgToStaffix.Session session) {
        Map<String, String> v = session.values;
        return new SessionID(v.get("BeginString"), v.get("SenderCompID"), v.get("SenderSubID"),
                v.get("SenderLocationID"), v.get("TargetCompID"), v.get("TargetSubID"), v.get("TargetLocationID"),
                v.get("SessionQualifier"));
    }

    private static Map<String, String> toMap(Properties properties) {
        Map<String, String> map = new HashMap<>();
        properties.stringPropertyNames().forEach(key -> map.put(key, properties.getProperty(key)));
        return map;
    }

    private static Map<Path, String> files(Path root) throws IOException {
        Map<Path, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
                if (!file.getFileName().toString().endsWith(".schema.json")) {
                    files.put(root.relativize(file), Files.readString(file));
                }
            }
        }
        return files;
    }
}
