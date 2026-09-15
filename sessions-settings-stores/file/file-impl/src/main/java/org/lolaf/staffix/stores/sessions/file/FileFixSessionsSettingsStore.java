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
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.session.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private final ConfigValueResolverChain configValueResolvers;

    /**
     * Per session, the file as read and the same tree resolved, kept so a write can put the placeholders
     * back. Only a session's own file is retained: {@code default.yaml} is never written, and folding its
     * trees in here would restore its placeholders into every session file.
     */
    private final Map<FixSessionId, RetainedYaml> retainedYaml = new ConcurrentHashMap<>();
    private final Set<FixSessionSettings> fixSessionSettings;

    protected FileFixSessionsSettingsStore(FileSessionsSettingsStoreSettings settings) {
        this.fixSessionSettings = new HashSet<>();
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
        this.configValueResolvers = settings.getConfigValueResolvers().isEmpty()
                ? ConfigValueResolverChain.defaultChain()
                : new ConfigValueResolverChain(settings.getConfigValueResolvers());
    }

    // Package-private so tests exercise the exact same mapper configuration (module registrations, strict
    // reading, InetAddress handling) as production, rather than maintaining a divergent copy.
    static ObjectMapper newMapper() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();
        // Jackson's built-in InetAddress deserializer (2.19+) rejects any string that is not an IP literal
        SimpleModule inetAddressModule = new SimpleModule();
        inetAddressModule.addDeserializer(InetAddress.class, new JsonDeserializer<>() {
            @Override
            public InetAddress deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return InetAddress.getByName(p.getValueAsString().trim());
            }
        });
        mapper.registerModule(inetAddressModule);
        mapper.configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false); // LocalTime as "10:10:11", not [10,10,11]
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL); // null objects are not serialized
        // Strict reading: reject typo'd field names, unknown enum constants and trailing junk so editing mistakes
        // fail loudly at load instead of silently leaving fields null. Bean Validation then enforces value-level rules.
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        mapper.configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
        mapper.configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, false);
        return mapper;
    }

    private static boolean isDefaults(URI uri) {
        String path = uri.getPath() == null ? uri.getSchemeSpecificPart() : uri.getPath();
        return path != null && (path.endsWith("/" + DEFAULT_FILE_NAME) || path.equals(DEFAULT_FILE_NAME));
    }

    @Override
    public Collection<FixSessionSettings> getSettings() {
        return fixSessionSettings;
    }

    @Override
    public Optional<FixSessionSettings> find(FixSessionId fixSessionId, FixSession.FixSessionType fixSessionType) {
        return fixSessionSettings.stream()
                .filter(s -> s.getFixSessionId().equals(fixSessionId) && s.getFixSessionType() == fixSessionType)
                .findFirst();
    }

    @Override
    protected void startMe() throws StartStopException {
        ensureDirectory();
        writeSchemaFile();
        fixSessionSettings.addAll(loadFromSources());
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
    public Set<FixSessionSettings> load() {
        return loadFromSources();
    }

    private Set<FixSessionSettings> loadFromSources() {
        configValueResolvers.refresh();
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

    private Set<FixSessionSettings> load(List<SettingsSource> sources, YamlFixSessionSettings defaults) {
        Map<FixSessionId, String> seen = new HashMap<>();
        return sources.stream()
                .map(source -> load(source, defaults, seen))
                .collect(Collectors.toSet());
    }

    private FixSessionSettings load(SettingsSource source, YamlFixSessionSettings defaults,
                                    Map<FixSessionId, String> seen) {
        RetainedYaml retained = source.getRead().get();
        FixSessionSettings settings = bind(retained, defaults, source.getName());
        String clash = seen.putIfAbsent(settings.getFixSessionId(), source.getName());
        if (clash != null) {
            throw new IllegalStateException("FIX session " + settings.getFixSessionId()
                    + " is defined by two sources: " + clash + " and " + source.getName());
        }
        // Only a writable store needs the trees: they exist to put placeholders back on write, and a
        // URI-backed store never writes.
        if (savable) {
            retainedYaml.put(settings.getFixSessionId(), retained);
        }
        log.info("Loaded FixSessionSettings {} from {}", settings.getFixSessionId(), source.getName());
        return settings;
    }

    private YamlFixSessionSettings readUriDefaults() {
        List<URI> found = uris.stream().filter(FileFixSessionsSettingsStore::isDefaults)
                .collect(Collectors.toList());
        if (found.size() > 1) {
            throw new IllegalStateException("Only one " + DEFAULT_FILE_NAME
                    + " may be given as a FIX session settings source, but these were: " + found);
        }
        return found.isEmpty() ? null : readRetaining(found.get(0)).getSettings();
    }

    private FixSessionSettings bind(RetainedYaml retained, YamlFixSessionSettings defaults, String source) {
        YamlFixSessionSettings yaml = FromYamlFixSessionSettingsTransformer.mergeWithDefault(defaults, retained.getSettings());
        FixSessionSettingsValidator.validate(yaml, source);
        return FromYamlFixSessionSettingsTransformer.toFixSessionSettings(yaml);
    }

    private YamlFixSessionSettings readDefaults() {
        File defaultFile = new File(directory, DEFAULT_FILE_NAME);
        return defaultFile.isFile() ? read(defaultFile) : null;
    }

    private YamlFixSessionSettings read(File file) {
        return readRetaining(file).getSettings();
    }

    private RetainedYaml readRetaining(File file) {
        try {
            return readRetaining(mapper.readValue(file, JsonNode.class), file.getPath());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read FIX session settings file " + file, e);
        }
    }

    private RetainedYaml readRetaining(URI uri) {
        try (InputStream in = uri.toURL().openStream()) {
            return readRetaining(mapper.readValue(in, JsonNode.class), uri.toString());
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to read FIX session settings from " + uri, e);
        }
    }

    // Through the tree rather than straight to the type, so a ${...} placeholder can stand in a field of
    // any type; readValue keeps FAIL_ON_TRAILING_TOKENS, which readTree would not.
    private RetainedYaml readRetaining(JsonNode raw, String source) throws IOException {
        JsonNode resolved = YamlPlaceholderResolver.resolve(raw, configValueResolvers, source);
        return new RetainedYaml(raw, resolved, mapper.treeToValue(resolved, YamlFixSessionSettings.class));
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
    public void onAdd(FixSessionSettings settings) {
        if (fixSessionSettings.add(settings)) {
            writeToDisk(settings);
        }
    }

    @Override
    public void onRemove(FixSessionSettings settings) {
        if (fixSessionSettings.remove(settings)) {
            if (!savable) {
                log.info("FIX session settings {} came from a URI and cannot be deleted", settings.getFixSessionId());
                return;
            }
            File file = fileFor(settings);
            if (file.exists() && !file.delete()) {
                throw new IllegalStateException("Unable to delete FIX session settings file " + file);
            }
        }
    }

    @Override
    public void onUpdate(FixSessionSettings settings) {
        fixSessionSettings.removeIf(s -> s.getFixSessionId().equals(settings.getFixSessionId()));
        fixSessionSettings.add(settings);
        writeToDisk(settings);
    }

    private void writeToDisk(FixSessionSettings settings) {
        if (!savable) {
            log.info("FIX session settings {} came from a URI and are not written back", settings.getFixSessionId());
            return;
        }
        ensureDirectory();
        File file = fileFor(settings);
        YamlFixSessionSettings yaml = ToYamlFixSessionSettingsTransformer.toYamlFixSessionSettings(settings);
        FixSessionSettingsValidator.validate(yaml, file.getPath());
        try {
            JsonNode written = mapper.valueToTree(yaml);
            RetainedYaml retained = retainedYaml.get(settings.getFixSessionId());
            if (retained != null) {
                YamlPlaceholderRestorer.restore(retained.getRaw(), retained.getResolved(), written,
                        file.getPath());
            }
            String content = SCHEMA_HEADER + mapper.writeValueAsString(written);
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write FIX session settings file " + file, e);
        }
    }

    /**
     * One file to read, however it is addressed.
     */
    @Getter
    @RequiredArgsConstructor
    private static final class SettingsSource {
        private final String name;
        private final Supplier<RetainedYaml> read;
    }

    @Getter
    @RequiredArgsConstructor
    private static final class RetainedYaml {
        private final JsonNode raw;
        private final JsonNode resolved;
        private final YamlFixSessionSettings settings;
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