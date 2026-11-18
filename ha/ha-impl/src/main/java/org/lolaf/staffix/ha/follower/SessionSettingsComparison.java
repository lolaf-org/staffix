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
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.ha.FixEngineHa.EngineAccess;
import org.lolaf.staffix.api.ha.HaStatus.SessionSettingsDifference;
import org.lolaf.staffix.ha.SessionSettingsDigests;
import org.lolaf.staffix.ha.SessionSettingsDigests.Digest;
import org.lolaf.staffix.ha.SessionSettingsDigests.StoredSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * How this node's session settings differ from the leader's, as of the leader's last digests and this node's stores now.
 */
public final class SessionSettingsComparison {

    private final EngineAccess engine;
    private final SessionSettingsApplier applier;
    private final Map<StoredSession, Digest> receiving = new HashMap<>();
    private int receivingGeneration;
    private @Nullable Map<StoredSession, Digest> leaders;
    /**
     * Where the leader's documents are applied: a store write must not hold up the lane's network thread.
     */
    @Setter
    private Consumer<Runnable> applyExecutor = Runnable::run;
    @Getter
    private volatile List<SessionSettingsDifference> differences = List.of();

    SessionSettingsComparison(EngineAccess engine) {
        this.engine = engine;
        this.applier = new SessionSettingsApplier(engine);
    }

    private static SessionSettingsDifference difference(StoredSession key, SessionSettingsDifference.Kind kind) {
        return SessionSettingsDifference.builder().store(key.getStore()).fixSessionType(key.getSession().getFixSessionType())
                .fixSessionId(key.getSession().getFixSessionId()).kind(kind).build();
    }

    synchronized void onDigest(int generation, Digest digest) {
        receive(generation);
        receiving.put(digest.storedSession(), digest);
    }

    synchronized void onDigestsComplete(int generation, int count) {
        receive(generation);
        if (receiving.size() == count) {
            leaders = new HashMap<>(receiving);
            compare();
            List<Digest> received = List.copyOf(receiving.values());
            applyExecutor.accept(() -> applyIfStillFollowing(received));
        }
    }

    /**
     * A new generation drops what was received of the one before, which the leader replaced before sending it whole.
     */
    private void receive(int generation) {
        if (generation != receivingGeneration) {
            receivingGeneration = generation;
            receiving.clear();
        }
    }

    private void applyIfStillFollowing(List<Digest> received) {
        synchronized (this) {
            if (leaders == null) {
                return;
            }
        }
        applier.apply(received);
    }

    /**
     * This node's stores changed, or it stopped following.
     */
    public synchronized void compare() {
        if (leaders == null) {
            return;
        }
        Map<StoredSession, String> own = new HashMap<>();
        SessionSettingsDigests.of(engine.getFixSessionsSettingsStores()).forEach(digest -> own.put(digest.storedSession(), digest.getValue()));
        List<SessionSettingsDifference> found = new ArrayList<>();
        leaders.forEach((key, digest) -> {
            if (digest.isRemoved()) {
                return;
            }
            String here = own.get(key);
            if (here == null) {
                found.add(difference(key, SessionSettingsDifference.Kind.MISSING_HERE));
            } else if (!here.equals(digest.getValue())) {
                found.add(difference(key, SessionSettingsDifference.Kind.DIFFERENT));
            }
        });
        own.keySet().stream().filter(key -> !leaders.containsKey(key) || leaders.get(key).isRemoved())
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
}
