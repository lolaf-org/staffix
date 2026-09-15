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
package org.lolaf.staffix.stores.sessions.file;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.io.File;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class TestFileFixSessionsSettingsStore {

    @TempDir
    File tempDir;

    ObjectMapper mapper;

    @BeforeEach
    void setup() {
        mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();
        mapper.configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
    }

    private FileFixSessionsSettingsStore newStore(File directory) {
        return new FileFixSessionsSettingsStore.FileStoreFactoryImpl()
                .newInstance(FileSessionsSettingsStoreSettings.builder()
                        .instanceId("test")
                        .fixSessionSettingsDirectory(directory)
                        .build());
    }

    private FixSessionSettings session(String senderCompID, String targetCompID) {
        return FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                        .id(senderCompID)
                        .senderCompID(senderCompID)
                        .targetCompID(targetCompID)
                        .build()))
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .build();
    }

    @Test
    void addWritesOneFilePerSessionAndRemoveDeletesIt() {
        FileFixSessionsSettingsStore store = newStore(tempDir);
        store.start();

        FixSessionSettings settings = session("SENDER", "TARGET");
        store.add(settings);

        File expected = new File(tempDir, settings.getFixSessionId().forFileName(".yaml"));
        assertThat(expected).isFile();
        assertThat(store.getSettings()).containsExactly(settings);

        store.remove(settings);

        assertThat(expected).doesNotExist();
        assertThat(store.getSettings()).isEmpty();
    }

    @Test
    void startLoadsSessionFilesFromDirectory() {
        FixSessionSettings a = session("SENDER-A", "TARGET-A");
        FixSessionSettings b = session("SENDER-B", "TARGET-B");

        FileFixSessionsSettingsStore writer = newStore(tempDir);
        writer.start();
        writer.add(a);
        writer.add(b);

        FileFixSessionsSettingsStore reader = newStore(tempDir);
        reader.start();

        assertThat(reader.getSettings()).hasSize(2);
        assertThat(reader.find(a.getFixSessionId(), FixSession.FixSessionType.ACCEPTOR)).isPresent();
        assertThat(reader.find(b.getFixSessionId(), FixSession.FixSessionType.ACCEPTOR)).isPresent();
    }

    @Test
    void loadReadsFromDiskWithoutMutatingManagedSettings() {
        FileFixSessionsSettingsStore store = newStore(tempDir);
        store.start();
        assertThat(store.getSettings()).isEmpty();

        FixSessionSettings settings = session("SENDER", "TARGET");
        newStore(tempDir).add(settings);

        // load() returns what is on disk but must not touch the in-memory managed settings
        assertThat(store.load()).hasSize(1)
                .anySatisfy(s -> assertThat(s.getFixSessionId()).isEqualTo(settings.getFixSessionId()));
        assertThat(store.getSettings()).isEmpty();
        assertThat(store.find(settings.getFixSessionId(), FixSession.FixSessionType.ACCEPTOR)).isEmpty();
    }

    @Test
    void defaultYamlIsMergedIntoSessionsAndNotLoadedAsSession() throws IOException {
        YamlFixSessionSettings defaults = YamlFixSessionSettings.builder()
                .dictionaryId("fromDefault")
                .build();
        YamlFixSessionSettings sessionYaml = YamlFixSessionSettings.builder()
                .fixSessionId(YamlFixSessionSettings.FixSessionId.builder()
                        .id("session1")
                        .fixVersion(FixRegularVersion.VERSION_44.toString())
                        .senderCompID("SENDER")
                        .targetCompID("TARGET")
                        .build())
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .build();
        mapper.writeValue(new File(tempDir, "default.yaml"), defaults);
        mapper.writeValue(new File(tempDir, "session1.yaml"), sessionYaml);

        FileFixSessionsSettingsStore store = newStore(tempDir);
        store.start();

        assertThat(store.getSettings()).hasSize(1);
        assertThat(store.getSettings()).first()
                .extracting(FixSessionSettings::getDictionaryId).isEqualTo("fromDefault");
    }

    @Test
    void startAutoCreatesMissingDirectory() {
        File missing = new File(tempDir, "does-not-exist-yet");
        assertThat(missing).doesNotExist();

        FileFixSessionsSettingsStore store = newStore(missing);
        store.start();

        assertThat(missing).isDirectory();
        assertThat(store.getSettings()).isEmpty();
    }
}
