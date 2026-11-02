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
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.stores.messages.testkit.AbstractAllocationFreeFixSessionMessagesStoreTest;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFileFixSessionMessagesStore extends AbstractAllocationFreeFixSessionMessagesStoreTest<FileFixSessionMessagesStore> {

    File storeFile;

    protected FileFixSessionMessagesStore createSessionStore(FixSessionId sessionId, BiPredicate<MessageType, ByteBuffer> messagesFilter) {

        storeFile = new File("./target/store");
        FileMessageStoreSettings settings = FileMessageStoreSettings.builder()
                .storageDirectoryPath(storeFile.getPath())
                .syncWrites(false)
                .messageFilter(messagesFilter)
                .blockSize(32)
                .blocksCount(10 * 1024)
                .build();
        return new FileFixSessionMessagesStore(settings, sessionId);
    }

    @Override
    protected void beforeTearDown() throws Exception {
        if (storeFile.exists()) {
            deleteDir(storeFile);
        }
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
                .syncWrites(false)
                .messageFilter(messagesFilter)
                .blockSize(32)
                .blocksCount(16)
                .build();
        messageStore = new FileFixSessionMessagesStore(settings, sessionId);

        messageStore.start();
        // block size = 32, blocks count = 16
        // a message of 12 chars takes 1 block (20 bytes of overhead), more take 2 or more

        int messageSizeForOneBlock = 32 - FileFixSessionMessagesStore.RECORD_OVERHEAD;
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
                .syncWrites(false)
                .messageFilter(messagesFilter)
                .blockSize(32)
                .blocksCount(16)
                .build();
        messageStore = new FileFixSessionMessagesStore(settings, sessionId);

        messageStore.start();

        // a block is 32 bytes and a message takes 20 more, so a message over 12 bytes spans 2 blocks
        messageStore.storeMessageSent(1, ByteBuffer.wrap("a".repeat(19).getBytes()));
        messageStore.storeMessageSent(2, ByteBuffer.wrap("a".repeat(20).getBytes()));
        messageStore.storeMessageSent(3, ByteBuffer.wrap("a".repeat(21).getBytes()));

        List<FixMessagesStore.FixSessionMessagesStore.StoreMessage> messages = messageStore.find(1, 3);
        assertThat(messages).hasSize(3);
        assertMessage(messages, 0, "a".repeat(19));
        assertMessage(messages, 1, "a".repeat(20));
        assertMessage(messages, 2, "a".repeat(21));
    }

    private FileFixSessionMessagesStore started(int blocksCount) {
        return started(blocksCount, false);
    }

    private FileFixSessionMessagesStore started(int blocksCount, boolean syncWrites) {
        messageStore = new FileFixSessionMessagesStore(FileMessageStoreSettings.builder()
                .storageDirectoryPath(storeFile.getPath())
                .syncWrites(syncWrites)
                .messageFilter(messagesFilter)
                .blockSize(32)
                .blocksCount(blocksCount)
                .build(), sessionId);
        messageStore.start();
        return messageStore;
    }

    private static ByteBuffer message(long seqNum) {
        return ByteBuffer.wrap(("m" + seqNum).getBytes());
    }

    private static List<Long> seqNums(List<FixMessagesStore.FixSessionMessagesStore.StoreMessage> messages) {
        return messages.stream().map(FixMessagesStore.FixSessionMessagesStore.StoreMessage::getSeqNum).collect(Collectors.toList());
    }

    private static List<Long> range(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().collect(Collectors.toList());
    }

    @Test
    void aFindReturnsTheRangeAmongMoreMessagesThanTheIndexFirstHolds() {
        FileFixSessionMessagesStore store = started(4 * 1024);
        for (long seqNum = 1; seqNum <= 3_000; seqNum++) {
            store.storeMessageSent(seqNum, message(seqNum));
        }

        assertThat(seqNums(store.find(1_500, 1_510))).isEqualTo(range(1_500, 1_510));
        assertThat(seqNums(store.find(1, 3_000))).isEqualTo(range(1, 3_000));
        assertMessage(store.find(2_999, 2_999), 0, "m2999");
    }

    @Test
    void aFindAfterTheFileLappedReturnsOnlyTheMessagesStillInIt() {
        FileFixSessionMessagesStore store = started(100);
        for (long seqNum = 1; seqNum <= 250; seqNum++) {
            store.storeMessageSent(seqNum, message(seqNum));
        }

        assertThat(seqNums(store.find(1, 250))).isEqualTo(range(151, 250));
    }

    @Test
    void aFileLappedManyTimesReusesTheIndexEntriesOfTheMessagesItOverwrote() {
        FileFixSessionMessagesStore store = started(100);
        for (long seqNum = 1; seqNum <= 10_000; seqNum++) {
            store.storeMessageSent(seqNum, message(seqNum));
        }

        assertThat(seqNums(store.find(1, 10_000))).isEqualTo(range(9_901, 10_000));
        assertThat(seqNums(store.find(9_950, 9_955))).isEqualTo(range(9_950, 9_955));
    }

    @Test
    void aStoreStartedAgainIndexesItsFileInTheOrderItWasWritten() {
        FileFixSessionMessagesStore store = started(100);
        for (long seqNum = 1; seqNum <= 250; seqNum++) {
            store.storeMessageSent(seqNum, message(seqNum));
        }
        store.stop(Deadline.unlimited());

        store = started(100);

        assertThat(seqNums(store.find(1, 250))).isEqualTo(range(151, 250));
        store.storeMessageSent(251, message(251));
        assertThat(seqNums(store.find(240, 260))).isEqualTo(range(240, 251));
    }

    @Test
    void aFindStopsWhereTheConsumerSays() {
        FileFixSessionMessagesStore store = started(100);
        for (long seqNum = 1; seqNum <= 20; seqNum++) {
            store.storeMessageSent(seqNum, message(seqNum));
        }
        List<Long> handed = new ArrayList<>();

        store.find(5, 20, (seqNum, message) -> handed.add(seqNum) && handed.size() < 3);

        assertThat(handed).containsExactly(5L, 6L, 7L);
    }

    @Test
    void aSeqNumLoweredWithoutAResetReplacesTheMessagesItStoresAgain() {
        FileFixSessionMessagesStore store = started(100);
        for (long seqNum = 1; seqNum <= 10; seqNum += 2) {
            store.storeMessageSent(seqNum, message(seqNum));
        }

        store.storeMessageSent(4, ByteBuffer.wrap("lowered 4".getBytes()));
        store.storeMessageSent(5, ByteBuffer.wrap("lowered 5".getBytes()));
        store.storeMessageSent(6, ByteBuffer.wrap("lowered 6".getBytes()));

        List<FixMessagesStore.FixSessionMessagesStore.StoreMessage> found = store.find(1, 10);
        assertThat(seqNums(found)).containsExactly(1L, 3L, 4L, 5L, 6L, 7L, 9L);
        assertMessage(found, 3, "lowered 5");
        store.stop(Deadline.unlimited());
        assertThat(seqNums(started(100).find(1, 10))).as("the same once indexed again")
                .containsExactly(1L, 3L, 4L, 5L, 6L, 7L, 9L);
    }

    @Test
    void aResetStartsTheIndexOver() {
        FileFixSessionMessagesStore store = started(100);
        for (long seqNum = 1; seqNum <= 10; seqNum++) {
            store.storeMessageSent(seqNum, message(seqNum));
        }

        store.resetSequenceNumbers();
        store.storeMessageSent(1, ByteBuffer.wrap("again".getBytes()));

        assertThat(store.find(1, 10)).singleElement().satisfies(found ->
                assertThat(new String(found.getMessage().array())).isEqualTo("again"));
    }

    @Test
    void aSyncedStoreKeepsABatchThatRolledOverAcrossARestart() {
        FileFixSessionMessagesStore store = started(16, true);
        for (long seqNum = 1; seqNum <= 12; seqNum++) {
            store.storeMessageSent(seqNum, message(seqNum));
        }
        FixMessagesStore.BatchingFixSessionMessagesStore.SentFixMessage[] batch = LongStream.rangeClosed(13, 20)
                .mapToObj(seqNum -> new TestingSentFixMessage(seqNum, message(seqNum)))
                .toArray(FixMessagesStore.BatchingFixSessionMessagesStore.SentFixMessage[]::new);

        store.storeSentFixMessagesAndSeqNums(batch, batch.length, 7, 21);
        store.stop(Deadline.unlimited());
        store = started(16, true);

        assertThat(seqNums(store.find(1, 20))).isEqualTo(range(5, 20));
        assertThat(store.getIncomingSeqNum()).isEqualTo(7);
        assertThat(store.getOutgoingSeqNum()).isEqualTo(21);
    }

    @Test
    void aSyncedStoreCallAllocatesNothing() {
        FileFixSessionMessagesStore store = started(1024, true);
        ByteBuffer message = ByteBuffer.wrap("8=FIX.4.4\u00019=5\u000135=0\u000110=000\u0001".getBytes());

        assertThat(allocatedBytes(seqNum -> store.storeMessageSent(seqNum, message.position(0)), 2_000, 200)).isZero();
    }

    @Test
    void aFileStartsWithItsFormatAtFixedOffsets() throws IOException {
        FileFixSessionMessagesStore store = started(16);
        store.storeNextIncomingSeqNum(7);
        store.storeMessageSent(3, message(3));
        store.stop(Deadline.unlimited());

        ByteBuffer header = ByteBuffer.wrap(Files.readAllBytes(sessionFile()));

        assertThat(header.getLong(0)).isEqualTo(FileFixSessionMessagesStore.MAGIC);
        assertThat(header.getInt(8)).isEqualTo(FileFixSessionMessagesStore.FORMAT_VERSION);
        assertThat(header.getLong(16)).isEqualTo(7);
        assertThat(header.getLong(24)).isEqualTo(4);
        assertThat(header.getInt(32)).isEqualTo(64 + 32);
        assertThat(header.getLong(64 + 8)).isEqualTo(3);
    }

    @Test
    void aFileOfAnOlderFormatFailsTheStartAndIsLeftAsItIs() throws IOException {
        byte[] olderFormat = ByteBuffer.allocate(1024).putLong(0, 12).putLong(64, 34).array();
        Files.createDirectories(storeFile.toPath());
        Files.write(sessionFile(), olderFormat);

        assertThatThrownBy(() -> started(16))
                .isInstanceOf(Startable.StartStopException.class)
                .hasMessageContaining(sessionFile().toString())
                .hasMessageContaining("0.9.1 or older");
        assertThat(Files.readAllBytes(sessionFile())).isEqualTo(olderFormat);
    }

    @Test
    void aFileOfAnUnknownVersionFailsTheStart() throws IOException {
        Files.createDirectories(storeFile.toPath());
        Files.write(sessionFile(), ByteBuffer.allocate(1024).putLong(0, FileFixSessionMessagesStore.MAGIC).putInt(8, 3).array());

        assertThatThrownBy(() -> started(16))
                .isInstanceOf(Startable.StartStopException.class)
                .hasMessageContaining("is of format 3");
    }

    @Test
    void aFileCreatedButNeverWrittenStartsEmpty() throws IOException {
        Files.createDirectories(storeFile.toPath());
        Files.write(sessionFile(), new byte[1024]);

        FileFixSessionMessagesStore store = started(16);
        store.storeMessageSent(1, message(1));

        assertThat(store.getIncomingSeqNum()).isEqualTo(1);
        assertThat(seqNums(store.find(1, 10))).containsExactly(1L);
    }

    @Test
    void aBlockSizeNotAMultipleOf8IsRefused() {
        FileMessageStoreSettings settings = FileMessageStoreSettings.builder()
                .storageDirectoryPath(storeFile.getPath())
                .blockSize(36)
                .build();

        assertThatThrownBy(() -> new FileFixSessionMessagesStore(settings, sessionId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiple of 8");
    }

    private Path sessionFile() {
        return new File(storeFile, sessionId.forFileName(".bin")).toPath();
    }
}
