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

import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore.StoreMessagesConsumer;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.replication.spi.RecordFlag;
import org.lolaf.staffix.ha.replication.spi.SnapshotSource;

import java.nio.ByteBuffer;

/**
 * A FIX session's store as a snapshot reads it: its state is its next incoming and outgoing seqnums, its records its
 * messages by seqnum, a round copying up to the last outgoing one. Allocates nothing once built.
 */
final class FixSessionSnapshotSource implements SnapshotSource, StoreMessagesConsumer {

    /**
     * The next incoming and outgoing seqnums.
     */
    static final int STATE_BYTES = 2 * Long.BYTES;

    private final FixSessionMessagesStore store;
    private final UnencryptedMessageTypes unencryptedMessageTypes;
    private @Nullable RecordConsumer consumer;

    FixSessionSnapshotSource(FixSessionMessagesStore store, UnencryptedMessageTypes unencryptedMessageTypes) {
        this.store = store;
        this.unencryptedMessageTypes = unencryptedMessageTypes;
    }

    @Override
    public boolean isReadable() {
        return store.isStarted();
    }

    @Override
    public long captureState(ByteBuffer state) {
        long nextOutgoingSeqNum = store.getOutgoingSeqNum();
        state.clear().putLong(store.getIncomingSeqNum()).putLong(nextOutgoingSeqNum);
        return nextOutgoingSeqNum - 1;
    }

    /**
     * Seqnums start at 1.
     */
    @Override
    public void read(long fromPosition, long toPosition, RecordConsumer recordConsumer) {
        long from = Math.max(1, fromPosition);
        if (from > toPosition) {
            return;
        }
        consumer = recordConsumer;
        try {
            store.find(from, toPosition, this);
        } finally {
            consumer = null;
        }
    }

    @Override
    public boolean onMessage(long seqNum, ByteBuffer message) {
        boolean plaintext = unencryptedMessageTypes.isUnencrypted(
                MessageTypeHash.of(message, message.position(), message.remaining()));
        RecordFlag flag = plaintext ? RecordFlag.PLAINTEXT : RecordFlag.NONE;
        return consumer.onRecord(StoreEventType.STORE_MESSAGE.getCode(), flag, seqNum, message, message.position(),
                message.remaining());
    }
}
