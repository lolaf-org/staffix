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

import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixtVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The dictionaries the encoders generator ships as {@code staffix-dictionaries/<id>.xml} beside the encoders, so
 * an admin tool decodes a session's messages with exactly the dictionary it runs.
 */
final class Dictionaries {

    private static final String DIRECTORY = "staffix-dictionaries/";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*");
    private static final Map<String, Optional<Dictionary>> LOADED = new ConcurrentHashMap<>();

    private Dictionaries() {
    }

    static final class Dictionary {
        final byte[] xml;
        final String hash;

        private Dictionary(byte[] xml) {
            this.xml = xml;
            this.hash = Sha256.hex(xml);
        }
    }

    /**
     * For FIXT the transport's first, then the application's; a dictionary not shipped is left out.
     */
    static List<DictionaryRef> of(FixSessionId fixSessionId, FixDictionaryId applicationDictionaryId) {
        List<String> ids = new ArrayList<>();
        if (fixSessionId.getFixVersion() instanceof FixtVersion) {
            ids.add(fixSessionId.getFixVersion().toString());
        }
        ids.add(applicationDictionaryId.getId());
        List<DictionaryRef> refs = new ArrayList<>();
        for (String id : ids) {
            get(id).ifPresent(dictionary -> refs.add(new DictionaryRef(id, dictionary.hash)));
        }
        return refs;
    }

    static Optional<Dictionary> get(String id) {
        if (!VALID_ID.matcher(id).matches() || id.contains("..")) {
            return Optional.empty();
        }
        return LOADED.computeIfAbsent(id, Dictionaries::load);
    }

    private static Optional<Dictionary> load(String id) {
        try (InputStream in = Dictionaries.class.getClassLoader().getResourceAsStream(DIRECTORY + id + ".xml")) {
            return in == null ? Optional.empty() : Optional.of(new Dictionary(in.readAllBytes()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read dictionary " + id, e);
        }
    }
}
