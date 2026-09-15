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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFixSessionSettingsValidationAndSchema {

    private static final String VALID_SESSION = String.join("\n",
            "fixSessionId:",
            "  id: \"test\"",
            "  fixVersion: \"FIX.4.4\"",
            "  senderCompID: \"SENDER\"",
            "  targetCompID: \"TARGET\"",
            "fixSessionType: \"ACCEPTOR\"",
            "");

    @TempDir
    private File tempDir;

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private FileFixSessionsSettingsStore newStore() {
        return new FileFixSessionsSettingsStore.FileStoreFactoryImpl()
                .newInstance(FileSessionsSettingsStoreSettings.builder()
                        .instanceId("test")
                        .fixSessionSettingsDirectory(tempDir)
                        .build());
    }

    private void writeSessionFile(String name, String content) throws IOException {
        Files.write(new File(tempDir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validSessionLoadsFine() throws IOException {
        writeSessionFile("ok.yaml", VALID_SESSION);

        FileFixSessionsSettingsStore store = newStore();
        store.start();

        assertThat(store.getSettings()).hasSize(1);
    }

    @Test
    void missingRequiredSessionIdFailsWithReadableError() throws IOException {
        writeSessionFile("bad.yaml", "dictionaryId: \"default\"\n");

        FileFixSessionsSettingsStore store = newStore();
        assertThatThrownBy(store::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid FIX session settings")
                .hasMessageContaining("fixSessionId");
    }

    @Test
    void blankRequiredCompIdFailsValidation() throws IOException {
        writeSessionFile("bad.yaml", String.join("\n",
                "fixSessionId:",
                "  fixVersion: \"FIX.4.4\"",
                "  senderCompID: \"SENDER\"",
                "  targetCompID: \"\"",
                ""));

        FileFixSessionsSettingsStore store = newStore();
        assertThatThrownBy(store::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("targetCompID");
    }

    @Test
    void unknownPropertyIsRejected() throws IOException {
        writeSessionFile("bad.yaml", VALID_SESSION + "heartBeatIntervl: {}\n");

        FileFixSessionsSettingsStore store = newStore();
        assertThatThrownBy(store::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to read");
    }

    @Test
    void unknownEnumConstantIsRejected() throws IOException {
        writeSessionFile("bad.yaml", VALID_SESSION + "desiredSessionState: \"NOT_A_STATE\"\n");

        FileFixSessionsSettingsStore store = newStore();
        assertThatThrownBy(store::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to read");
    }

    @Test
    void trailingDocumentIsRejected() throws IOException {
        writeSessionFile("bad.yaml", VALID_SESSION + "---\nsessionId:\n  fixVersion: \"FIX.4.4\"\n");

        FileFixSessionsSettingsStore store = newStore();
        assertThatThrownBy(store::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to read");
    }

    @Test
    void startWritesSchemaFileIntoDirectory() {
        FileFixSessionsSettingsStore store = newStore();
        store.start();

        assertThat(new File(tempDir, FixSessionSettingsJsonSchema.SCHEMA_FILE_NAME)).isFile();
    }

    @Test
    void writtenSessionFileReferencesSchema() throws IOException {
        FileFixSessionsSettingsStore store = newStore();
        store.start();

        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                        .id("test")
                        .senderCompID("SENDER")
                        .targetCompID("TARGET")
                        .build()))
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .build();
        store.add(settings);

        File file = new File(tempDir, settings.getFixSessionId().forFileName(".yaml"));
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertThat(content).startsWith("# yaml-language-server: $schema=" + FixSessionSettingsJsonSchema.SCHEMA_FILE_NAME);
    }

    @Test
    void packagedSchemaMatchesTheModel() throws IOException {
        String packaged;
        try (InputStream stream = FixSessionSettingsJsonSchema.openPackagedSchema()) {
            assertThat(stream)
                    .describedAs("schema packaged in the jar (generated by exec-maven-plugin at process-classes; "
                            + "run `mvn process-classes` if this fails in an IDE that compiled without Maven)")
                    .isNotNull();
            packaged = new String(readAll(stream), StandardCharsets.UTF_8);
        }

        assertThat(packaged)
                .describedAs("packaged schema must match the model it is generated from")
                .isEqualTo(FixSessionSettingsJsonSchemaGenerator.generate());
    }

    @Test
    void generatedSchemaDescribesTheModel() throws IOException {
        String schemaJson = FixSessionSettingsJsonSchemaGenerator.generate();

        JsonNode schema = new ObjectMapper().readTree(schemaJson);
        assertThat(schema.get("$schema").asText()).contains("2020-12");
        assertThat(schema.get("properties").has("fixSessionId")).isTrue();
        assertThat(schema.get("required")).isNotNull();
        assertThat(schema.get("required").toString()).contains("fixSessionId");
    }

    @Test
    void durationFieldsAreConstrainedByPattern() throws IOException {
        JsonNode schema = new ObjectMapper().readTree(FixSessionSettingsJsonSchemaGenerator.generate());

        // The declared type is the first anyOf branch; the second lets a ${...} placeholder stand here.
        JsonNode duration = schema.get("properties").get("logInOrOutResponseTimeout").get("anyOf").get(0);
        assertThat(duration.get("type").asText()).isEqualTo("string");
        assertThat(duration.get("format").asText()).isEqualTo("duration");
        assertThat(duration.has("pattern")).isTrue();

        String pattern = duration.get("pattern").asText();
        assertThat("PT0.0000015S").matches(pattern);
        assertThat("10s").doesNotMatch(pattern);
        assertThat("P1Y").doesNotMatch(pattern);
    }

    @Test
    void scheduleTimesAreConstrainedByLocalTimePattern() throws IOException {
        JsonNode schema = new ObjectMapper().readTree(FixSessionSettingsJsonSchemaGenerator.generate());

        JsonNode startTime = schema.path("properties").path("sessionScheduleSettings")
                .path("properties").path("sessionSchedules")
                .path("items").path("properties").path("startTime").path("anyOf").path(0);
        assertThat(startTime.get("type").asText()).isEqualTo("string");
        assertThat(startTime.has("pattern")).isTrue();

        String pattern = startTime.get("pattern").asText();
        assertThat("10:10:10.099999999").matches(pattern);
        assertThat("09:30").matches(pattern);
        assertThat("25:00").doesNotMatch(pattern);
        assertThat("10:10:11Z").doesNotMatch(pattern);
    }

    /**
     * Every non-enum scalar also accepts a placeholder, so an editor does not flag one standing in a
     * typed field. Enums keep their exact declaration.
     */
    @Test
    void nonEnumScalarsAlsoAcceptAPlaceholder() throws IOException {
        JsonNode schema = new ObjectMapper().readTree(FixSessionSettingsJsonSchemaGenerator.generate());

        JsonNode duration = schema.path("properties").path("logInOrOutResponseTimeout");
        assertThat(duration.has("anyOf")).isTrue();
        String placeholderPattern = duration.path("anyOf").path(1).path("pattern").asText();
        assertThat("${sysprop:x:PT30S}").matches(placeholderPattern);
        assertThat("prefix-${env:X}-suffix").matches(placeholderPattern);
        assertThat("PT30S").doesNotMatch(placeholderPattern);

        JsonNode sessionType = schema.path("properties").path("fixSessionType");
        assertThat(sessionType.has("anyOf")).isFalse();
        assertThat(sessionType.has("enum")).isTrue();
    }
}
