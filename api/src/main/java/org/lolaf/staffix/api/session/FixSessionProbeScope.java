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

package org.lolaf.staffix.api.session;

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.alerting.ProbeScope;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A session's probes as the alerting sees them: a scope of type {@value #TYPE}, named by the session's
 * {@link FixSessionId#getQualifiedName() qualified name} and labelled with its group and name.
 */
@UtilityClass
public class FixSessionProbeScope {

    public static final String TYPE = "session";
    public static final String GROUP_LABEL = "fixSessionGroup";
    public static final String NAME_LABEL = "fixSessionName";

    /**
     * Session ids are interned for the JVM's lifetime, so their scopes are too: a status poll looks one up per session.
     */
    private static final Map<FixSessionId, ProbeScope> SCOPES = new ConcurrentHashMap<>();

    public static ProbeScope of(FixSessionId fixSessionId) {
        ProbeScope scope = SCOPES.get(fixSessionId);
        return scope != null ? scope : SCOPES.computeIfAbsent(fixSessionId, FixSessionProbeScope::build);
    }

    private static ProbeScope build(FixSessionId fixSessionId) {
        return ProbeScope.builder()
                .type(TYPE)
                .name(fixSessionId.getQualifiedName())
                .label(GROUP_LABEL, fixSessionId.getGroup())
                .label(NAME_LABEL, fixSessionId.getName())
                .build();
    }

    public static boolean isSession(ProbeScope scope) {
        return TYPE.equals(scope.getType());
    }
}
