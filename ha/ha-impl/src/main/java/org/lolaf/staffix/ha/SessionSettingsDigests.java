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

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Value;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;
import org.lolaf.staffix.api.utils.Sha256;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocumentCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Session settings as digests, for a follower to compare with its leader's. Taken on each session's document with its
 * placeholders unresolved, so two nodes resolving one differently agree.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SessionSettingsDigests {

    public static List<Digest> of(List<FixSessionsSettingsStore> stores) {
        return of(stores, store -> false);
    }

    /**
     * @param replicated the stores whose documents go with their digests; the others are only compared
     */
    static List<Digest> of(List<FixSessionsSettingsStore> stores, Predicate<FixSessionsSettingsStore> replicated) {
        List<Digest> digests = new ArrayList<>();
        for (FixSessionsSettingsStore store : stores) {
            String identity = replicated.test(store) ? store.getStorageIdentity().orElse("") : "";
            for (FixSessionSettings settings : store.getSettings()) {
                String json = FixSessionSettingsDocumentCodec.toCanonicalUnresolvedJson(settings);
                digests.add(new Digest(store.getInstanceId(), settings.getFixSessionType(), settings.getFixSessionId().toString(),
                        Sha256.hex(json), identity, identity.isEmpty() ? "" : json, false));
            }
        }
        return digests;
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

        static Digest removal(StoredSession session, String storeIdentity) {
            return new Digest(session.getStore(), session.getSession().getFixSessionType(),
                    session.getSession().getFixSessionId(), "", storeIdentity, "", true);
        }

        /**
         * Names a session the leader no longer has, with nothing else.
         */
        static Digest forgotten(StoredSession session) {
            return new Digest(session.getStore(), session.getSession().getFixSessionType(),
                    session.getSession().getFixSessionId(), "", "", "", false);
        }

        Digest withoutDocument() {
            return new Digest(store, fixSessionType, fixSessionId, value, "", "", removed);
        }

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
}
