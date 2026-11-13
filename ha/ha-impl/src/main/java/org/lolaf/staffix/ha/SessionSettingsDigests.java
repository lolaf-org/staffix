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
package org.lolaf.staffix.ha;

import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;
import org.lolaf.staffix.api.utils.Sha256;
import org.lolaf.staffix.ha.wire.WireFormat;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocumentCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The leader's session settings as digests, for its follower to compare with its own. Taken on each session's document
 * with its placeholders unresolved, so two nodes resolving one differently agree.
 */
@Slf4j
public final class SessionSettingsDigests {

    /**
     * Generation 0 until a first {@link #publish}: nothing to send.
     */
    @Getter
    private volatile Published published = new Published(0, List.of());
    /**
     * Sessions removed from a store and not added again, so a follower away at the time still removes them. In memory:
     * a leader restarted since has forgotten them, and the follower then only reports the session.
     */
    private final Set<StoredSession> removals = ConcurrentHashMap.newKeySet();

    public static List<Digest> of(List<FixSessionsSettingsStore> stores) {
        return of(stores, store -> false);
    }

    private static List<Digest> of(List<FixSessionsSettingsStore> stores, Predicate<FixSessionsSettingsStore> replicated) {
        List<Digest> digests = new ArrayList<>();
        for (FixSessionsSettingsStore store : stores) {
            String identity = replicated.test(store) ? store.getStorageIdentity().orElse("") : "";
            for (FixSessionSettings settings : store.getSettings()) {
                String json = FixSessionSettingsDocumentCodec.toCanonicalUnresolvedJson(settings);
                digests.add(new Digest(store.getInstanceId(), settings.getFixSessionType(), settings.getFixSessionId().toString(),
                        Sha256.hex(json), identity, identity.isEmpty() ? "" : sendable(settings, json), false));
            }
        }
        return digests;
    }

    private static String sendable(FixSessionSettings settings, String json) {
        if (json.getBytes(StandardCharsets.UTF_8).length <= WireFormat.MAX_STRING_BYTES) {
            return json;
        }
        log.warn("The settings of FIX session {} are too long to replicate, they are only compared", settings.getFixSessionId());
        return "";
    }

    /**
     * Each call is a new generation, sent to the followers even if nothing differs.
     *
     * @param replicated the stores whose documents go with their digests; the others are only compared
     */
    synchronized void publish(List<FixSessionsSettingsStore> stores, Predicate<FixSessionsSettingsStore> replicated) {
        List<Digest> digests = of(stores, replicated);
        for (FixSessionsSettingsStore store : stores) {
            if (replicated.test(store)) {
                String identity = store.getStorageIdentity().orElse("");
                removals.stream().filter(removal -> removal.getStore().equals(store.getInstanceId()))
                        .forEach(removal -> digests.add(new Digest(removal.getStore(), removal.getSession().getFixSessionType(),
                                removal.getSession().getFixSessionId(), "", identity, "", true)));
            }
        }
        published = new Published(published.getGeneration() + 1, digests);
    }

    void onRemoved(String store, FixSessionSettings settings) {
        removals.add(removal(store, settings));
    }

    void onAdded(String store, FixSessionSettings settings) {
        removals.remove(removal(store, settings));
    }

    private static StoredSession removal(String store, FixSessionSettings settings) {
        return new StoredSession(store, TypedSessionId.of(settings));
    }

    @Value
    public static class Digest {
        String store;
        FixSessionType fixSessionType;
        String fixSessionId;
        String value;
        /**
         * Empty unless {@link #document} is sent.
         */
        String storeIdentity;
        /**
         * The session's document with its placeholders as expressions; empty for a store only compared.
         */
        String document;
        /**
         * The leader removed the session: this says so and carries no digest.
         */
        boolean removed;

        public TypedSessionId session() {
            return new TypedSessionId(fixSessionType, fixSessionId);
        }

        public StoredSession storedSession() {
            return new StoredSession(store, session());
        }
    }

    /**
     * A session of one settings store, by the store's instance id.
     */
    @Value
    public static class StoredSession {
        String store;
        TypedSessionId session;
    }

    @Value
    public static class Published {
        int generation;
        List<Digest> digests;
    }
}
