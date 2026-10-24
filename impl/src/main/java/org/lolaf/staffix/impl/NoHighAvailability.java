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

import lombok.NoArgsConstructor;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.ha.FixEngineHa;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;

import static lombok.AccessLevel.PRIVATE;

/**
 * The high availability of an engine configured without any: stateless, so one instance serves every engine.
 */
@NoArgsConstructor(access = PRIVATE)
final class NoHighAvailability implements FixEngineHa {

    static final NoHighAvailability INSTANCE = new NoHighAvailability();

    @Override
    public FixSessionMessagesStore replicatingStore(FixSessionSettings settings, FixSessionMessagesStore store) {
        throw new IllegalStateException("FIX session " + settings.getFixSessionId() + " asks for replication mode "
                + settings.getHaSessionSettings().getReplicationMode()
                + ", but its engine has no high availability configured");
    }

    @Override
    public FixEngineHa start() {
        return this;
    }

    @Override
    public FixEngineHa stop(Deadline stopDeadline) {
        return this;
    }

    @Override
    public boolean isStarted() {
        return true;
    }
}
