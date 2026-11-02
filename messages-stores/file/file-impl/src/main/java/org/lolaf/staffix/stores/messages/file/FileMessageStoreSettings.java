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
package org.lolaf.staffix.stores.messages.file;

import lombok.Builder;
import lombok.Getter;
import org.jspecify.annotations.NullMarked;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.function.BiPredicate;

/**
 * Where the file message store keeps its files, and how it flushes them.
 */
@NullMarked
@Getter
@Builder(toBuilder = true)
public class FileMessageStoreSettings implements FixMessagesStoreSettings {

    public static final int DEFAULT_BLOCKS_SIZE = 64;
    /**
     * Default blocks count for 10k, 1kb message with a default 64 bytes block size
     */
    public static final int DEFAULT_BLOCKS_COUNT = calculateBlocksCount(DEFAULT_BLOCKS_SIZE, 10 * 1024, 1024);
    /**
     * Names this instance, so a session can select it by id when more than one is configured.
     */
    @Builder.Default
    private final String instanceId = DEFAULT_INSTANCE_ID;
    /**
     * The directory where the file messages store will be saved
     */
    private String storageDirectoryPath;
    /**
     * Number of blocks for the store, when all the blocks have been used, the messages storage will roll to the beginning of
     * So if each message you store is about 1k, your block size is 1k and block count 1k, you can store max 10k messages in the store
     */
    @Builder.Default
    private int blocksCount = DEFAULT_BLOCKS_COUNT;
    /**
     * At least 32 and a multiple of 8. A message takes 20 bytes more than its size, rounded up to whole blocks: with 256
     * byte blocks, a 1004 byte message takes exactly 4. Match it to the typical message size to waste less space.
     */
    @Builder.Default
    private int blockSize = DEFAULT_BLOCKS_SIZE;
    /**
     * Forces each write to disk before the store call returns, so it survives an OS crash or a power loss. Off, a write
     * reaches the page cache only: it survives the process crashing, not the machine.
     */
    @Builder.Default
    private boolean syncWrites = true;
    /**
     * Decides what is worth storing. A store only has to hold what could be asked for again.
     */
    @Builder.Default
    private BiPredicate<MessageType, ByteBuffer> messageFilter = (messageType, message) -> false;
    /**
     * See {@link FixMessagesStoreSettings#getResourceCheckInterval()}.
     */
    @Builder.Default
    private Duration resourceCheckInterval = DEFAULT_RESOURCE_CHECK_INTERVAL;
    /**
     * See {@link FixMessagesStoreSettings#getAlertClearDelay()}.
     */
    @Builder.Default
    private Duration alertClearDelay = DEFAULT_ALERT_CLEAR_DELAY;

    /**
     * Calculates the required blocks count
     *
     * @param blockSize            the configured block size
     * @param desiredMessagesCount the desired message count in your store
     * @param averageMessageSize   your average stored message size in bytes
     * @return the number of blocks to set up in your settings {@link #blocksCount}
     */
    public static int calculateBlocksCount(int blockSize, int desiredMessagesCount, int averageMessageSize) {
        return desiredMessagesCount * (averageMessageSize / blockSize);
    }

}