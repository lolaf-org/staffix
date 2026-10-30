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

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.stores.FixMessagesStore.BatchingFixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;

/**
 * A store the high availability opens for a replica: its probes are evaluated while it runs, under the session's scope,
 * which no running session holds on a follower. A store that batches writes keeps batching through it.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
class ReplicaStore implements FixSessionMessagesStore {

    @Delegate(excludes = Lifecycle.class)
    private final FixSessionMessagesStore store;
    private final Runnable registerProbes;
    private final Runnable unregisterProbes;

    static ReplicaStore of(FixSessionMessagesStore store, Runnable registerProbes, Runnable unregisterProbes) {
        return store instanceof BatchingFixSessionMessagesStore
                ? new Batching((BatchingFixSessionMessagesStore) store, registerProbes, unregisterProbes)
                : new ReplicaStore(store, registerProbes, unregisterProbes);
    }

    @Override
    public FixSessionMessagesStore start() {
        store.start();
        registerProbes.run();
        return this;
    }

    @Override
    public FixSessionMessagesStore stop(Deadline stopDeadline) {
        unregisterProbes.run();
        store.stop(stopDeadline);
        return this;
    }

    private static final class Batching extends ReplicaStore implements BatchingFixSessionMessagesStore {

        private final BatchingFixSessionMessagesStore store;

        private Batching(BatchingFixSessionMessagesStore store, Runnable registerProbes, Runnable unregisterProbes) {
            super(store, registerProbes, unregisterProbes);
            this.store = store;
        }

        @Override
        public void storeSentFixMessages(SentFixMessage[] sentFixMessages, int sentFixMessageCount) {
            store.storeSentFixMessages(sentFixMessages, sentFixMessageCount);
        }
    }

    private interface Lifecycle {

        FixSessionMessagesStore start();

        FixSessionMessagesStore stop(Deadline stopDeadline);
    }
}
