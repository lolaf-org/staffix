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
package org.lolaf.staffix.impl;

import org.lolaf.ringos.Deadline;

/**
 * An initiator or acceptor, which runs its sessions only while the engine leads. Started on an engine that does not
 * lead, it builds no session until {@link #activate()}.
 */
interface RunsWhileLeading {

    /**
     * The engine leads: builds and connects, or binds, what was started meanwhile.
     */
    void activate();

    /**
     * The engine no longer leads: stops the sessions and closes their stores, keeping the endpoint started for the
     * next {@link #activate()}.
     *
     * @param logout logs the sessions out first, for an engine handing leadership over; else they are dropped
     *               without a Logout, which would take a MsgSeqNum(34) the new leader never learns
     */
    void deactivate(Deadline deadline, boolean logout);

    /**
     * The deadline of a deactivation without logout: the endpoint's own shutdown delay.
     */
    Deadline dropDeadline();
}
