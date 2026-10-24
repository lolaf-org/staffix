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
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.ha.FixEngineHa;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.ha.log.ReplicationLog;

@Getter
public class ReplicationFixEngineHa extends Startable.SimpleStartable<FixEngineHa> implements FixEngineHa {

    private final ReplicationLog log;
    private final ReplicatedSessions sessions = new ReplicatedSessions();

    public ReplicationFixEngineHa(ReplicationHaSettings settings) {
        this.log = new ReplicationLog(settings.getLogCapacity());
    }

    @Override
    public FixSessionMessagesStore replicatingStore(FixSessionSettings sessionSettings, FixSessionMessagesStore store) {
        return new ReplicatingFixSessionMessagesStore(store, log, sessions.register(sessionSettings, store));
    }

    @Override
    protected void startMe() {
        // the publisher and the follower receiver start here once they exist
    }

    @Override
    protected void stopMe(Deadline stopDeadline) {
        // the publisher and the follower receiver stop here once they exist
    }

    public static class ReplicationFixEngineHaFactory implements FixEngineHa.FixEngineHaFactory<ReplicationHaSettings> {

        @Override
        public Class<ReplicationHaSettings> getSettingsClass() {
            return ReplicationHaSettings.class;
        }

        @Override
        public FixEngineHa newInstance(ReplicationHaSettings settings) {
            return new ReplicationFixEngineHa(settings);
        }
    }
}
