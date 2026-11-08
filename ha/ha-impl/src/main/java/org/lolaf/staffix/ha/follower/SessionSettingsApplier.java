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
package org.lolaf.staffix.ha.follower;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.ha.FixEngineHa.EngineAccess;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;
import org.lolaf.staffix.ha.SessionSettingsDigests;
import org.lolaf.staffix.ha.SessionSettingsDigests.Digest;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocumentCodec;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocumentMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Brings this node's replicated stores to the leader's documents, a session at a time so that one this node cannot
 * resolve does not hold the others back; it stays reported as different. Only sessions whose digest differs are written:
 * equal digests may resolve to different values here, which are this node's to keep.
 */
@Slf4j
@RequiredArgsConstructor
final class SessionSettingsApplier {

    private static final ObjectMapper JSON = FixSessionSettingsDocumentMapper.configure(new ObjectMapper());

    private final EngineAccess engine;

    void apply(List<Digest> leaders) {
        leaders.stream().filter(digest -> !digest.getDocument().isEmpty())
                .collect(Collectors.groupingBy(Digest::getStore))
                .forEach(this::applyStore);
    }

    private void applyStore(String storeId, List<Digest> leaders) {
        Optional<FixSessionsSettingsStore> found = engine.getFixSessionsSettingsStores().stream()
                .filter(store -> store.getInstanceId().equals(storeId)).findFirst();
        if (found.isEmpty() || found.get().getStorageIdentity().isEmpty()) {
            return;
        }
        FixSessionsSettingsStore store = found.get();
        if (store.getStorageIdentity().get().equals(leaders.get(0).getStoreIdentity())) {
            reloadShared(storeId);
            return;
        }
        Map<String, String> own = new HashMap<>();
        SessionSettingsDigests.of(List.of(store)).forEach(digest -> own.put(key(digest), digest.getValue()));
        FixSessionSettingsDocumentCodec codec = new FixSessionSettingsDocumentCodec(JSON, store.getConfigValueResolvers());
        codec.refreshResolvers();
        leaders.stream().filter(digest -> !digest.getValue().equals(own.get(key(digest))))
                .forEach(digest -> applySession(storeId, digest, codec));
    }

    private void reloadShared(String storeId) {
        try {
            engine.reloadSessionSettings(storeId);
        } catch (RuntimeException e) {
            log.error("Cannot reload session settings store {}, shared with the leader", storeId, e);
        }
    }

    private void applySession(String storeId, Digest digest, FixSessionSettingsDocumentCodec codec) {
        String source = "the leader's " + digest.getFixSessionId();
        try {
            JsonNode document = JSON.readTree(digest.getDocument());
            FixSessionSettings settings = codec.toSettings(codec.read(document, source), Optional.empty(), source);
            engine.applySessionSettings(storeId, settings);
            log.info("Applied the leader's settings of FIX session {} to store {}", digest.getFixSessionId(), storeId);
        } catch (IOException | RuntimeException e) {
            log.error("Cannot apply the leader's settings of FIX session {} to store {}", digest.getFixSessionId(), storeId, e);
        }
    }

    private static String key(Digest digest) {
        return digest.getFixSessionType() + " " + digest.getFixSessionId();
    }
}
