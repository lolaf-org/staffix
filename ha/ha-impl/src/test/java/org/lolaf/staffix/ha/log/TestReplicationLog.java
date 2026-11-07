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
package org.lolaf.staffix.ha.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.lolaf.ringos.idling.RetryStrategy;
import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.ha.wire.AllocatedBytes;
import org.lolaf.staffix.ha.wire.FrameDecoder;
import org.lolaf.staffix.ha.wire.FrameEncoder;
import org.lolaf.staffix.ha.wire.RecordingHandler;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReplicationLog {

    private static final int CAPACITY = 1 << 16;
    private static final String MESSAGE = "8=FIX.4.4\u00019=5\u000135=D\u000110=000\u0001";

    private final ReplicationLog log = new ReplicationLog(CAPACITY);
    private final StoreEventRecord record = new StoreEventRecord();
    private final LogFullWait wait = new LogFullWait(Duration.ofSeconds(1));
    private final ByteBuffer frame = ByteBuffer.allocateDirect(CAPACITY);
    private final RecordingHandler handler = new RecordingHandler();

    private static ByteBuffer message(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Reads the next event the way a sender does: the body after room for the header, then the header with the
     * event's offset, then decodes the whole frame.
     */
    private int readAsFrame(ReplicationLog.Reader reader) {
        int body = reader.readInto(frame, WireFormat.STORE_EVENT_BODY_OFFSET);
        if (body < 0) {
            return body;
        }
        int length = FrameEncoder.storeEventHeader(frame, 0, 7, reader.getEventOffset(), body);
        assertThat(new FrameDecoder(CAPACITY).decode(frame, 0, length, handler)).isEqualTo(length);
        return length;
    }

    @Test
    void eventsAreReadInTheOrderAppendedAsWholeWireFrames() {
        ReplicationLog.Reader reader = log.newReader(() -> {
        });
        long first = log.append(record.set(3, StoreEventType.STORE_MESSAGE, 42, message(MESSAGE)), wait);
        long second = log.append(record.set(3, StoreEventType.STORE_INCOMING_SEQ_NUM, 19, null), wait);

        readAsFrame(reader);
        readAsFrame(reader);

        assertThat(handler.frames).containsExactly(
                "STORE_EVENT epoch=7 offset=" + first + " key=3 STORE_MESSAGE seq=42 message=" + MESSAGE,
                "STORE_EVENT epoch=7 offset=" + second + " key=3 STORE_INCOMING_SEQ_NUM seq=19 message=");
        assertThat(reader.readInto(frame, 0)).as("nothing more").isNegative();
        assertThat(reader.getOffset()).isEqualTo(log.getTailOffset());
    }

    @Test
    void offsetsGrowWithEveryEventFromZero() {
        long first = log.append(record.set(1, StoreEventType.RESET_SEQ_NUM, 0, null), wait);
        long second = log.append(record.set(1, StoreEventType.STORE_OUTGOING_SEQ_NUM, 2, null), wait);

        assertThat(first).isZero();
        assertThat(second).isPositive();
        assertThat(log.getTailOffset()).isGreaterThan(second);
    }

    @Test
    void aMessageIsAppendedWithoutMovingItsBuffer() {
        ByteBuffer message = message(MESSAGE);
        message.position(2);

        log.append(record.set(1, StoreEventType.STORE_MESSAGE, 1, message), wait);

        assertThat(message.position()).isEqualTo(2);
        assertThat(message.limit()).isEqualTo(MESSAGE.length());
    }

    @Test
    void aReaderResumesFromAnEventStillInTheLog() {
        log.append(record.set(1, StoreEventType.STORE_MESSAGE, 1, message("first")), wait);
        long second = log.append(record.set(1, StoreEventType.STORE_MESSAGE, 2, message("second")), wait);
        ReplicationLog.Reader reader = log.newReader(() -> {
        });

        assertThat(reader.seek(second)).isTrue();

        readAsFrame(reader);
        assertThat(handler.frames).singleElement().asString().contains("seq=2 message=second");
    }

    @Test
    void aReaderCannotResumeFromAnEventOverwrittenSoTheFollowerNeedsASnapshot() {
        ReplicationLog small = new ReplicationLog(1024);
        long first = small.append(record.set(1, StoreEventType.STORE_MESSAGE, 1, message(MESSAGE)), wait);
        for (int i = 2; i < 100; i++) {
            small.append(record.set(1, StoreEventType.STORE_MESSAGE, i, message(MESSAGE)), wait);
        }
        ReplicationLog.Reader reader = small.newReader(() -> {
        });

        assertThat(reader.seek(first)).isFalse();
        assertThat(reader.getOffset()).as("left at the tail").isEqualTo(small.getTailOffset());
    }

    @Test
    void aMessageTooLongForTheLogIsRefusedAndNothingIsAppended() {
        ByteBuffer tooLong = ByteBuffer.allocate(log.getMaxMessageLength() + 1);

        assertThatThrownBy(() -> log.append(record.set(1, StoreEventType.STORE_MESSAGE, 1, tooLong), wait))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the " + log.getMaxMessageLength());
        assertThat(log.getTailOffset()).isZero();
        assertThat(log.append(record.set(1, StoreEventType.STORE_MESSAGE, 1, ByteBuffer.allocate(log.getMaxMessageLength())), wait))
                .as("the longest message is accepted, in fragments").isPositive();
    }

    @Test
    void aMessageLongerThanAFrameIsAppendedAsConsecutiveFragmentsTheLastOneEnding() {
        int fragment = log.getMaxFragmentLength();
        String x = "x".repeat(fragment);
        String y = "y".repeat(fragment);
        ByteBuffer message = ByteBuffer.wrap(("ab" + x + y + "zzz").getBytes(StandardCharsets.US_ASCII)).position(2);
        ReplicationLog.Reader reader = log.newReader(() -> {
        });

        long last = log.append(record.set(3, StoreEventType.STORE_MESSAGE, 42, message), wait);

        readAsFrame(reader);
        long second = reader.getOffset();
        readAsFrame(reader);
        readAsFrame(reader);
        assertThat(handler.frames).containsExactly(
                "STORE_EVENT epoch=7 offset=0 key=3 STORE_MESSAGE seq=42 message=" + x + " more",
                "STORE_EVENT epoch=7 offset=" + second + " key=3 STORE_MESSAGE seq=42 message=" + y + " continued more",
                "STORE_EVENT epoch=7 offset=" + last + " key=3 STORE_MESSAGE seq=42 message=zzz continued");
        assertThat(message.position()).as("left untouched").isEqualTo(2);
        assertThat(reader.readInto(frame, 0)).as("nothing more").isNegative();
    }

    @Test
    void aMessageThatFitsAFrameIsOneEventNotMarkedAsAFragment() {
        ReplicationLog.Reader reader = log.newReader(() -> {
        });

        log.append(record.set(3, StoreEventType.STORE_MESSAGE, 42, ByteBuffer.wrap("x".repeat(log.getMaxFragmentLength())
                .getBytes(StandardCharsets.US_ASCII))), wait);

        readAsFrame(reader);
        assertThat(handler.frames).singleElement().asString().doesNotContain(" more", " continued");
        assertThat(reader.readInto(frame, 0)).as("nothing more").isNegative();
    }

    /**
     * Fewer bytes than events rather than zero: a one-off may land in the window while the JIT settles, whereas an
     * object per event would cost at least 16 bytes each.
     */
    @Test
    void appendingAllocatesNothingPerEvent() {
        ByteBuffer message = message(MESSAGE);
        for (int i = 0; i < 20_000; i++) {
            log.append(record.set(1, StoreEventType.STORE_MESSAGE, i, message), wait);
        }

        long before = AllocatedBytes.ofCurrentThread();
        for (int i = 0; i < 200_000; i++) {
            log.append(record.set(1, StoreEventType.STORE_MESSAGE, i, message), wait);
        }

        assertThat(AllocatedBytes.ofCurrentThread() - before).isLessThan(200_000);
    }

    /**
     * Sessions appending from their own threads while a follower reads: each session's events arrive whole and in
     * order, nothing lost.
     */
    @Test
    @Timeout(60)
    void sessionsAppendingConcurrentlyReachTheReaderInEachSessionsOrder() throws InterruptedException {
        int sessions = 4;
        int eventsPerSession = 20_000;
        ReplicationLog big = new ReplicationLog(1 << 24);
        ReplicationLog.Reader reader = big.newReader(() -> {
        });
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> writers = new ArrayList<>();
        for (int s = 0; s < sessions; s++) {
            int sessionKey = s;
            writers.add(new Thread(() -> {
                StoreEventRecord own = new StoreEventRecord();
                LogFullWait ownWait = new LogFullWait(Duration.ofSeconds(1));
                awaitQuietly(start);
                for (int seq = 0; seq < eventsPerSession; seq++) {
                    big.append(own.set(sessionKey, StoreEventType.STORE_MESSAGE, seq, message(sessionKey + ":" + seq)),
                            ownWait);
                }
            }));
        }
        writers.forEach(Thread::start);
        start.countDown();

        long[] next = new long[sessions];
        AtomicReference<String> failure = new AtomicReference<>();
        int read = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(50);
        while (read < sessions * eventsPerSession && System.nanoTime() < deadline) {
            int body = reader.readInto(frame, 0);
            if (body < 0) {
                Thread.onSpinWait();
                continue;
            }
            int sessionKey = frame.getInt(0);
            long seq = frame.getLong(8);
            byte[] text = new byte[frame.getInt(16)];
            for (int i = 0; i < text.length; i++) {
                text[i] = frame.get(20 + i);
            }
            if (seq != next[sessionKey] || !new String(text, StandardCharsets.US_ASCII).equals(sessionKey + ":" + seq)) {
                failure.compareAndSet(null, "session " + sessionKey + " seq " + seq + " after " + (next[sessionKey] - 1));
            }
            next[sessionKey] = seq + 1;
            read++;
        }
        for (Thread writer : writers) {
            writer.join();
        }

        assertThat(failure.get()).isNull();
        assertThat(read).isEqualTo(sessions * eventsPerSession);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void anAppendThatWaitedTooLongDropsOnlyTheSlowestGatingReaderAndTellsItsOwner() {
        ReplicationLog small = new ReplicationLog(1024);
        LogFullWait briefWait = new LogFullWait(Duration.ofMillis(10));
        List<String> dropped = new ArrayList<>();
        ReplicationLog.Reader keepingUp = small.newReader(() -> dropped.add("keeping up"));
        ReplicationLog.Reader stuck = small.newReader(() -> dropped.add("stuck"));

        for (int i = 0; i < 100; i++) {
            small.append(record.set(1, StoreEventType.STORE_MESSAGE, i, message(MESSAGE)), briefWait);
            assertThat(keepingUp.readInto(frame, 0)).isPositive();
        }

        assertThat(dropped).containsExactly("stuck");
        assertThat(stuck.readInto(frame, 0)).as("overwritten").isNegative();
    }

    @Test
    void aGatingReaderReadsAgainTheEventItUnread() {
        ReplicationLog.Reader reader = log.newReader(() -> {
        });
        long first = log.append(record.set(1, StoreEventType.STORE_INCOMING_SEQ_NUM, 7, null), wait);
        assertThat(reader.readInto(frame, 0)).isPositive();

        reader.unread();

        assertThat(reader.getOffset()).isEqualTo(first);
        assertThat(reader.readInto(frame, 0)).isPositive();
        assertThat(reader.getEventOffset()).isEqualTo(first);
    }

    @Test
    void aClosedGatingReaderIsNeverDropped() {
        ReplicationLog small = new ReplicationLog(1024);
        ReplicationLog.Reader reader = small.newReader(() -> {
            throw new AssertionError("dropped once closed");
        });

        reader.close();

        for (int i = 0; i < 100; i++) {
            small.append(record.set(1, StoreEventType.STORE_MESSAGE, i, message(MESSAGE)), wait);
        }
    }

    @Test
    void writersThatGiveUpTogetherDropTheSlowestReaderOnlyNotOneEach() throws Exception {
        ReplicationLog small = new ReplicationLog(1024);
        List<String> dropped = new CopyOnWriteArrayList<>();
        small.newReader(() -> dropped.add("stuck"));
        ReplicationLog.Reader keepingUp = small.newReader(() -> dropped.add("keeping up"));
        StoreEventRecord filler = new StoreEventRecord();
        long first = small.append(filler.set(1, StoreEventType.STORE_INCOMING_SEQ_NUM, 0, null), wait);
        long frameBytes = small.getTailOffset() - first;
        while (small.getTailOffset() + frameBytes <= 1024) {
            small.append(filler.set(1, StoreEventType.STORE_INCOMING_SEQ_NUM, 1, null), wait);
        }
        while (keepingUp.readInto(frame, 0) >= 0) {
            assertThat(keepingUp.getOffset()).isLessThanOrEqualTo(small.getTailOffset());
        }
        CyclicBarrier bothGated = new CyclicBarrier(2);
        List<Long> written = new CopyOnWriteArrayList<>();
        List<Thread> writers = new ArrayList<>();
        for (int w = 0; w < 2; w++) {
            int writer = w;
            writers.add(new Thread(() -> {
                RetryStrategy giveUpTogether = new RetryStrategy() {
                    @Override
                    public void reset() {
                    }

                    @Override
                    public boolean awaitRetry() {
                        try {
                            bothGated.await(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                        return false;
                    }
                };
                written.add(small.append(new StoreEventRecord().set(2 + writer, StoreEventType.STORE_INCOMING_SEQ_NUM,
                        1, null), giveUpTogether));
            }));
        }
        writers.forEach(Thread::start);
        for (Thread writer : writers) {
            writer.join(10_000);
        }

        assertThat(written).hasSize(2);
        assertThat(dropped).containsExactly("stuck");
    }
}
