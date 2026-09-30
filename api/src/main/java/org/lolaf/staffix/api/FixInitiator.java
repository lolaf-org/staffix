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
package org.lolaf.staffix.api;

import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;

import java.util.List;

/**
 * The outbound half of a FIX connection: one session at a time, dialled out to its target's addresses and re-dialled
 * on its own after a drop.
 *
 * <p>An initiator runs one session at a time, which is why {@link #getSession()} takes no argument; a process talking
 * to several counterparties builds an initiator for each. Its other targets are the same counterparty reached another
 * way, and are only run once {@link #switchTo switched} to. {@link FixAcceptor} is the other side, and holds many.
 */
public interface FixInitiator extends Startable<FixInitiator> {

    /**
     * Stops, giving the session up to {@link FixInitiatorBuilder#getShutdownMaxDelay()} to log out.
     */
    FixInitiator stop();

    /**
     * Whether the active session has a connection, logged on or not.
     */
    boolean isConnected();

    /**
     * The active session; another instance after a {@link #switchTo switch}, so it must not be held across one.
     */
    FixSession getSession();

    /**
     * The sessions this initiator can run: the {@link FixInitiatorBuilder#getMainTarget() main target} first, then the
     * {@link FixInitiatorBuilder#getBackupTargets() backup targets}.
     */
    List<FixSessionId> getFixSessionIds();

    /**
     * Makes the given session the active one. A logged on session is logged out first, waiting up to
     * {@link FixInitiatorBuilder#getShutdownMaxDelay()}, then the target's addresses are dialled. Each session keeps
     * its own sequence numbers. Switching to the active session does nothing; on a stopped initiator it only picks
     * the session the next {@link #start()} runs. If the new session fails to start, the initiator is stopped and the
     * failure rethrown, with that session still picked, so a {@link #start()} retries it.
     *
     * @throws IllegalArgumentException if the session is not one of {@link #getFixSessionIds()}
     * @throws IllegalStateException    if the session's settings were removed from its store
     */
    void switchTo(FixSessionId fixSessionId);

}