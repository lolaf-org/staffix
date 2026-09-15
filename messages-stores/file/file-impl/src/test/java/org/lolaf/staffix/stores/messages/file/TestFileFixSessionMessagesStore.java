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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.stores.messages.testkit.AbstractFixSessionMessagesStoreTest;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;

class TestFileFixSessionMessagesStore extends AbstractFixSessionMessagesStoreTest<FileFixSessionMessagesStore> {

    File storeFile;

    protected FileFixSessionMessagesStore createSessionStore(FixSessionId sessionId, BiPredicate<MessageType, ByteBuffer> messagesFilter) {

        storeFile = new File("./target/store");
        FileMessageStoreSettings settings = FileMessageStoreSettings.builder()
                .storageDirectoryPath(storeFile.getPath())
                .messageFilter(messagesFilter)
                .blockSize(32)
                .blocksCount(10 * 1024)
                .build();
        return new FileFixSessionMessagesStore(settings, sessionId);
    }

    @Override
    protected void beforeTearDown() throws Exception {
        deleteDir(storeFile);
    }

    void deleteDir(File f) throws IOException {
        if (f.isDirectory()) {
            for (File c : f.listFiles()) {
                deleteDir(c);
            }
        }
        if (!f.delete()) {
            throw new IOException("Failed to delete file: " + f);
        }
    }

    @Test
    void testMessagesMaxSizeRollOver() {
        FileMessageStoreSettings settings = FileMessageStoreSettings.builder()
                .storageDirectoryPath(storeFile.getPath())
                .messageFilter(messagesFilter)
                .blockSize(32)
                .blocksCount(16)
                .build();
        messageStore = new FileFixSessionMessagesStore(settings, sessionId);

        messageStore.start();
        // block size = 32, blocks count = 16
        // message with 11 chars will take 1 block (block header is 21), more will take 2 or more

        int messageSizeForOneBlock = 32 - FileFixSessionMessagesStore.BLOCK_HEADER_SIZE;
        int i = 0;
        for (; i < 8; i++) {
            messageStore.storeMessageSent(i + 1, ByteBuffer.wrap((i + "a".repeat(messageSizeForOneBlock - 1)).getBytes()));
        }
        for (; i < 12; i++) {
            messageStore.storeMessageSent(i + 1, ByteBuffer.wrap((i + "b".repeat(messageSizeForOneBlock)).getBytes()));
        }

        List<FixMessagesStore.FixSessionMessagesStore.StoreMessage> messages = messageStore.find(1, 12);
        assertThat(messages).hasSize(12);
        assertMessage(messages, 0, 0 + "a".repeat(messageSizeForOneBlock - 1));
        assertMessage(messages, 7, 7 + "a".repeat(messageSizeForOneBlock - 1));
        assertMessage(messages, 8, 8 + "b".repeat(messageSizeForOneBlock));
        assertMessage(messages, 11, 11 + "b".repeat(messageSizeForOneBlock));

        // rolling over with 2 messages taking 2 blocks
        messageStore.storeMessageSent(13, ByteBuffer.wrap("c".repeat(messageSizeForOneBlock + 1).getBytes()));
        messageStore.storeMessageSent(14, ByteBuffer.wrap("d".repeat(messageSizeForOneBlock + 2).getBytes()));

        messages = messageStore.find(1, 12);
        assertThat(messages).hasSize(8);

        messages = messageStore.find(1, 14);
        assertThat(messages).hasSize(10);

        assertMessage(messages, 0, 4 + "a".repeat(messageSizeForOneBlock - 1));
        assertMessage(messages, 1, 5 + "a".repeat(messageSizeForOneBlock - 1));
        assertMessage(messages, 2, 6 + "a".repeat(messageSizeForOneBlock - 1));
        assertMessage(messages, 3, 7 + "a".repeat(messageSizeForOneBlock - 1));
        assertMessage(messages, 4, 8 + "b".repeat(messageSizeForOneBlock));
        assertMessage(messages, 5, 9 + "b".repeat(messageSizeForOneBlock));
        assertMessage(messages, 6, 10 + "b".repeat(messageSizeForOneBlock));
        assertMessage(messages, 7, 11 + "b".repeat(messageSizeForOneBlock));
        assertMessage(messages, 8, "c".repeat(messageSizeForOneBlock + 1));
        assertMessage(messages, 9, "d".repeat(messageSizeForOneBlock + 2));
    }

    @Test
    void testMessageStorageOverMultipleBlocks() {
        FileMessageStoreSettings settings = FileMessageStoreSettings.builder()
                .storageDirectoryPath(storeFile.getPath())
                .messageFilter(messagesFilter)
                .blockSize(32)
                .blocksCount(16)
                .build();
        messageStore = new FileFixSessionMessagesStore(settings, sessionId);

        messageStore.start();

        // block is 32 bytes, block header is 13 bytes, so to store a message over 2 blocks or more we must have a message greater than 19 bytes
        messageStore.storeMessageSent(1, ByteBuffer.wrap("a".repeat(19).getBytes()));
        messageStore.storeMessageSent(2, ByteBuffer.wrap("a".repeat(20).getBytes()));
        messageStore.storeMessageSent(3, ByteBuffer.wrap("a".repeat(21).getBytes()));

        List<FixMessagesStore.FixSessionMessagesStore.StoreMessage> messages = messageStore.find(1, 3);
        assertThat(messages).hasSize(3);
        assertMessage(messages, 0, "a".repeat(19));
        assertMessage(messages, 1, "a".repeat(20));
        assertMessage(messages, 2, "a".repeat(21));
    }
}