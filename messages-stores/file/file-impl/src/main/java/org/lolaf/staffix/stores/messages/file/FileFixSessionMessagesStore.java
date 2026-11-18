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
import org.jspecify.annotations.Nullable;
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
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;

@Slf4j
class FileFixSessionMessagesStore extends Startable.SimpleStartable<FixMessagesStore.FixSessionMessagesStore> implements FixMessagesStore.BatchingFixSessionMessagesStore {

    /**
     * File format 1, at fixed offsets so that a file reads the same on any machine. Header, 64 bytes: magic (long),
     * version (int), padding (int), incoming seqnum (long), outgoing seqnum (long), next write position (int). Each
     * message starts on a block: start marker (int), size (int), seqnum (long), the message, end marker (int).
     */
    static final int FORMAT_VERSION = 1;
    static final long MAGIC = 0xC0DEF1E5_53544658L;
    static final int RECORD_OVERHEAD = 20;
    private static final int MAGIC_POSITION = 0;
    private static final int VERSION_POSITION = 8;
    private static final int INCOMING_SEQUENCE_POSITION = 16;
    private static final int OUTGOING_SEQUENCE_POSITION = 24;
    private static final int LAST_MESSAGE_POSITION = 32;
    private static final int STORE_HEADER_LEN = 64;
    private static final int SIZE_OFFSET = 4;
    private static final int SEQNUM_OFFSET = 8;
    private static final int PAYLOAD_OFFSET = 16;
    // no byte of it is ASCII, which most of a FIX message is
    private static final int BLOCK_MARKER = 0xC0DEF1E5;
    private static final ByteBuffer CORRUPTED = ByteBuffer.allocate(0);

    private final AtomicLong outgoingSequenceNumber;
    private final AtomicLong incomingSequenceNumber;
    private final FileMessageStoreSettings settings;
    private final BiPredicate<MessageType, ByteBuffer> messageFilter;
    private final File sessionFile;
    private final int blockSize;
    private final FixSessionId fixSessionId;
    private final String storageIdentity;
    private final SeqNumIndex index = new SeqNumIndex(this::holds);
    private final UnsyncedRange unsynced = new UnsyncedRange();
    private MappedByteBuffer mappedByteBuffer;
    private ByteBuffer alignedByteBuffer;
    private int previousLapFrom;
    private WritesSync writesSync = WritesSync.DISABLED;

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
        if (blockSize < 32 || blockSize % Long.BYTES != 0) {
            throw new IllegalArgumentException("The block size must be at least 32 bytes and a multiple of 8, not " + blockSize);
        }
        this.fixSessionId = fixSessionId;
    }

    /**
     * @return false for no file, or one created but never written, which a crash before its first sync leaves
     * @throws StartStopException for a file of another format, such as one written by 0.9.1 or older, which had no version
     */
    private static boolean isFormatted(File sessionFile) throws StartStopException {
        if (!sessionFile.exists()) {
            return false;
        }
        ByteBuffer header = ByteBuffer.allocate(VERSION_POSITION + Integer.BYTES);
        try (FileChannel fc = FileChannel.open(sessionFile.toPath(), StandardOpenOption.READ)) {
            fc.read(header, 0);
        } catch (IOException e) {
            throw new StartStopException("Unable to read the file messages store " + sessionFile, e);
        }
        long magic = header.getLong(MAGIC_POSITION);
        int version = header.getInt(VERSION_POSITION);
        if (magic == 0 && version == 0) {
            return false;
        }
        if (magic != MAGIC) {
            throw new StartStopException("The file messages store " + sessionFile + " is not of format " + FORMAT_VERSION
                    + ", likely written by staffix 0.9.1 or older: delete it, which resets the session's sequence numbers");
        }
        if (version != FORMAT_VERSION) {
            throw new StartStopException("The file messages store " + sessionFile + " is of format " + version
                    + ", this version reads format " + FORMAT_VERSION);
        }
        return true;
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
        boolean fileExists = isFormatted(sessionFile);
        int fileLen = STORE_HEADER_LEN + (settings.getBlocksCount() * blockSize);
        int alignment = STORE_HEADER_LEN;
        if (mappedByteBuffer == null) {
            // null on a first start, and again after a stop released the previous mapping
            mappedByteBuffer = mapSessionFile(sessionFile, fileLen + alignment);
        }
        alignedByteBuffer = mappedByteBuffer.alignedSlice(alignment);
        int alignedSliceOffset = (alignment - mappedByteBuffer.alignmentOffset(0, alignment)) % alignment;
        writesSync = WritesSync.of(settings.isSyncWrites(), mappedByteBuffer, alignedSliceOffset, STORE_HEADER_LEN);
        // important set the limit to the wanted file size and not the size + alignment
        alignedByteBuffer.position(STORE_HEADER_LEN).limit(fileLen);
        if (fileExists) {
            incomingSequenceNumber.set(alignedByteBuffer.getLong(INCOMING_SEQUENCE_POSITION));
            outgoingSequenceNumber.set(alignedByteBuffer.getLong(OUTGOING_SEQUENCE_POSITION));
            alignedByteBuffer.position(alignedByteBuffer.getInt(LAST_MESSAGE_POSITION));
        } else {
            alignedByteBuffer.putLong(MAGIC_POSITION, MAGIC)
                    .putInt(VERSION_POSITION, FORMAT_VERSION)
                    .putLong(INCOMING_SEQUENCE_POSITION, incomingSequenceNumber.get())
                    .putLong(OUTGOING_SEQUENCE_POSITION, outgoingSequenceNumber.get())
                    .putInt(LAST_MESSAGE_POSITION, STORE_HEADER_LEN);
            syncWrites();
        }
        previousLapFrom = alignedByteBuffer.position();
        indexFile(alignedByteBuffer.position());
        log.info("File messages store for FIX session {} started with incomingSeqNum {} and outgoingSeqNum {}",
                fixSessionId, incomingSequenceNumber.get(), outgoingSequenceNumber.get());
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        MappedByteBuffer mapping = mappedByteBuffer;
        // dropped before the mapping goes, so that a write racing this stop finds no buffer instead of freed memory
        alignedByteBuffer = null;
        mappedByteBuffer = null;
        writesSync = WritesSync.DISABLED;
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
        index.clear();
        unsynced.add(STORE_HEADER_LEN, alignedByteBuffer.limit());
        syncWrites();
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
        writeNextIncomingSeqNum(nextIncomingSeqNum);
        syncWrites();
    }

    @Override
    public void storeNextOutgoingSeqNum(long nextOutgoingSeqNum) {
        writeNextOutgoingSeqNum(nextOutgoingSeqNum);
        syncWrites();
    }

    @Override
    public void storeMessageSent(long outgoingSequenceNumber, ByteBuffer message) {
        alignedByteBuffer.putInt(LAST_MESSAGE_POSITION, storeMessage(outgoingSequenceNumber, message));
        writeNextOutgoingSeqNum(outgoingSequenceNumber + 1);
        syncWrites();
    }

    @Override
    public void storeSentFixMessages(SentFixMessage[] sentFixMessages, int sentFixMessageCount) {
        writeSentFixMessages(sentFixMessages, sentFixMessageCount);
        syncWrites();
    }

    @Override
    public void storeSentFixMessagesAndSeqNums(SentFixMessage[] sentFixMessages, int sentFixMessageCount,
                                               long nextIncomingSeqNum, long nextOutgoingSeqNum) {
        if (sentFixMessageCount > 0) {
            writeSentFixMessages(sentFixMessages, sentFixMessageCount);
        }
        if (nextIncomingSeqNum >= 0) {
            writeNextIncomingSeqNum(nextIncomingSeqNum);
        }
        if (nextOutgoingSeqNum >= 0) {
            writeNextOutgoingSeqNum(nextOutgoingSeqNum);
        }
        syncWrites();
    }

    private void writeNextIncomingSeqNum(long nextIncomingSeqNum) {
        incomingSequenceNumber.set(nextIncomingSeqNum);
        alignedByteBuffer.putLong(INCOMING_SEQUENCE_POSITION, nextIncomingSeqNum);
    }

    private void writeNextOutgoingSeqNum(long nextOutgoingSeqNum) {
        outgoingSequenceNumber.set(nextOutgoingSeqNum);
        alignedByteBuffer.putLong(OUTGOING_SEQUENCE_POSITION, nextOutgoingSeqNum);
    }

    private void writeSentFixMessages(SentFixMessage[] sentFixMessages, int sentFixMessageCount) {
        int nextPosition = 0;
        for (int i = 0; i < sentFixMessageCount; i++) {
            SentFixMessage sfm = sentFixMessages[i];
            nextPosition = storeMessage(sfm.getSequenceNumber(), sfm.getMessage());
        }
        alignedByteBuffer.putInt(LAST_MESSAGE_POSITION, nextPosition);
    }

    private int storeMessage(long outgoingSequenceNumber, ByteBuffer message) {
        int messageSize = message.remaining();
        int requiredSpace = blocksCount(messageSize) * blockSize;
        if (alignedByteBuffer.remaining() < requiredSpace) {
            log.debug("Rolling over for FIX message store {}", settings.getInstanceId());
            if (unsynced.isNotEmpty()) {
                syncWrites();
            }
            alignedByteBuffer.position(STORE_HEADER_LEN);
            previousLapFrom = STORE_HEADER_LEN;
        }
        int position = alignedByteBuffer.position();
        int nextPosition = position + requiredSpace;
        clearStartMarkersOverwritten(position, nextPosition);
        VarHandle.storeStoreFence();
        alignedByteBuffer.position(position + SIZE_OFFSET)
                .putInt(messageSize)
                .putLong(outgoingSequenceNumber)
                .put(message);
        int endMarkerPosition = alignedByteBuffer.position();
        alignedByteBuffer.putInt(BLOCK_MARKER);
        if ((endMarkerPosition - STORE_HEADER_LEN) % blockSize == 0) {
            // on a block start, the end marker reads as a start marker over the header an older message left there
            alignedByteBuffer.putInt(-1).putLong(0);
        }
        VarHandle.storeStoreFence();
        alignedByteBuffer.putInt(position, BLOCK_MARKER)
                .position(nextPosition);
        index.add(outgoingSequenceNumber, position);
        unsynced.add(position, nextPosition);
        return nextPosition;
    }

    private void syncWrites() {
        writesSync.sync(unsynced);
        unsynced.clear();
    }

    /**
     * Indexes the messages in the order they were written: from the next write to the file's end, the lap before, then
     * from its start.
     */
    private void indexFile(int nextWrite) {
        index.clear();
        indexFrom(nextWrite, alignedByteBuffer.limit());
        indexFrom(STORE_HEADER_LEN, nextWrite);
    }

    private void indexFrom(int from, int to) {
        int position = from;
        while (position + RECORD_OVERHEAD <= to) {
            int span = spanAt(alignedByteBuffer, position);
            if (span == 0) {
                position += blockSize;
            } else {
                index.add(alignedByteBuffer.getLong(position + SEQNUM_OFFSET), position);
                position += span;
            }
        }
    }

    private boolean holds(long seqNum, int position) {
        return alignedByteBuffer.getInt(position) == BLOCK_MARKER && alignedByteBuffer.getLong(position + SEQNUM_OFFSET) == seqNum;
    }

    /**
     * A find running meanwhile skips a message without its start marker, or whose marker went while it copied it:
     * cleared on every message about to be overwritten, even in part, including one starting before the write, and
     * set on a new message only once it is whole, which a hard crash benefits from too.
     */
    private void clearStartMarkersOverwritten(int position, int nextPosition) {
        int lapClearedFrom = previousLapFrom;
        while (previousLapFrom < nextPosition) {
            int span = spanAt(alignedByteBuffer, previousLapFrom);
            alignedByteBuffer.putInt(previousLapFrom, 0);
            previousLapFrom += span == 0 ? blockSize : span;
        }
        if (previousLapFrom != lapClearedFrom) {
            index.dropOverwritten();
        }
        for (int block = position; block < nextPosition; block += blockSize) {
            alignedByteBuffer.putInt(block, 0);
        }
    }

    /**
     * @return the bytes taken by the message starting at {@code position}, 0 for no whole message header there
     */
    private int spanAt(ByteBuffer file, int position) {
        if (file.limit() - position < RECORD_OVERHEAD || file.getInt(position) != BLOCK_MARKER) {
            return 0;
        }
        VarHandle.loadLoadFence();
        int messageSize = file.getInt(position + SIZE_OFFSET);
        if (messageSize < 0 || messageSize > file.limit() - position - RECORD_OVERHEAD) {
            return 0;
        }
        return blocksCount(messageSize) * blockSize;
    }

    /**
     * Safe while the session keeps writing, as resends and replication snapshots read on threads of their own: a
     * block written meanwhile is skipped, and gap filled, rather than handed over torn. Reads through the index, each
     * message as the consumer takes it.
     */
    @Override
    public void find(long startSequenceNumber, long stopSequenceNumber, StoreMessagesConsumer consumer) {
        int lastPosition = alignedByteBuffer.getInt(LAST_MESSAGE_POSITION); // important do not call
        if (lastPosition == STORE_HEADER_LEN) {
            // no messages written store is empty
            return;
        }
        ByteBuffer file = alignedByteBuffer.duplicate();
        index.find(startSequenceNumber, stopSequenceNumber, (seqNum, position) -> {
            ByteBuffer message = readMessage(file, position, seqNum);
            return message == null || message == CORRUPTED || consumer.onMessage(seqNum, message);
        });
    }

    /**
     * @return the message of that seqnum starting there, null when there is none or it was overwritten while read,
     * {@link #CORRUPTED} when it lacks its end marker
     */
    private @Nullable ByteBuffer readMessage(ByteBuffer file, int position, long seqNum) {
        int span = spanAt(file, position);
        if (span == 0 || file.getLong(position + SEQNUM_OFFSET) != seqNum) {
            return null;
        }
        int messageSize = file.getInt(position + SIZE_OFFSET);
        if (messageSize < 0 || blocksCount(messageSize) * blockSize != span) {
            return null;
        }
        byte[] msgBytes = new byte[messageSize];
        file.position(position + PAYLOAD_OFFSET);
        file.get(msgBytes);
        int blockEndMarker = file.getInt(position + PAYLOAD_OFFSET + messageSize);
        VarHandle.loadLoadFence();
        if (file.getInt(position) != BLOCK_MARKER || file.getLong(position + SEQNUM_OFFSET) != seqNum
                || file.getInt(position + SIZE_OFFSET) != messageSize) {
            return null;
        }
        if (blockEndMarker != BLOCK_MARKER) {
            log.error("Read a corrupted block for seqnum {} skipping message {}", seqNum, new String(msgBytes, SerDe.CHARSET));
            return CORRUPTED;
        }
        return ByteBuffer.wrap(msgBytes);
    }

    private int blocksCount(int messageSize) {
        return (messageSize + RECORD_OVERHEAD + blockSize - 1) / blockSize;
    }

    private MappedByteBuffer mapSessionFile(File sessionFile, int size) {
        try (FileChannel fc = FileChannel.open(sessionFile.toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
            return fc.map(FileChannel.MapMode.READ_WRITE, 0, size);
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }
}
