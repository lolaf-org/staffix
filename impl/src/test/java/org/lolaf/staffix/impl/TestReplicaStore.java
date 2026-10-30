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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.stores.FixMessagesStore.BatchingFixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.BatchingFixSessionMessagesStore.SentFixMessage;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TestReplicaStore {

    @Test
    void aStoreThatBatchesKeepsBatchingThroughTheReplicaStore() {
        BatchingFixSessionMessagesStore store = mock(BatchingFixSessionMessagesStore.class);
        SentFixMessage[] messages = new SentFixMessage[2];

        FixSessionMessagesStore replica = ReplicaStore.of(store, () -> {
        }, () -> {
        });
        ((BatchingFixSessionMessagesStore) replica).storeSentFixMessages(messages, 1);

        verify(store).storeSentFixMessages(messages, 1);
    }

    @Test
    void aStoreThatDoesNotBatchIsNotOfferedAsOneThatDoes() {
        FixSessionMessagesStore replica = ReplicaStore.of(mock(FixSessionMessagesStore.class), () -> {
        }, () -> {
        });

        assertThat(replica).isNotInstanceOf(BatchingFixSessionMessagesStore.class);
    }
}
