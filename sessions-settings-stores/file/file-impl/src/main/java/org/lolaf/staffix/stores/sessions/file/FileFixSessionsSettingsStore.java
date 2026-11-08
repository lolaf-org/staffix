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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.session.*;
import org.lolaf.staffix.api.stores.DirectoryStorageIdentity;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocumentCodec;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocumentCodec.ReadDocument;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocumentMapper;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsJsonSchema;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Loads sessions from YAML files in a directory, one session per file.
 *
 * <p>Copies the generated JSON schema next to them on startup, so an editor can complete and validate a session
 * file against the model the engine actually reads.
 */
@Slf4j
public class FileFixSessionsSettingsStore extends FixSessionsSettingsStore.AbstractFixSessionSettingsStore {

    private static final String FILE_EXTENSION = ".yaml";
    private static final String DEFAULT_FILE_NAME = "default" + FILE_EXTENSION;
    private static final String SCHEMA_HEADER =
            "# yaml-language-server: $schema=" + FixSessionSettingsJsonSchema.SCHEMA_FILE_NAME + System.lineSeparator();

    @Getter
    private final String instanceId;
    private final File directory;
    private final List<URI> uris;
    /**
     * A directory-backed store writes; a URI-backed one has nowhere to write to.
     */
    private final boolean savable;
    private final ObjectMapper mapper;
    private final FixSessionSettingsDocumentCodec codec;

    private volatile String storageIdentity;

    protected FileFixSessionsSettingsStore(FileSessionsSettingsStoreSettings settings) {
        this.instanceId = settings.getInstanceId();
        this.directory = settings.getFixSessionSettingsDirectory();
        this.uris = settings.getFixSessionSettingsUris();
        if (directory != null && !uris.isEmpty()) {
            throw new IllegalArgumentException("A FIX session settings store reads a directory or a list of"
                    + " URIs, not both; " + instanceId + " was given " + directory + " and " + uris);
        }
        if (directory == null && uris.isEmpty()) {
            throw new IllegalArgumentException("A FIX session settings store needs either a directory or a"
                    + " list of URIs; " + instanceId + " was given neither");
        }
        this.savable = directory != null;
        this.mapper = newMapper();
        this.codec = new FixSessionSettingsDocumentCodec(mapper, settings.getConfigValueResolvers());
    }

    // Package-private so tests exercise the exact same mapper configuration (module registrations, strict
    // reading, InetAddress handling) as production, rather than maintaining a divergent copy.
    static ObjectMapper newMapper() {
        return FixSessionSettingsDocumentMapper.configure(new ObjectMapper(new YAMLFactory()))
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    private static boolean isDefaults(URI uri) {
        String path = uri.getPath() == null ? uri.getSchemeSpecificPart() : uri.getPath();
        return path != null && (path.endsWith("/" + DEFAULT_FILE_NAME) || path.equals(DEFAULT_FILE_NAME));
    }

    @Override
    protected void startMe() throws StartStopException {
        if (savable) {
            ensureDirectory();
            writeSchemaFile();
            storageIdentity = readStorageIdentity();
        }
        loadFromSources().forEach(this::hold);
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        // noting to do
    }

    /**
     * Reads every {@code *.yaml} session file from the configured directory, merging each against {@code default.yaml}
     * when present, and returns the result without touching the in-memory settings.
     */
    @Override
    protected Set<FixSessionSettings> onLoad() {
        return loadFromSources();
    }

    @Override
    public Optional<String> getStorageIdentity() {
        return Optional.ofNullable(storageIdentity);
    }

    private String readStorageIdentity() {
        try {
            return DirectoryStorageIdentity.of(directory.toPath());
        } catch (IOException e) {
            log.warn("Cannot read the storage identity of FIX session settings directory {}", directory, e);
            return null;
        }
    }

    /**
     * A directory-backed store writes each change to its file; a URI-backed one keeps changes in memory only.
     */
    @Override
    public boolean isPersistent() {
        return savable;
    }

    private Set<FixSessionSettings> loadFromSources() {
        codec.refreshResolvers();
        return savable ? loadFromDirectory() : loadFromUris();
    }

    private Set<FixSessionSettings> loadFromDirectory() {
        ensureDirectory();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(FILE_EXTENSION) && !name.equals(DEFAULT_FILE_NAME));
        List<SettingsSource> sources = files == null ? Collections.emptyList()
                : Arrays.stream(files)
                .map(file -> new SettingsSource(file.getPath(), () -> readRetaining(file)))
                .collect(Collectors.toList());
        return load(sources, readDefaults());
    }

    /**
     * Unlike a directory a URI list cannot be enumerated, so each URI names one file and the defaults are
     * the one whose last segment is {@code default.yaml}.
     */
    private Set<FixSessionSettings> loadFromUris() {
        List<SettingsSource> sources = uris.stream()
                .filter(uri -> !isDefaults(uri))
                .map(uri -> new SettingsSource(uri.toString(), () -> readRetaining(uri)))
                .collect(Collectors.toList());
        return load(sources, readUriDefaults());
    }

    private Set<FixSessionSettings> load(List<SettingsSource> sources, ReadDocument defaults) {
        Map<FixSessionId, String> seen = new HashMap<>();
        return sources.stream()
                .map(source -> load(source, defaults, seen))
                .collect(Collectors.toSet());
    }

    private FixSessionSettings load(SettingsSource source, ReadDocument defaults,
                                    Map<FixSessionId, String> seen) {
        FixSessionSettings settings = codec.toSettings(source.getRead().get(), Optional.ofNullable(defaults), source.getName());
        String clash = seen.putIfAbsent(settings.getFixSessionId(), source.getName());
        if (clash != null) {
            throw new IllegalStateException("FIX session " + settings.getFixSessionId()
                    + " is defined by two sources: " + clash + " and " + source.getName());
        }
        log.info("Loaded FixSessionSettings {} from {}", settings.getFixSessionId(), source.getName());
        return settings;
    }

    private ReadDocument readUriDefaults() {
        List<URI> found = uris.stream().filter(FileFixSessionsSettingsStore::isDefaults)
                .collect(Collectors.toList());
        if (found.size() > 1) {
            throw new IllegalStateException("Only one " + DEFAULT_FILE_NAME
                    + " may be given as a FIX session settings source, but these were: " + found);
        }
        return found.isEmpty() ? null : readRetaining(found.get(0));
    }

    private ReadDocument readDefaults() {
        File defaultFile = new File(directory, DEFAULT_FILE_NAME);
        return defaultFile.isFile() ? readRetaining(defaultFile) : null;
    }

    private ReadDocument readRetaining(File file) {
        try {
            return codec.read(mapper.readValue(file, JsonNode.class), file.getPath());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read FIX session settings file " + file, e);
        }
    }

    private ReadDocument readRetaining(URI uri) {
        try (InputStream in = uri.toURL().openStream()) {
            return codec.read(mapper.readValue(in, JsonNode.class), uri.toString());
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to read FIX session settings from " + uri, e);
        }
    }

    private void ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create FIX session settings directory " + directory);
        }
        if (!directory.isDirectory()) {
            throw new IllegalStateException("FIX session settings path is not a directory: " + directory);
        }
    }

    /**
     * Drops the JSON schema next to the session files so editors can offer autocomplete and validation for files
     * declaring {@code # yaml-language-server: $schema=...}. The schema is copied from the jar, where the build put it
     * after generating it from the model, so no schema-generation dependency is needed at runtime. Best-effort: a
     * failure here must not stop the store.
     */
    private void writeSchemaFile() {
        if (!directory.canWrite()) {
            log.warn("Unable to write FIX session settings JSON schema file in read only dir {}", directory);
            return;
        }
        File schemaFile = new File(directory, FixSessionSettingsJsonSchema.SCHEMA_FILE_NAME);
        try (InputStream packagedSchema = FixSessionSettingsJsonSchema.openPackagedSchema()) {
            if (packagedSchema == null) {
                log.warn("No packaged FIX session settings JSON schema on the classpath, {} not written", schemaFile);
                return;
            }
            Files.copy(packagedSchema, schemaFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (RuntimeException | IOException e) {
            log.warn("Unable to write FIX session settings JSON schema to {}", schemaFile, e);
        }
    }

    private File fileFor(FixSessionSettings settings) {
        return new File(directory, settings.getFixSessionId().forFileName(FILE_EXTENSION));
    }

    @Override
    protected FixSessionSettings onAdd(FixSessionSettings settings) {
        return getSettings().contains(settings) ? settings : writeToDisk(settings);
    }

    @Override
    protected void onRemove(FixSessionSettings settings) {
        if (!savable) {
            log.info("FIX session settings {} came from a URI and cannot be deleted", settings.getFixSessionId());
            return;
        }
        File file = fileFor(settings);
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("Unable to delete FIX session settings file " + file);
        }
    }

    /**
     * Written first: a write the file refuses, such as a change to a placeholder's value, leaves the store as it was.
     */
    @Override
    protected FixSessionSettings onUpdate(FixSessionSettings settings) {
        return writeToDisk(settings);
    }

    /**
     * @return the settings with the placeholders the file now holds
     */
    private FixSessionSettings writeToDisk(FixSessionSettings settings) {
        if (!savable) {
            log.info("FIX session settings {} came from a URI and are not written back", settings.getFixSessionId());
            return settings;
        }
        ensureDirectory();
        File file = fileFor(settings);
        FixSessionSettingsDocumentCodec.WrittenDocument written = codec.write(settings,
                find(settings.getFixSessionId(), settings.getFixSessionType()), file.getPath());
        try {
            String content = SCHEMA_HEADER + mapper.writeValueAsString(written.getTree());
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write FIX session settings file " + file, e);
        }
        return written.getSettings();
    }

    /**
     * One file to read, however it is addressed.
     */
    @Getter
    @RequiredArgsConstructor
    private static final class SettingsSource {
        private final String name;
        private final Supplier<ReadDocument> read;
    }

    public static class FileStoreFactoryImpl implements FixSessionsSettingsStoreSettings.FixSessionsStoreFactory<FileSessionsSettingsStoreSettings> {

        @Override
        public FileFixSessionsSettingsStore newInstance(FileSessionsSettingsStoreSettings settings) {
            return new FileFixSessionsSettingsStore(settings);
        }

        @Override
        public Class<FileSessionsSettingsStoreSettings> getSettingsClass() {
            return FileSessionsSettingsStoreSettings.class;
        }
    }
}