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

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A registry for accessing the {@link FixSession} instances of one FIX engine, obtained from
 * {@code FixEngine.getFixSessionRegistry()}.
 * <p>
 * The scope is deliberately the engine rather than the JVM. Sessions are keyed by {@link FixSessionId}, and two
 * engines may legitimately be configured with the same id - separate deployments sharing a JVM, or a test standing up
 * both ends of a session - so a single process-wide registry cannot hold both: one would displace the other, and
 * whichever stopped first would take the survivor's entry with it.
 */
public interface FixSessionRegistry {

    /**
     * Find a FIX session by its session ID.
     *
     * @param id the session ID to look up
     * @return an {@link Optional} containing the matching {@link FixSession}, or empty if not found
     */
    Optional<FixSession> find(FixSessionId id);

    /**
     * Find a FIX session whose session ID matches the given predicate.
     * If multiple sessions match, the first match is returned.
     *
     * @param predicate a predicate applied to each registered {@link FixSessionId}
     * @return an {@link Optional} containing the first matching {@link FixSession}, or empty if none match
     */
    Optional<FixSession> find(Predicate<FixSessionId> predicate);

    /**
     * Find all FIX sessions whose session ID matches the given predicate.
     *
     * @param predicate a predicate applied to each registered {@link FixSessionId}
     * @return a {@link List} of all matching {@link FixSession} instances, or an empty list if none match
     */
    List<FixSession> findAll(Predicate<FixSessionId> predicate);
}
