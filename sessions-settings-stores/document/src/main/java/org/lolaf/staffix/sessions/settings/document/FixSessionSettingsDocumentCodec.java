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
package org.lolaf.staffix.sessions.settings.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Value;
import org.lolaf.staffix.api.session.ConfigValueResolver;
import org.lolaf.staffix.api.session.ConfigValueResolverChain;
import org.lolaf.staffix.api.session.FixSessionSettings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a store persisting session documents does around its storage: reading a document into settings and writing
 * settings back with their placeholders, so the value of a placeholder never reaches the storage.
 */
public final class FixSessionSettingsDocumentCodec {

    private static final ObjectMapper JSON = FixSessionSettingsDocumentMapper.configure(new ObjectMapper());

    private final ObjectMapper mapper;
    private final ConfigValueResolverChain resolvers;

    /**
     * @param mapper    of the format the store persists, configured by {@link FixSessionSettingsDocumentMapper}
     * @param resolvers asked in order; empty falls back to system properties then environment variables
     */
    public FixSessionSettingsDocumentCodec(ObjectMapper mapper, List<ConfigValueResolver> resolvers) {
        this.mapper = mapper;
        this.resolvers = ConfigValueResolverChain.of(resolvers);
    }

    /**
     * @return the settings' document with its placeholders as expressions, equal on two nodes resolving them differently
     */
    public static String toCanonicalUnresolvedJson(FixSessionSettings settings) {
        JsonNode resolved = JSON.valueToTree(ToFixSessionSettingsDocumentTransformer.toDocument(settings));
        return FixSessionSettingsDocumentMapper.toCanonicalJson(FixSessionSettingsPlaceholders.overlay(resolved, settings.getPlaceholders()));
    }

    /**
     * Once per load, so every document of a load resolves against the same snapshot.
     */
    public void refreshResolvers() {
        resolvers.refresh();
    }

    /**
     * @param tree   a document as persisted
     * @param source named in a failure
     * @throws IllegalStateException if a placeholder cannot be resolved or the document does not bind
     */
    public ReadDocument read(JsonNode tree, String source) {
        JsonNode unresolved = FixSessionSettingsDocumentUpgrader.upgrade(tree, source);
        try {
            return new ReadDocument(unresolved, mapper.treeToValue(DocumentPlaceholderResolver.resolve(unresolved, resolvers, source), FixSessionSettingsDocument.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to read the FIX session settings of " + source, e);
        }
    }

    /**
     * @param defaults merged under the document; its placeholders follow the values the document inherits
     */
    public FixSessionSettings toSettings(ReadDocument document, Optional<ReadDocument> defaults, String source) {
        FixSessionSettingsDocument merged = FromFixSessionSettingsDocumentTransformer.mergeWithDefault(
                defaults.map(ReadDocument::getDocument).orElse(null), document.getDocument());
        FixSessionSettingsDocumentValidator.validate(merged, source);
        return FromFixSessionSettingsDocumentTransformer.toFixSessionSettings(merged).toBuilder()
                .placeholders(placeholders(document, defaults))
                .build();
    }

    /**
     * An expression the settings bring is written as it is; one the session already has stays only while its value does,
     * as the value belongs to the placeholder's source.
     *
     * @param current the session as the store holds it, if it does
     * @param where   named in a failure
     * @throws IllegalStateException if the value of a placeholder the session has was changed
     */
    public WrittenDocument write(FixSessionSettings settings, Optional<FixSessionSettings> current, String where) {
        FixSessionSettingsDocument document = ToFixSessionSettingsDocumentTransformer.toDocument(settings);
        FixSessionSettingsDocumentValidator.validate(document, where);
        JsonNode resolved = mapper.valueToTree(document);
        Map<String, String> held = current.map(FixSessionSettings::getPlaceholders).orElse(Map.of());
        Map<String, String> brought = new HashMap<>(settings.getPlaceholders());
        brought.entrySet().removeAll(held.entrySet());
        if (current.isPresent()) {
            JsonNode currentResolved = mapper.valueToTree(ToFixSessionSettingsDocumentTransformer.toDocument(current.get()));
            Map<String, String> kept = new HashMap<>(held);
            kept.keySet().removeAll(brought.keySet());
            DocumentPlaceholderRestorer.restore(FixSessionSettingsPlaceholders.overlay(currentResolved, kept), currentResolved, resolved, where);
        }
        JsonNode tree = FixSessionSettingsPlaceholders.overlay(resolved, brought);
        return new WrittenDocument(tree, settings.toBuilder().placeholders(FixSessionSettingsPlaceholders.find(tree)).build());
    }

    private static Map<String, String> placeholders(ReadDocument document, Optional<ReadDocument> defaults) {
        Map<String, String> placeholders = new HashMap<>();
        defaults.ifPresent(inherited -> FixSessionSettingsPlaceholders.find(inherited.getUnresolved()).forEach((pointer, expression) -> {
            if (document.getUnresolved().at(pointer).isMissingNode()) {
                placeholders.put(pointer, expression);
            }
        }));
        placeholders.putAll(FixSessionSettingsPlaceholders.find(document.getUnresolved()));
        return Map.copyOf(placeholders);
    }

    @Value
    public static class ReadDocument {
        JsonNode unresolved;
        FixSessionSettingsDocument document;
    }

    @Value
    public static class WrittenDocument {
        /**
         * What to persist: placeholders as expressions.
         */
        JsonNode tree;
        /**
         * The settings holding the placeholders {@link #tree} has.
         */
        FixSessionSettings settings;
    }
}
