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
import org.lolaf.staffix.api.session.ConfigValuePlaceholder;
import org.lolaf.staffix.api.session.ConfigValueResolver;
import org.lolaf.staffix.api.session.FixSessionSettings;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Placeholders in a session file, resolved on the parsed tree before binding.
 */
class TestSessionSettingsPlaceholders {

    private static final String PROPERTY = "staffix.test.session.placeholder";

    @TempDir
    File tempDir;

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void resolvesAPlaceholderInAStringField() throws IOException {
        System.setProperty(PROPERTY, "RESOLVED");
        writeSession("${sysprop:" + PROPERTY + "}", "PT30S");

        assertThat(load()).singleElement()
                .satisfies(s -> assertThat(s.getFixSessionId().getId()).isEqualTo("RESOLVED"));
    }

    /**
     * The reason resolution happens on the tree: a placeholder stands where a {@code Duration} is
     * declared, and Jackson coerces the resolved string as it would any scalar.
     */
    @Test
    void resolvesAPlaceholderInATypedField() throws IOException {
        System.setProperty(PROPERTY, "PT45S");
        writeSession("SENDER", "${sysprop:" + PROPERTY + "}");

        assertThat(load()).singleElement()
                .satisfies(s -> assertThat(s.getLogInOrOutResponseTimeout())
                        .isEqualTo(Duration.ofSeconds(45)));
    }

    @Test
    void keepsTheTextAroundAPartialPlaceholder() throws IOException {
        System.setProperty(PROPERTY, "ENV");
        writeSession("prefix-${sysprop:" + PROPERTY + "}-suffix", "PT30S");

        assertThat(load()).singleElement()
                .satisfies(s -> assertThat(s.getFixSessionId().getId()).isEqualTo("prefix-ENV-suffix"));
    }

    @Test
    void usesTheDefaultWhenNothingAnswers() throws IOException {
        writeSession("${sysprop:" + PROPERTY + ":FALLBACK}", "PT30S");

        assertThat(load()).singleElement()
                .satisfies(s -> assertThat(s.getFixSessionId().getId()).isEqualTo("FALLBACK"));
    }

    @Test
    void leavesAFileWithNoPlaceholderAlone() throws IOException {
        writeSession("PLAIN", "PT30S");

        assertThat(load()).singleElement()
                .satisfies(s -> assertThat(s.getFixSessionId().getId()).isEqualTo("PLAIN"));
    }

    @Test
    void failsNamingTheFileAndTheFieldWhenNothingAnswers() throws IOException {
        writeSession("${sysprop:" + PROPERTY + "}", "PT30S");

        assertThatThrownBy(this::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPERTY)
                .hasMessageContaining("session1.yaml")
                .hasMessageContaining("fixSessionId.id");
    }

    /**
     * An injected resolver replaces the built-ins rather than joining them, so a deployment can define
     * exactly where configuration comes from.
     */
    @Test
    void usesTheInjectedResolversInsteadOfTheBuiltIns() throws IOException {
        System.setProperty(PROPERTY, "FROM_SYSTEM_PROPERTY");
        writeSession("${sysprop:" + PROPERTY + "}", "PT30S");

        ConfigValueResolver custom = placeholder -> Optional.of("FROM_CUSTOM");

        assertThat(load(List.of(custom))).singleElement()
                .satisfies(s -> assertThat(s.getFixSessionId().getId()).isEqualTo("FROM_CUSTOM"));
    }

    /**
     * The requirement the whole design serves: a file deployed with a placeholder still has it after the
     * store writes the session back.
     */
    @Test
    void writesThePlaceholderBackRatherThanTheResolvedValue() throws IOException {
        System.setProperty(PROPERTY, "RESOLVED");
        writeSession("${sysprop:" + PROPERTY + "}", "PT30S");

        FileFixSessionsSettingsStore store = store(List.of());
        FixSessionSettings loaded = store.load().iterator().next();
        store.add(loaded);

        assertThat(writtenFile(loaded))
                .contains("${sysprop:" + PROPERTY + "}")
                .doesNotContain("RESOLVED");
    }

    @Test
    void writesThePlaceholderBackForATypedField() throws IOException {
        System.setProperty(PROPERTY, "PT45S");
        writeSession("SENDER", "${sysprop:" + PROPERTY + "}");

        FileFixSessionsSettingsStore store = store(List.of());
        FixSessionSettings loaded = store.load().iterator().next();
        store.add(loaded);

        assertThat(writtenFile(loaded))
                .contains("${sysprop:" + PROPERTY + "}")
                .doesNotContain("PT45S");
    }

    /**
     * A placeholder-backed value is owned by its source, so changing it through the store is refused
     * rather than silently written as a literal.
     */
    @Test
    void refusesToWriteAValueThatNoLongerMatchesItsPlaceholder() throws IOException {
        System.setProperty(PROPERTY, "PT45S");
        writeSession("SENDER", "${sysprop:" + PROPERTY + "}");

        FileFixSessionsSettingsStore store = store(List.of());
        FixSessionSettings loaded = store.load().iterator().next();

        assertThatThrownBy(() -> store.add(loaded.toBuilder()
                .logInOrOutResponseTimeout(Duration.ofSeconds(99))
                .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("logInOrOutResponseTimeout")
                .hasMessageContaining("cannot be changed through the store");
    }

    /**
     * A session that was never read from a file has no placeholders to restore.
     */
    @Test
    void writesLiteralsForASessionThatWasNeverRead() throws IOException {
        writeSession("PLAIN", "PT30S");
        FixSessionSettings loaded = store(List.of()).load().iterator().next();

        store(List.of()).add(loaded);

        assertThat(writtenFile(loaded)).contains("PLAIN").doesNotContain("${");
    }

    /**
     * Refreshed once per load, not once per file, so every file in one load sees the same snapshot.
     */
    @Test
    void refreshesTheResolversOncePerLoad() throws IOException {
        writeSession("${anything}", "PT30S");
        Files.write(new File(tempDir, "session2.yaml").toPath(),
                new String(Files.readAllBytes(new File(tempDir, "session1.yaml").toPath()),
                        StandardCharsets.UTF_8).replace("SENDER", "OTHER").getBytes(StandardCharsets.UTF_8));

        AtomicInteger refreshes = new AtomicInteger();
        ConfigValueResolver counting = new ConfigValueResolver() {
            @Override
            public Optional<String> resolve(ConfigValuePlaceholder placeholder) {
                return Optional.of("VALUE");
            }

            @Override
            public void refresh() {
                refreshes.incrementAndGet();
            }
        };

        assertThat(load(List.of(counting))).hasSize(2);
        assertThat(refreshes).hasValue(1);
    }

    /**
     * A placeholder in {@code default.yaml} is resolved on its own file, before the merge, so a session
     * that omits the field inherits the resolved value.
     */
    @Test
    void resolvesAPlaceholderInTheDefaultsFile() throws IOException {
        System.setProperty(PROPERTY, "PT45S");
        writeDefaults("logInOrOutResponseTimeout: \"${sysprop:" + PROPERTY + "}\"\n");
        writeSessionWithoutTimeout("SENDER");

        assertThat(load()).singleElement()
                .satisfies(s -> assertThat(s.getLogInOrOutResponseTimeout())
                        .isEqualTo(Duration.ofSeconds(45)));
    }

    /**
     * The defaults file is resolved but never retained, so its placeholder does not follow a merged value
     * into a session file. {@code default.yaml} is only ever read, so it has nothing to round-trip.
     */
    @Test
    void doesNotLeakADefaultsPlaceholderIntoASessionFile() throws IOException {
        System.setProperty(PROPERTY, "PT45S");
        writeDefaults("logInOrOutResponseTimeout: \"${sysprop:" + PROPERTY + "}\"\n");
        writeSessionWithoutTimeout("SENDER");

        FileFixSessionsSettingsStore store = store(List.of());
        FixSessionSettings loaded = store.load().iterator().next();
        store.add(loaded);

        // PT45S rather than a value Jackson would normalise, e.g. PT77S written back as PT1M17S.
        assertThat(writtenFile(loaded)).contains("PT45S").doesNotContain("${");
    }

    /**
     * List elements are matched index-wise, so a placeholder inside one survives the round trip.
     */
    @Test
    void restoresAPlaceholderInsideAList() throws IOException {
        System.setProperty(PROPERTY, "10.0.0.1");
        writeSessionWith("allowedAddresses:\n  - \"${sysprop:" + PROPERTY + "}\"\n  - \"10.0.0.2\"\n");

        FileFixSessionsSettingsStore store = store(List.of());
        FixSessionSettings loaded = store.load().iterator().next();
        assertThat(loaded.getAllowedAddresses()).hasSize(2);
        store.add(loaded);

        assertThat(writtenFile(loaded))
                .contains("${sysprop:" + PROPERTY + "}")
                .doesNotContain("10.0.0.1");
    }

    /**
     * A resized list cannot be matched up index-wise, so nothing is restored and the literal is written -
     * without rejecting the write, since the list as a whole is what changed.
     */
    @Test
    void writesLiteralsWhenAListHasBeenResized() throws IOException {
        System.setProperty(PROPERTY, "10.0.0.1");
        writeSessionWith("allowedAddresses:\n  - \"${sysprop:" + PROPERTY + "}\"\n  - \"10.0.0.2\"\n");

        FileFixSessionsSettingsStore store = store(List.of());
        FixSessionSettings loaded = store.load().iterator().next();
        store.add(loaded.toBuilder()
                .allowedAddresses(List.of(loaded.getAllowedAddresses().get(0)))
                .build());

        assertThat(writtenFile(loaded)).contains("10.0.0.1").doesNotContain("${sysprop");
    }

    /**
     * A placeholder in a map value, whose keys the model does not declare.
     */
    @Test
    void resolvesAPlaceholderInAMapValue() throws IOException {
        System.setProperty(PROPERTY, "MAPPED");
        writeSessionWith("fixApplicationSessionSettings:\n  someKey: \"${sysprop:" + PROPERTY + "}\"\n");

        assertThat(load()).singleElement()
                // The runtime map is keyed by a descriptor, not by the YAML key, so assert on the value.
                .satisfies(s -> assertThat(s.getFixApplicationSessionSettings().values())
                        .contains("MAPPED"));
    }

    /**
     * Nothing escapes an opening brace, so a value that merely looks like a placeholder is treated as one
     * and fails when no resolver answers. Documented rather than supported.
     */
    @Test
    void treatsALiteralOpeningBraceAsAPlaceholder() throws IOException {
        writeSession("literal-${not.a.property}", "PT30S");

        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class);
    }

    private void writeSessionWith(String extraYaml) throws IOException {
        String yaml = "fixSessionId:\n"
                + "  id: \"SENDER\"\n"
                + "  senderCompID: \"SENDER\"\n"
                + "  targetCompID: \"TARGET\"\n"
                + "  fixVersion: \"FIX.4.4\"\n"
                + "fixSessionType: ACCEPTOR\n"
                + extraYaml;
        Files.write(new File(tempDir, "session1.yaml").toPath(), yaml.getBytes(StandardCharsets.UTF_8));
    }

    private void writeDefaults(String body) throws IOException {
        Files.write(new File(tempDir, "default.yaml").toPath(), body.getBytes(StandardCharsets.UTF_8));
    }

    private void writeSessionWithoutTimeout(String id) throws IOException {
        String yaml = "fixSessionId:\n"
                + "  id: \"" + id + "\"\n"
                + "  senderCompID: \"SENDER\"\n"
                + "  targetCompID: \"TARGET\"\n"
                + "  fixVersion: \"FIX.4.4\"\n"
                + "fixSessionType: ACCEPTOR\n";
        Files.write(new File(tempDir, "session1.yaml").toPath(), yaml.getBytes(StandardCharsets.UTF_8));
    }

    private String writtenFile(FixSessionSettings settings) throws IOException {
        File file = new File(tempDir, settings.getFixSessionId().forFileName(".yaml"));
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private Set<FixSessionSettings> load() {
        return load(List.of());
    }

    private FileFixSessionsSettingsStore store(List<ConfigValueResolver> resolvers) {
        return new FileFixSessionsSettingsStore.FileStoreFactoryImpl()
                .newInstance(FileSessionsSettingsStoreSettings.builder()
                        .instanceId("test")
                        .fixSessionSettingsDirectory(tempDir)
                        .configValueResolvers(resolvers)
                        .build());
    }

    private Set<FixSessionSettings> load(List<ConfigValueResolver> resolvers) {
        return store(resolvers).load();
    }

    private void writeSession(String id, String timeout) throws IOException {
        String yaml = "fixSessionId:\n"
                + "  id: \"" + id + "\"\n"
                + "  senderCompID: \"SENDER\"\n"
                + "  targetCompID: \"TARGET\"\n"
                + "  fixVersion: \"FIX.4.4\"\n"
                + "fixSessionType: ACCEPTOR\n"
                + "logInOrOutResponseTimeout: \"" + timeout + "\"\n";
        Files.write(new File(tempDir, "session1.yaml").toPath(), yaml.getBytes(StandardCharsets.UTF_8));
    }
}
