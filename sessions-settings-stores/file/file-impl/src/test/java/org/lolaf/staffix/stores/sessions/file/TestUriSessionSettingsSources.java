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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lolaf.staffix.api.session.FixSessionSettings;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loading session settings from URIs rather than a local directory.
 */
class TestUriSessionSettingsSources {

    private static final String PROPERTY = "staffix.test.uri.prefix";

    @TempDir
    File tempDir;

    private static String session(String id) {
        return "fixSessionId:\n"
                + "  id: \"" + id + "\"\n"
                + "  senderCompID: \"SENDER\"\n"
                + "  targetCompID: \"TARGET-" + id + "\"\n"
                + "  fixVersion: \"FIX.4.4\"\n"
                + "fixSessionType: ACCEPTOR\n";
    }

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void loadsEverySessionNamedByAUri() throws IOException {
        URI one = write("one.yaml", session("one"));
        URI two = write("two.yaml", session("two"));

        assertThat(ids(load(one, two))).containsExactlyInAnyOrder("one", "two");
    }

    @Test
    void mergesTheDefaultsNamedByAUri() throws IOException {
        URI defaults = write("default.yaml", "logInOrOutResponseTimeout: \"PT45S\"\n");
        URI one = write("one.yaml", session("one"));

        assertThat(load(defaults, one)).singleElement()
                .satisfies(s -> assertThat(s.getLogInOrOutResponseTimeout())
                        .isEqualTo(Duration.ofSeconds(45)));
    }

    @Test
    void refusesTwoDefaultsSources() throws IOException {
        URI first = write("a/default.yaml", "logInOrOutResponseTimeout: \"PT45S\"\n");
        URI second = write("b/default.yaml", "logInOrOutResponseTimeout: \"PT30S\"\n");

        assertThatThrownBy(() -> load(first, second, write("one.yaml", session("one"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default.yaml");
    }

    @Test
    void refusesTheSameSessionFromTwoUris() throws IOException {
        URI first = write("a/one.yaml", session("one"));
        URI second = write("b/one.yaml", session("one"));

        assertThatThrownBy(() -> load(first, second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("two sources");
    }

    @Test
    void refusesADirectoryAndUrisTogether() throws IOException {
        URI one = write("one.yaml", session("one"));

        assertThatThrownBy(() -> new FileFixSessionsSettingsStore.FileStoreFactoryImpl()
                .newInstance(FileSessionsSettingsStoreSettings.builder()
                        .instanceId("test")
                        .fixSessionSettingsDirectory(tempDir)
                        .fixSessionSettingsUri(one)
                        .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not both");
    }

    @Test
    void refusesNeitherADirectoryNorUris() {
        assertThatThrownBy(() -> new FileFixSessionsSettingsStore.FileStoreFactoryImpl()
                .newInstance(FileSessionsSettingsStoreSettings.builder().instanceId("test").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("neither");
    }

    /**
     * A URI-backed store has nowhere to write, so a write is skipped rather than refused - and nothing is
     * left on disk.
     */
    @Test
    void skipsWritesRatherThanFailing() throws IOException {
        // Named so it differs from what forFileName would produce, or the source itself would satisfy
        // the assertion below.
        URI one = write("source.yaml", session("one"));
        File source = new File(tempDir, "source.yaml");
        String before = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        FileFixSessionsSettingsStore store = store(List.of(one));
        FixSessionSettings loaded = store.load().iterator().next();
        store.add(loaded);

        assertThat(new File(tempDir, loaded.getFixSessionId().forFileName(".yaml"))).doesNotExist();
        assertThat(source).content().isEqualTo(before);
    }

    @Test
    void resolvesPlaceholdersInAUriSource() throws IOException {
        System.setProperty(PROPERTY, "uat-");
        URI one = write("one.yaml", session("${sysprop:" + PROPERTY + ":}one"));

        assertThat(ids(load(one))).containsExactly("uat-one");
    }

    /**
     * The classpath case: the caller resolves the resource itself, so the store needs no {@code classpath:}
     * scheme of its own.
     */
    @Test
    void loadsAClasspathResourceResolvedByTheCaller() throws URISyntaxException {
        URI onClasspath = getClass().getResource("/uri-sessions/classpath-session.yaml").toURI();

        assertThat(ids(load(onClasspath))).containsExactly("classpath-session");
    }

    private Set<FixSessionSettings> load(URI... uris) {
        return store(List.of(uris)).load();
    }

    private FileFixSessionsSettingsStore store(List<URI> uris) {
        return new FileFixSessionsSettingsStore.FileStoreFactoryImpl()
                .newInstance(FileSessionsSettingsStoreSettings.builder()
                        .instanceId("test")
                        .fixSessionSettingsUris(uris)
                        .build());
    }

    private List<String> ids(Set<FixSessionSettings> loaded) {
        return loaded.stream().map(s -> s.getFixSessionId().getId()).collect(java.util.stream.Collectors.toList());
    }

    private URI write(String name, String body) throws IOException {
        File file = new File(tempDir, name);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), body.getBytes(StandardCharsets.UTF_8));
        return file.toURI();
    }
}
