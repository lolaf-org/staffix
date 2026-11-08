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

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.ha.FixEngineHa.EngineAccess;
import org.lolaf.staffix.api.ha.HaStatus.SessionSettingsDifference;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.ha.SessionSettingsDigests;
import org.lolaf.staffix.ha.SessionSettingsDigests.Digest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How this node's session settings differ from the leader's, as of the leader's last digests and this node's stores now.
 */
@RequiredArgsConstructor
public final class SessionSettingsComparison {

    private final EngineAccess engine;
    private final Map<DigestKey, String> receiving = new HashMap<>();
    private int receivingGeneration;
    private @Nullable Map<DigestKey, String> leaders;
    @Getter
    private volatile List<SessionSettingsDifference> differences = List.of();

    private static DigestKey key(Digest digest) {
        return new DigestKey(digest.getStore(), digest.getFixSessionType(), digest.getFixSessionId());
    }

    private static SessionSettingsDifference difference(DigestKey key, SessionSettingsDifference.Kind kind) {
        return SessionSettingsDifference.builder().store(key.store).fixSessionType(key.fixSessionType).fixSessionId(key.fixSessionId)
                .kind(kind).build();
    }

    synchronized void onDigest(int generation, Digest digest) {
        if (generation != receivingGeneration) {
            receivingGeneration = generation;
            receiving.clear();
        }
        receiving.put(key(digest), digest.getValue());
    }

    synchronized void onDigestsComplete(int generation, int count) {
        if (generation != receivingGeneration) {
            receivingGeneration = generation;
            receiving.clear();
        }
        if (receiving.size() == count) {
            leaders = new HashMap<>(receiving);
            compare();
        }
    }

    /**
     * This node's stores changed, or it stopped following.
     */
    public synchronized void compare() {
        if (leaders == null) {
            return;
        }
        Map<DigestKey, String> own = new HashMap<>();
        SessionSettingsDigests.of(engine.getFixSessionsSettingsStores()).forEach(digest -> own.put(key(digest), digest.getValue()));
        List<SessionSettingsDifference> found = new ArrayList<>();
        leaders.forEach((key, value) -> {
            String here = own.get(key);
            if (here == null) {
                found.add(difference(key, SessionSettingsDifference.Kind.MISSING_HERE));
            } else if (!here.equals(value)) {
                found.add(difference(key, SessionSettingsDifference.Kind.DIFFERENT));
            }
        });
        own.keySet().stream().filter(key -> !leaders.containsKey(key))
                .forEach(key -> found.add(difference(key, SessionSettingsDifference.Kind.EXTRA_HERE)));
        differences = List.copyOf(found);
    }

    /**
     * This node leads now: there is no leader to differ from.
     */
    public synchronized void clear() {
        leaders = null;
        differences = List.of();
    }

    @Value
    private static class DigestKey {
        String store;
        FixSessionType fixSessionType;
        String fixSessionId;
    }
}
