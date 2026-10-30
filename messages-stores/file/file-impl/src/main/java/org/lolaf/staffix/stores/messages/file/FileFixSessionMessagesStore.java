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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.unsafe.UnsafeOperations;
import org.lolaf.ringos.unsafe.UnsafeOperationsApi;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;

@Slf4j
class FileFixSessionMessagesStore extends Startable.SimpleStartable<FixMessagesStore.FixSessionMessagesStore> implements FixMessagesStore.BatchingFixSessionMessagesStore {

    static final int BLOCK_HEADER_SIZE = 21;
    /**
     * Store header structure:
     * - inSeqNum   (long:8)
     * - padding    (UnsafeOperationsApi.get().getL1CacheLineSize() - 8)
     * - outSeqNum  (long:8)
     * - padding    (UnsafeOperationsApi.get().getL1CacheLineSize() - 8)
     * - lastMsgPos (int:4)
     * - padding    (UnsafeOperationsApi.get().getL1CacheLineSize() - 4)
     * <p>
     * Message storage structure:
     * - startMarker      (int:4)
     * - blocksCount      (byte:1)
     * - sequenceNumber   (long:8)
     * - messageSize      (int:4)
     * - messageBytes     (byte:x)
     * - endMarker        (int:4)
     */
    private static final int INCOMING_SEQUENCE_POSITION = 0;
    private static final int L1_CACHE_LINE_SIZE = UnsafeOperationsApi.ifAvailableDoReturn(UnsafeOperations::getL1CacheLineSize, UnsafeOperations.DEFAULT_L1_CACHE_LINE_SIZE);
    private static final int OUTGOING_SEQUENCE_POSITION = L1_CACHE_LINE_SIZE;
    private static final int LAST_MESSAGE_POSITION = L1_CACHE_LINE_SIZE * 2;
    private static final int STORE_HEADER_LEN = L1_CACHE_LINE_SIZE * 3;
    private static final int BLOCK_MARKER = 0xffffffff;

    private final AtomicLong outgoingSequenceNumber;
    private final AtomicLong incomingSequenceNumber;
    private final FileMessageStoreSettings settings;
    private final BiPredicate<MessageType, ByteBuffer> messageFilter;
    private final File sessionFile;
    private final int blockSize;
    private final FixSessionId fixSessionId;
    private final String storageIdentity;
    private MappedByteBuffer mappedByteBuffer;
    private ByteBuffer alignedByteBuffer;
    private int previousLapFrom;

    public FileFixSessionMessagesStore(FileMessageStoreSettings settings, FixSessionId fixSessionId) {
        this(settings, fixSessionId, null);
    }

    /**
     * @param storageIdentity the directory's, read by the {@link FileMessagesStore} as it starts; null for none
     */
    FileFixSessionMessagesStore(FileMessageStoreSettings settings, FixSessionId fixSessionId, String storageIdentity) {
        this.storageIdentity = storageIdentity;
        this.settings = settings;
        this.messageFilter = settings.getMessageFilter();
        this.outgoingSequenceNumber = new AtomicLong(1);
        this.incomingSequenceNumber = new AtomicLong(1);
        this.sessionFile = new File(settings.getStorageDirectoryPath(), fixSessionId.forFileName(".bin"));
        this.blockSize = settings.getBlockSize();
        if (blockSize < 32) {
            throw new IllegalArgumentException("Minimum block size is 32 bytes");
        }
        this.fixSessionId = fixSessionId;
    }

    @Override
    public Optional<String> getStorageIdentity() {
        return Optional.ofNullable(storageIdentity);
    }

    @Override
    protected void startMe() throws StartStopException {
        // some configured stores may start in parallel and use the same directory
        synchronized (FileFixSessionMessagesStore.class) {
            File storageDir = new File(settings.getStorageDirectoryPath());
            if (!storageDir.exists() && !storageDir.mkdirs()) {
                throw new IllegalStateException("Unable to create directory " + storageDir);
            }
        }
        boolean fileExists = sessionFile.exists();
        int fileLen = STORE_HEADER_LEN + (settings.getBlocksCount() * blockSize);
        int alignment = L1_CACHE_LINE_SIZE;
        if (mappedByteBuffer == null) {
            // null on a first start, and again after a stop released the previous mapping
            mappedByteBuffer = mapSessionFile(sessionFile, fileLen + alignment);
        }
        alignedByteBuffer = mappedByteBuffer.alignedSlice(alignment);
        // important set the limit to the wanted file size and not the size + alignment
        alignedByteBuffer.position(STORE_HEADER_LEN).limit(fileLen);
        if (fileExists) {
            incomingSequenceNumber.set(alignedByteBuffer.getLong(INCOMING_SEQUENCE_POSITION));
            outgoingSequenceNumber.set(alignedByteBuffer.getLong(OUTGOING_SEQUENCE_POSITION));
            alignedByteBuffer.position(alignedByteBuffer.getInt(LAST_MESSAGE_POSITION));
        } else {
            // important save the initial sequence number
            alignedByteBuffer.putLong(INCOMING_SEQUENCE_POSITION, incomingSequenceNumber.get());
            alignedByteBuffer.putLong(OUTGOING_SEQUENCE_POSITION, outgoingSequenceNumber.get());
        }
        previousLapFrom = alignedByteBuffer.position();
        log.info("File messages store for FIX session {} started with incomingSeqNum {} and outgoingSeqNum {}",
                fixSessionId, incomingSequenceNumber.get(), outgoingSequenceNumber.get());
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        MappedByteBuffer mapping = mappedByteBuffer;
        // dropped before the mapping goes, so that a write racing this stop finds no buffer instead of freed memory
        alignedByteBuffer = null;
        mappedByteBuffer = null;
        if (mapping != null) {
            // without Unsafe there is no unmapping to be had: the mapping then lives until the garbage collector takes
            // this object, which is what every version of this class did before
            UnsafeOperationsApi.ifAvailableDo(UnsafeOperations::invokeCleanerIfNeeded, mapping);
        }
        log.info("File messages store for FIX session {} stopped with incomingSeqNum {} and outgoingSeqNum {}",
                fixSessionId, incomingSequenceNumber.get(), outgoingSequenceNumber.get());
    }

    @Override
    public boolean filter(MessageType messageType, ByteBuffer message) {
        return messageFilter.test(messageType, message);
    }

    @Override
    public void resetSequenceNumbers() {
        incomingSequenceNumber.set(1);
        outgoingSequenceNumber.set(1);
        alignedByteBuffer.putLong(INCOMING_SEQUENCE_POSITION, 1)
                .putLong(OUTGOING_SEQUENCE_POSITION, 1)
                .putInt(LAST_MESSAGE_POSITION, STORE_HEADER_LEN)
                .position(STORE_HEADER_LEN);
        byte[] zeros = new byte[1024];
        while (alignedByteBuffer.remaining() > zeros.length) {
            alignedByteBuffer.put(zeros);
        }
        byte zero = (byte) 0;
        while (alignedByteBuffer.hasRemaining()) {
            alignedByteBuffer.put(zero);
        }
        alignedByteBuffer.position(STORE_HEADER_LEN);
        previousLapFrom = STORE_HEADER_LEN;
    }

    @Override
    public long getIncomingSeqNum() {
        return incomingSequenceNumber.get();
    }

    @Override
    public long getOutgoingSeqNum() {
        return outgoingSequenceNumber.get();
    }

    @Override
    public long getNextOutgoingSeqNum() {
        return outgoingSequenceNumber.getAndIncrement();
    }

    @Override
    public void storeNextIncomingSeqNum(long nextIncomingSeqNum) {
        incomingSequenceNumber.set(nextIncomingSeqNum);
        alignedByteBuffer.putLong(INCOMING_SEQUENCE_POSITION, nextIncomingSeqNum);
    }

    @Override
    public void storeNextOutgoingSeqNum(long nextOutgoingSeqNum) {
        outgoingSequenceNumber.set(nextOutgoingSeqNum);
        alignedByteBuffer.putLong(OUTGOING_SEQUENCE_POSITION, nextOutgoingSeqNum);
    }

    @Override
    public void storeMessageSent(long outgoingSequenceNumber, ByteBuffer message) {
        alignedByteBuffer.putInt(LAST_MESSAGE_POSITION, storeMessage(outgoingSequenceNumber, message));
        storeNextOutgoingSeqNum(outgoingSequenceNumber + 1);
    }

    @Override
    public void storeSentFixMessages(SentFixMessage[] sentFixMessages, int sentFixMessageCount) {
        int nextPosition = 0;
        for (int i = 0; i < sentFixMessageCount; i++) {
            SentFixMessage sfm = sentFixMessages[i];
            nextPosition = storeMessage(sfm.getSequenceNumber(), sfm.getMessage());
        }
        alignedByteBuffer.putInt(LAST_MESSAGE_POSITION, nextPosition);
    }

    private int storeMessage(long outgoingSequenceNumber, ByteBuffer message) {
        int messageSize = message.remaining();
        int neededBlocksCount = blocksCount(messageSize);
        int requiredSpace = neededBlocksCount * blockSize;
        if (alignedByteBuffer.remaining() < requiredSpace) {
            log.debug("Rolling over for FIX message store {}", settings.getInstanceId());
            alignedByteBuffer.position(STORE_HEADER_LEN);
            previousLapFrom = STORE_HEADER_LEN;
        }
        int position = alignedByteBuffer.position();
        int nextPosition = position + requiredSpace;
        clearStartMarkersOverwritten(position, nextPosition);
        VarHandle.storeStoreFence();
        alignedByteBuffer.position(position + Integer.BYTES)
                .put((byte) neededBlocksCount)
                .putLong(outgoingSequenceNumber)
                .putInt(messageSize)
                .put(message);
        int endMarkerPosition = alignedByteBuffer.position();
        alignedByteBuffer.putInt(BLOCK_MARKER);
        if ((endMarkerPosition - STORE_HEADER_LEN) % blockSize == 0) {
            // on a block start, the end marker reads as a start marker over the header an older message left there
            alignedByteBuffer.put((byte) 0).putLong(0).putInt(0);
        }
        VarHandle.storeStoreFence();
        alignedByteBuffer.putInt(position, BLOCK_MARKER)
                .position(nextPosition);
        return nextPosition;
    }

    /**
     * A find running meanwhile skips a message without its start marker, or whose marker went while it copied it:
     * cleared on every message about to be overwritten, even in part, including one starting before the write, and
     * set on a new message only once it is whole, which a hard crash benefits from too.
     */
    private void clearStartMarkersOverwritten(int position, int nextPosition) {
        while (previousLapFrom < nextPosition) {
            int span = spanAt(alignedByteBuffer, previousLapFrom);
            alignedByteBuffer.putInt(previousLapFrom, 0);
            previousLapFrom += span == 0 ? blockSize : span;
        }
        for (int block = position; block < nextPosition; block += blockSize) {
            alignedByteBuffer.putInt(block, 0);
        }
    }

    /**
     * @return the bytes taken by the message starting at {@code position}, 0 for no whole message header there
     */
    private int spanAt(ByteBuffer file, int position) {
        if (file.limit() - position < BLOCK_HEADER_SIZE || file.getInt(position) != BLOCK_MARKER) {
            return 0;
        }
        VarHandle.loadLoadFence();
        int messageSize = file.getInt(position + 13);
        // the count is a byte, which a message longer than 255 blocks overflows: the size gives the span
        int span = messageSize < 0 ? 0 : blocksCount(messageSize) * blockSize;
        if (span == 0 || (byte) (span / blockSize) != file.get(position + 4) || span > file.limit() - position) {
            return 0;
        }
        return span;
    }

    /**
     * Safe while the session keeps writing, as resends and replication snapshots read on threads of their own: a
     * block written meanwhile is skipped, and gap filled, rather than handed over torn.
     */
    @Override
    public void find(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) {
        int lastPosition = alignedByteBuffer.getInt(LAST_MESSAGE_POSITION); // important do not call
        if (lastPosition == STORE_HEADER_LEN) {
            // no messages written store is empty
            return;
        }
        ByteBuffer file = alignedByteBuffer.duplicate();
        List<StoreMessage> messages = new ArrayList<>();
        int position = STORE_HEADER_LEN;
        while (position + BLOCK_HEADER_SIZE <= file.limit()) {
            position = readBlock(file, position, startSequenceNumber, stopSequenceNumber, messages);
        }
        // the file is scanned block by block rather than in sequence order, so the matching messages are ordered
        // before being handed over: the contract is ascending MsgSeqNum(34)
        messages.sort(Comparator.comparingLong(StoreMessage::getSeqNum));
        for (StoreMessage message : messages) {
            if (!consumer.onMessage(message.getSeqNum(), message.getMessage())) {
                return;
            }
        }
    }

    /**
     * @return where the next block starts
     */
    private int readBlock(ByteBuffer file, int position, long startSequenceNumber, long stopSequenceNumber,
                          List<StoreMessage> messages) {
        int span = spanAt(file, position);
        if (span == 0) {
            return position + blockSize;
        }
        long seqNum = file.getLong(position + 5);
        int messageSize = file.getInt(position + 13);
        if (messageSize < 0 || blocksCount(messageSize) * blockSize != span) {
            return position + blockSize;
        }
        if (seqNum < startSequenceNumber || seqNum > stopSequenceNumber) {
            return position + span;
        }
        byte[] msgBytes = new byte[messageSize];
        file.position(position + 17);
        file.get(msgBytes);
        int blockEndMarker = file.getInt(position + 17 + messageSize);
        VarHandle.loadLoadFence();
        if (file.getInt(position) != BLOCK_MARKER || file.getLong(position + 5) != seqNum
                || file.getInt(position + 13) != messageSize) {
            return position + blockSize;
        }
        if (blockEndMarker == BLOCK_MARKER) {
            messages.add(new StoreMessage(seqNum, ByteBuffer.wrap(msgBytes)));
        } else {
            log.error("Read a corrupted block for seqnum {} skipping message {}", seqNum, new String(msgBytes, SerDe.CHARSET));
        }
        return position + span;
    }

    private int blocksCount(int messageSize) {
        return (int) Math.ceil((messageSize + BLOCK_HEADER_SIZE) / (double) blockSize);
    }

    private MappedByteBuffer mapSessionFile(File sessionFile, int size) {
        Set<StandardOpenOption> options = new HashSet<>(EnumSet.of(
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ));
        if (settings.isSyncWrites()) {
            options.add(StandardOpenOption.DSYNC);
        }
        try (FileChannel fc = (FileChannel) Files.newByteChannel(sessionFile.toPath(), options)) {
            return fc.map(FileChannel.MapMode.READ_WRITE, 0, size);
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }
}
