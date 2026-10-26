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
package org.lolaf.staffix.api.ha;

import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.api.session.FixSessionId;

import java.util.List;

/**
 * Where an engine's high availability stands, and what an operator can do with it, through
 * {@link org.lolaf.staffix.api.admin.AdminApi#getHa()}.
 */
public interface HaAdmin {

    HaStatus getStatus();

    /**
     * Every session configured with replication, whether this node leads or follows.
     */
    List<HaSessionStatus> getSessionsStatus();

    /**
     * Takes this node out of elections for maintenance, or back in; see {@link LeaderElection#setCandidate(boolean)}.
     *
     * @throws IllegalStateException when the leader election cannot, as {@link HaStatus#isCandidacySupported()} tells
     */
    void setCandidate(boolean candidate);

    /**
     * Replaces every follower's copy of every replicated session with a fresh one. Until a follower holds it again,
     * the session's {@code SYNC} sends do not wait for that follower.
     *
     * @throws IllegalStateException unless this node leads
     */
    void resync();

    /**
     * {@link #resync()} for one session.
     *
     * @throws IllegalArgumentException for a session this node does not replicate
     * @throws IllegalStateException    unless this node leads
     */
    void resync(FixSessionId fixSessionId, FixSessionType fixSessionType);
}
