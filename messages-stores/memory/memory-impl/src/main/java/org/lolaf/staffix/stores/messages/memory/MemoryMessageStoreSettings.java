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
package org.lolaf.staffix.stores.messages.memory;

import lombok.Builder;
import lombok.Getter;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;

import java.nio.ByteBuffer;
import java.util.function.BiPredicate;

/**
 * How much the in-memory store retains before discarding the oldest.
 */
@Getter
@Builder(toBuilder = true)
public class MemoryMessageStoreSettings implements FixMessagesStoreSettings {

    /**
     * Names this instance, so a session can select it by id when more than one is configured.
     */
    @Builder.Default
    private final String instanceId = DEFAULT_INSTANCE_ID;
    /**
     * Max entries in memory, set to zero to fully disable messages storage
     */
    @Builder.Default
    private int maxEntriesInMemory = 1024;
    /**
     * Use direct memory buffers to keep the messages to store in memory
     */
    @Builder.Default
    private boolean useDirectMemory = true;
    /**
     * Decides what is worth storing. A store only has to hold what could be asked for again.
     */
    @Builder.Default
    private BiPredicate<MessageType, ByteBuffer> messageFilter = (messageType, message) -> false;

}