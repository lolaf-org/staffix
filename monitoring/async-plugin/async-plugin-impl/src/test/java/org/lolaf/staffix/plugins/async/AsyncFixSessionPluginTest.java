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
package org.lolaf.staffix.plugins.async;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.BusySpinIdleStrategy;
import org.lolaf.ringos.rb.RingBuffer;
import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixFieldsEncoder;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.RttMeasurement;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.api.time.UTCTime;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

class AsyncFixSessionPluginTest {

    private static final MessageType NEW_ORDER = MessageType.of("D", false);

    private AsyncPluginConsumerPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.stop(Deadline.of(Duration.ofSeconds(5)));
        }
    }

    private AsyncFixSessionPlugin<PluginContext> wrap(FixSessionPlugin<PluginContext, Object> delegate, int queueSize) {
        pool = new AsyncPluginConsumerPool(1, queueSize, "test", BusySpinIdleStrategy::getInstance);
        RingBuffer<AsyncPluginEvent> queue = pool.register(delegate);
        pool.start();
        return new AsyncFixSessionPlugin<>(delegate, queue, BackpressurePolicy.DROP, BusySpinIdleStrategy.getInstance());
    }

    @Test
    void replaysValueOnlyCallbacksInOrderOnTheConsumerThread() {
        RecordingPlugin delegate = new RecordingPlugin();
        AsyncFixSessionPlugin<PluginContext> async = wrap(delegate, 1024);

        Thread producer = Thread.currentThread();
        async.onLogon();
        async.onMessageEncodingStarted(NEW_ORDER, 111L, null);
        async.onMessageSent(NEW_ORDER, 42, 222L, UTCTime.of(Duration.ofSeconds(1700).toNanos()));
        async.onLogout();

        await().atMost(Duration.ofSeconds(5)).until(() -> delegate.calls.size() >= 4);

        List<Call> calls = List.copyOf(delegate.calls);
        assertThat(calls).extracting(c -> c.kind)
                .containsExactly("onLogon", "onMessageEncodingStarted", "onMessageSent", "onLogout");
        // Callbacks run on the pool consumer thread, never on the producing thread.
        assertThat(delegate.callbackThreads).allSatisfy(t -> assertThat(t).isNotEqualTo(producer));
    }

    @Test
    void copiesTimestampByValueSoLaterMutationOfAReusedUtcTimeIsNotObserved() {
        RecordingPlugin delegate = new RecordingPlugin();
        AsyncFixSessionPlugin<PluginContext> async = wrap(delegate, 1024);

        UTCTime.TimeImpl reused = new UTCTime.TimeImpl();
        reused.from(1700L, 123);
        async.onMessageReceived(NEW_ORDER, 7, 999L, reused);
        // Simulate the engine recycling the mutable UTCTime instance right after the call returns.
        reused.from(9999L, 456);

        await().atMost(Duration.ofSeconds(5)).until(() -> !delegate.calls.isEmpty());
        Call call = delegate.calls.peek();
        assertThat(call.kind).isEqualTo("onMessageReceived");
        assertThat(call.messageCode).isEqualTo("D");
        assertThat(call.payloadSize).isEqualTo(7);
        assertThat(call.timeNanos).isEqualTo(999L);
        assertThat(call.epochSeconds).isEqualTo(1700L);
        assertThat(call.nanosOfSecond).isEqualTo(123);
    }

    @Test
    void snapshotsEncodedBodyBytesAndDropsThePooledEncoder() {
        RecordingPlugin delegate = new RecordingPlugin();
        AsyncFixSessionPlugin<PluginContext> async = wrap(delegate, 1024);

        ByteBuffer body = ByteBuffer.allocate(4);
        body.put(new byte[]{1, 2, 3, 4});
        body.flip();
        FixFieldsEncoder<?> encoder = mock(FixFieldsEncoder.class);

        Object token = new Object();
        async.onMessageEncodedBody(NEW_ORDER, body, encoder, 0L, token);
        // Mutate the live buffer after the call: the async copy must be unaffected.
        body.put(0, (byte) 99);

        await().atMost(Duration.ofSeconds(5)).until(() -> !delegate.calls.isEmpty());
        Call call = delegate.calls.peek();
        assertThat(call.kind).isEqualTo("onMessageEncodedBody");
        assertThat(call.bodyBytes).containsExactly(1, 2, 3, 4);
        assertThat(call.encoderWasNull).isTrue();
        // The per-message encoding token is threaded through to the replayed callback by reference.
        assertThat(call.encodingToken).isSameAs(token);
    }

    @Test
    void threadsTheEncodingTokenThroughToTheReplayedStartedCallback() {
        RecordingPlugin delegate = new RecordingPlugin();
        AsyncFixSessionPlugin<PluginContext> async = wrap(delegate, 1024);

        Object token = new Object();
        async.onMessageEncodingStarted(NEW_ORDER, 222L, token);

        await().atMost(Duration.ofSeconds(5)).until(() -> !delegate.calls.isEmpty());
        Call call = delegate.calls.peek();
        assertThat(call.kind).isEqualTo("onMessageEncodingStarted");
        assertThat(call.encodingToken).isSameAs(token);
    }

    @Test
    void threadsTheEncodingTokenThroughToTheReplayedFinishedCallback() {
        RecordingPlugin delegate = new RecordingPlugin();
        AsyncFixSessionPlugin<PluginContext> async = wrap(delegate, 1024);

        Object token = new Object();
        async.onMessageEncodingFinished(NEW_ORDER, 555L, token);

        await().atMost(Duration.ofSeconds(5)).until(() -> !delegate.calls.isEmpty());
        Call call = delegate.calls.peek();
        assertThat(call.kind).isEqualTo("onMessageEncodingFinished");
        assertThat(call.encodingToken).isSameAs(token);
    }

    @Test
    void countsDroppedEventsWhenTheQueueIsFullUnderTheDropPolicy() {
        RecordingPlugin delegate = new RecordingPlugin();
        // Build a wrapper whose consumer is NOT started, so nothing is ever drained.
        AsyncPluginConsumerPool idlePool = new AsyncPluginConsumerPool(1, 8, "drop", BusySpinIdleStrategy::getInstance);
        RingBuffer<AsyncPluginEvent> queue = idlePool.register(delegate);
        AsyncFixSessionPlugin<PluginContext> async =
                new AsyncFixSessionPlugin<>(delegate, queue, BackpressurePolicy.DROP, BusySpinIdleStrategy.getInstance());

        for (int i = 0; i < 1000; i++) {
            async.onLogon();
        }

        assertThat(async.getDroppedEventsCount()).isGreaterThan(0);
        assertThat(delegate.calls).isEmpty();
    }

    @Test
    void flushesBufferedCallbacksAndSessionDestroyedOnStop() {
        RecordingPlugin delegate = new RecordingPlugin();
        AsyncFixSessionPlugin<PluginContext> async = wrap(delegate, 1024);
        FixSessionId sessionId = mock(FixSessionId.class);

        for (int i = 0; i < 50; i++) {
            async.onLogon();
        }
        async.onSessionDestroyed("engine-1", sessionId);

        pool.stop(Deadline.of(Duration.ofSeconds(5)));
        pool = null; // already stopped

        assertThat(delegate.calls).hasSize(51);
        Call last = delegate.lastCall.get();
        assertThat(last.kind).isEqualTo("onSessionDestroyed");
        assertThat(last.instanceId).isEqualTo("engine-1");
        assertThat(last.sessionId).isSameAs(sessionId);
    }

    @Test
    void delegatesValueReturningAndSetupCallbacksSynchronously() {
        RecordingPlugin delegate = new RecordingPlugin();
        AsyncFixSessionPlugin<PluginContext> async = wrap(delegate, 1024);
        FixMessageDecoder decoder = mock(FixMessageDecoder.class);
        FixFieldsDecoderMapper mapper = mock(FixFieldsDecoderMapper.class);

        assertThat(async.requiresTimeMeasurement()).isTrue();
        assertThat(async.getPluginContext()).isEmpty();
        async.onDecoderSetup(decoder, mapper);

        // onDecoderSetup runs inline on the calling thread, so it is observed immediately without draining.
        assertThat(delegate.decoderSetupThread.get()).isEqualTo(Thread.currentThread());
    }

    @Test
    void neverEnqueuesCallbacksTheDelegateLeavesAsDefaults() {
        DefaultsPlugin delegate = new DefaultsPlugin();
        // Small queue, consumer never started: if any of these were offered, they would overflow and drop.
        AsyncPluginConsumerPool idlePool = new AsyncPluginConsumerPool(1, 8, "defaults", BusySpinIdleStrategy::getInstance);
        RingBuffer<AsyncPluginEvent> queue = idlePool.register(delegate);
        AsyncFixSessionPlugin<PluginContext> async =
                new AsyncFixSessionPlugin<>(delegate, queue, BackpressurePolicy.DROP, BusySpinIdleStrategy.getInstance());

        for (int i = 0; i < 1000; i++) {
            async.onLogon();
            async.onMessageReceived(NEW_ORDER, 10, i, UTCTime.of(0));
            async.onMessageDecodingStarted(NEW_ORDER, i, UTCTime.of(0));
            async.onRttMeasurement(new RttMeasurement(Duration.ZERO, Duration.ZERO, UTCTime.of(0)));
        }

        // Nothing was offered, so nothing dropped despite the tiny un-drained queue, and it stays empty.
        assertThat(async.getDroppedEventsCount()).isZero();
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void enqueuesOnlyTheCallbacksTheDelegateOverrides() {
        ReceivedOnlyPlugin delegate = new ReceivedOnlyPlugin();
        AsyncFixSessionPlugin<PluginContext> async = wrap(delegate, 1024);

        async.onMessageDecodingStarted(NEW_ORDER, 1L, UTCTime.of(0)); // default -> skipped
        async.onMessageReceived(NEW_ORDER, 5, 2L, UTCTime.of(0));     // overridden -> forwarded

        await().atMost(Duration.ofSeconds(5)).until(() -> delegate.received.get() > 0);
        assertThat(delegate.received.get()).isEqualTo(1);
        assertThat(delegate.decodingStarted.get()).isZero();
    }

    // --- Test fixtures -------------------------------------------------------------------------------

    private static class DefaultsPlugin implements FixSessionPlugin<PluginContext, Object> {
        @Override
        public boolean isForPluginContext(Class<? extends PluginContext> pluginClass) {
            return false;
        }

        @Override
        public Optional<PluginContext> getPluginContext() {
            return Optional.empty();
        }

        @Override
        public void onSessionDestroyed(String fixInstanceId, FixSessionId fixSessionId) {
            // no-op
        }
    }

    private static final class ReceivedOnlyPlugin extends DefaultsPlugin {
        final AtomicInteger received = new AtomicInteger();
        final AtomicInteger decodingStarted = new AtomicInteger();

        @Override
        public void onMessageReceived(MessageType messageType, int payloadSize, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
            received.incrementAndGet();
        }
    }

    private static final class Call {
        String kind;
        String messageCode;
        int payloadSize;
        long timeNanos;
        long epochSeconds;
        int nanosOfSecond;
        int[] bodyBytes;
        boolean encoderWasNull;
        Object encodingToken;
        String instanceId;
        FixSessionId sessionId;
    }

    private static final class RecordingPlugin implements FixSessionPlugin<PluginContext, Object> {

        final ConcurrentLinkedQueue<Call> calls = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Thread> callbackThreads = new ConcurrentLinkedQueue<>();
        final AtomicReference<Call> lastCall = new AtomicReference<>();
        final AtomicReference<Thread> decoderSetupThread = new AtomicReference<>();

        private void record(Call call) {
            callbackThreads.add(Thread.currentThread());
            calls.add(call);
            lastCall.set(call);
        }

        @Override
        public boolean isForPluginContext(Class<? extends PluginContext> pluginClass) {
            return false;
        }

        @Override
        public Optional<PluginContext> getPluginContext() {
            return Optional.empty();
        }

        @Override
        public boolean requiresTimeMeasurement() {
            return true;
        }

        @Override
        public void onDecoderSetup(FixMessageDecoder decoder, FixFieldsDecoderMapper fieldsDecoderMapper) {
            decoderSetupThread.set(Thread.currentThread());
        }

        @Override
        public void onLogon() {
            Call c = new Call();
            c.kind = "onLogon";
            record(c);
        }

        @Override
        public void onLogout() {
            Call c = new Call();
            c.kind = "onLogout";
            record(c);
        }

        @Override
        public Object getMessageEncodingToken(MessageType messageType, long encodingStartTimeInNanos) {
            // Returns null: token round-trip is driven directly through the I/O-thread callbacks in the tests,
            // simulating the token the engine hands back. Overridden so the wrapper detects it as forwarded.
            return null;
        }

        @Override
        public void onMessageEncodingStarted(MessageType messageType, long encodingStartTimeInNanos, Object encodingToken) {
            Call c = new Call();
            c.kind = "onMessageEncodingStarted";
            c.messageCode = messageType.code();
            c.timeNanos = encodingStartTimeInNanos;
            c.encodingToken = encodingToken;
            record(c);
        }

        @Override
        public void onMessageReceived(MessageType messageType, int payloadSize, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
            Call c = new Call();
            c.kind = "onMessageReceived";
            c.messageCode = messageType.code();
            c.payloadSize = payloadSize;
            c.timeNanos = localReceiveTimeInNanos;
            c.epochSeconds = localReceiveTime.getEpochSeconds();
            c.nanosOfSecond = localReceiveTime.getNanosOfSecond();
            record(c);
        }

        @Override
        public void onMessageSent(MessageType messageType, int payloadSize, long localSendingTimeInNanos, UTCTime localSendingTime) {
            Call c = new Call();
            c.kind = "onMessageSent";
            c.messageCode = messageType.code();
            c.payloadSize = payloadSize;
            c.timeNanos = localSendingTimeInNanos;
            c.epochSeconds = localSendingTime.getEpochSeconds();
            c.nanosOfSecond = localSendingTime.getNanosOfSecond();
            record(c);
        }

        @Override
        public void onMessageEncodedBody(MessageType messageType, ByteBuffer encodedBody, FixFieldsEncoder<?> fixFieldsEncoder, long encodingStartTimeInNanos, Object encodingToken) {
            Call c = new Call();
            c.kind = "onMessageEncodedBody";
            c.messageCode = messageType.code();
            c.encoderWasNull = fixFieldsEncoder == null;
            c.encodingToken = encodingToken;
            int[] bytes = new int[encodedBody.remaining()];
            int pos = encodedBody.position();
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = encodedBody.get(pos + i) & 0xFF;
            }
            c.bodyBytes = bytes;
            record(c);
        }

        @Override
        public void onMessageEncodingFinished(MessageType messageType, long encodingEndTimeInNanos, Object encodingToken) {
            Call c = new Call();
            c.kind = "onMessageEncodingFinished";
            c.messageCode = messageType.code();
            c.timeNanos = encodingEndTimeInNanos;
            c.encodingToken = encodingToken;
            record(c);
        }

        @Override
        public void onSessionDestroyed(String fixInstanceId, FixSessionId fixSessionId) {
            Call c = new Call();
            c.kind = "onSessionDestroyed";
            c.instanceId = fixInstanceId;
            c.sessionId = fixSessionId;
            record(c);
        }
    }
}
