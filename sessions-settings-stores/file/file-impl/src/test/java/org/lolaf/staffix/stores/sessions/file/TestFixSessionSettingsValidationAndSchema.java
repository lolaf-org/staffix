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

import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsJsonSchema;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFixSessionSettingsValidationAndSchema {

    private static final String VALID_SESSION = String.join("\n",
            "fixSessionId:",
            "  name: \"test\"",
            "  fixVersion: \"FIX.4.4\"",
            "  senderCompID: \"SENDER\"",
            "  targetCompID: \"TARGET\"",
            "fixSessionType: \"ACCEPTOR\"",
            "");

    @TempDir
    private File tempDir;

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
                        .name("test")
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
}
