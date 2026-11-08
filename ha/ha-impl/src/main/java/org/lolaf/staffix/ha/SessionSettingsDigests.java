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
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStore;
import org.lolaf.staffix.sessions.settings.document.FixSessionSettingsDocumentCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The leader's session settings as digests, for its follower to compare with its own. Taken on each session's document
 * with its placeholders unresolved, so two nodes resolving one differently agree.
 */
@Getter
public final class SessionSettingsDigests {

    /**
     * -- GETTER --
     *  Generation 0 until a first
     * : nothing to send.
     */
    private volatile Published published = new Published(0, List.of());

    public static List<Digest> of(List<FixSessionsSettingsStore> stores) {
        List<Digest> digests = new ArrayList<>();
        for (FixSessionsSettingsStore store : stores) {
            for (FixSessionSettings settings : store.getSettings()) {
                digests.add(new Digest(store.getInstanceId(), settings.getFixSessionType(), settings.getFixSessionId().toString(),
                        sha256(FixSessionSettingsDocumentCodec.toCanonicalUnresolvedJson(settings))));
            }
        }
        return digests;
    }

    private static String sha256(String text) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Each call is a new generation, sent to the followers even if nothing differs.
     */
    synchronized void publish(List<FixSessionsSettingsStore> stores) {
        published = new Published(published.getGeneration() + 1, of(stores));
    }

    @Value
    public static class Digest {
        String store;
        FixSessionType fixSessionType;
        String fixSessionId;
        String value;
    }

    @Value
    public static class Published {
        int generation;
        List<Digest> digests;
    }
}
