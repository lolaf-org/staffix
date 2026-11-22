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
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The engine's replicated sessions, each with a key that stays its own for the life of the engine, across the
 * restarts that rebuild a session. Keys are dense from 0, in the order sessions were first registered.
 */
public final class ReplicatedSessions {

    /**
     * Carried here as every connection already has the sessions it announces.
     */
    @Getter
    private final SessionSettingsDigests settingsDigests;

    /**
     * By type first, as one id may be configured as an initiator and as an acceptor: two sessions.
     */
    private final Map<FixSessionType, Map<FixSessionId, ReplicatedSession>> byTypeAndId;
    private final List<ReplicatedSession> byKey;
    /**
     * Each session's, by key, for the sender deciding per frame: replaced whole on every register.
     */
    private volatile UnencryptedMessageTypes[] unencryptedMessageTypes;

    public ReplicatedSessions() {
        this.settingsDigests = new SessionSettingsDigests();
        this.byTypeAndId = new EnumMap<>(FixSessionType.class);
        this.byKey = new CopyOnWriteArrayList<>();
        this.unencryptedMessageTypes = new UnencryptedMessageTypes[0];
        for (FixSessionType fixSessionType : FixSessionType.values()) {
            byTypeAndId.put(fixSessionType, new HashMap<>());
        }
    }

    /**
     * Registers a session as it is built, or takes the latest settings and store of one already known.
     */
    synchronized ReplicatedSession register(FixSessionSettings settings, FixSessionMessagesStore store) {
        ReplicatedSession.Config config = ReplicatedSession.Config.of(settings.getHighAvailability(), store);
        Map<FixSessionId, ReplicatedSession> byId = byTypeAndId.get(settings.getFixSessionType());
        ReplicatedSession known = byId.get(settings.getFixSessionId());
        if (known != null) {
            known.update(config);
            cacheUnencryptedMessageTypes(known.getKey(), config);
            return known;
        }
        // built with its config before it is listed, so a sender never finds it without one
        ReplicatedSession added = new ReplicatedSession(byKey.size(), settings.getFixSessionId(), settings.getFixSessionType(), config);
        cacheUnencryptedMessageTypes(added.getKey(), config);
        byId.put(settings.getFixSessionId(), added);
        byKey.add(added);
        return added;
    }

    private void cacheUnencryptedMessageTypes(int key, ReplicatedSession.Config config) {
        UnencryptedMessageTypes[] cached = Arrays.copyOf(unencryptedMessageTypes,
                Math.max(unencryptedMessageTypes.length, key + 1));
        cached[key] = config.getUnencryptedMessageTypes();
        unencryptedMessageTypes = cached;
    }

    /**
     * Whether the session's {@code unencryptedMessageTypes} names the type, on this leader's settings. Any thread;
     * allocates nothing.
     */
    public boolean isUnencrypted(int key, int messageTypeHash) {
        return unencryptedMessageTypes[key].isUnencrypted(messageTypeHash);
    }

    /**
     * Any thread.
     */
    public ReplicatedSession get(int key) {
        return byKey.get(key);
    }

    /**
     * Null for a session not built yet. Any thread.
     */
    synchronized @Nullable ReplicatedSession find(FixSessionId fixSessionId, FixSessionType fixSessionType) {
        return byTypeAndId.get(fixSessionType).get(fixSessionId);
    }

    /**
     * How many sessions were registered; keys run from 0 to this, excluded. Any thread.
     */
    public int count() {
        return byKey.size();
    }

    public Collection<ReplicatedSession> all() {
        return byKey;
    }
}
