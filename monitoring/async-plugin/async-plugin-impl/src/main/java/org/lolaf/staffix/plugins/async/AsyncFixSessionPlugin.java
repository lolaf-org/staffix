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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.ringos.rb.RingBuffer;
import org.lolaf.ringos.rb.RingBuffer.EventTranslatorOneArg;
import org.lolaf.ringos.rb.RingBuffer.EventTranslatorThreeLongArg;
import org.lolaf.ringos.rb.RingBuffer.EventTranslatorTwoArg;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A {@link FixSessionPlugin} wrapper that defers a wrapped plugin's fire-and-forget callbacks onto a
 * shared consumer thread, off the latency-critical message-processing path.
 *
 * <p>Void, side-effect-only callbacks are published to a pre-filled ring buffer (no allocation on the
 * producing thread) and replayed against the delegate on the consumer thread. Callbacks that must run
 * synchronously are delegated inline:
 * <ul>
 *   <li>{@link #isForPluginContext}, {@link #getPluginContext}, {@link #requiresTimeMeasurement} — they
 *       return a value;</li>
 *   <li>{@link #onDecoderSetup} — it registers field listeners on live setup-time objects and must run
 *       before any message flows.</li>
 * </ul>
 *
 * <p>Because callbacks are replayed after the fact, this wrapper is only sound for plugins whose
 * callbacks are side-effect-only and order-independent relative to the engine (metrics, logging, tracing).
 * It must not wrap a plugin that has to run before the message is acted on, or that mutates engine state.
 *
 * @param <C> the wrapped plugin's context type
 */
@Slf4j
final class AsyncFixSessionPlugin<C> implements FixSessionPlugin<C, Object> {

    private static final long DROP_WARN_THROTTLE_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final EventTranslatorOneArg<AsyncPluginEvent, AsyncPluginEvent.Type> WRITE_TYPE =
            AsyncPluginEvent::writeType;
    private static final EventTranslatorThreeLongArg<AsyncPluginEvent, MessageType, UTCTime> WRITE_DECODING_STARTED =
            AsyncPluginEvent::writeDecodingStarted;
    private static final EventTranslatorThreeLongArg<AsyncPluginEvent, MessageType, UTCTime> WRITE_DECODING_FINISHED =
            AsyncPluginEvent::writeDecodingFinished;
    private static final EventTranslatorThreeLongArg<AsyncPluginEvent, MessageType, Object> WRITE_ENCODING_STARTED =
            AsyncPluginEvent::writeEncodingStarted;
    private static final EventTranslatorThreeLongArg<AsyncPluginEvent, MessageType, Object> WRITE_ENCODING_FINISHED =
            AsyncPluginEvent::writeEncodingFinished;
    private static final EventTranslatorOneArg<AsyncPluginEvent, EncodedBodyScratch> COPY_ENCODED_BODY =
            AsyncPluginEvent::copyEncodedBody;
    private static final EventTranslatorOneArg<AsyncPluginEvent, RttMeasurement> WRITE_RTT =
            AsyncPluginEvent::writeRtt;
    private static final EventTranslatorTwoArg<AsyncPluginEvent, String, FixSessionId> WRITE_SESSION_DESTROYED =
            AsyncPluginEvent::writeSessionDestroyed;
    private static final EventTranslatorOneArg<AsyncPluginEvent, MessageSizeScratch> COPY_MESSAGE_SIZE_WITH_TIME =
            AsyncPluginEvent::copyMessageSizeWithTime;
    // Per-delegate-class cache of which default callbacks the delegate actually overrides.
    private static final ClassValue<Forwarded> FORWARDED_CV = new ClassValue<>() {
        @Override
        protected Forwarded computeValue(Class<?> delegateClass) {
            return new Forwarded(delegateClass);
        }
    };

    // The delegate's own token type is carried opaquely as Object: this wrapper stashes it in the ring-buffer
    // slot by reference and replays it, never inspecting it.
    private final FixSessionPlugin<C, Object> delegate;
    private final RingBuffer<AsyncPluginEvent> queue;
    private final BackpressurePolicy backpressurePolicy;
    private final IdleStrategy producerIdleStrategy;
    private final Forwarded forwarded;
    // Reusable staging slot for the RECEIVED / SENT paths. Both callbacks run on the session's single
    // I/O thread (one plugin instance per session) and each writes then publishes it within one call, so
    // a plain reusable field is sufficient — no thread-local needed.
    private final MessageSizeScratch scratch;
    // Reusable staging slot for the ENCODED_BODY path. onMessageEncodedBody runs on the session's single I/O
    // thread and stages-then-publishes within one call, so a plain reusable field is sufficient. It carries the
    // encoding token (by reference) alongside the live body buffer that the translator snapshots during offer.
    private final EncodedBodyScratch encodedBodyScratch;
    // Best-effort drop counter. It is incremented from the ring-buffer producer side, which includes the
    // concurrently-invoked onMessageEncodingStarted callback (fired on the producing application thread),
    // with a plain (racy) ++ that may lose updates under contention — accurate drop counting is not required.
    private long droppedEvents;
    private long lastDropWarnNanos;

    AsyncFixSessionPlugin(FixSessionPlugin<C, Object> delegate, RingBuffer<AsyncPluginEvent> queue,
                          BackpressurePolicy backpressurePolicy, IdleStrategy producerIdleStrategy) {
        this.delegate = delegate;
        this.queue = queue;
        this.backpressurePolicy = backpressurePolicy;
        this.producerIdleStrategy = producerIdleStrategy;
        this.forwarded = FORWARDED_CV.get(delegate.getClass());
        this.scratch = new MessageSizeScratch();
        this.encodedBodyScratch = new EncodedBodyScratch();
    }

    long getDroppedEventsCount() {
        return droppedEvents;
    }

    @Override
    public boolean isForPluginContext(Class<? extends PluginContext> pluginClass) {
        return delegate.isForPluginContext(pluginClass);
    }

    @Override
    public Optional<C> getPluginContext() {
        return delegate.getPluginContext();
    }

    @Override
    public boolean requiresTimeMeasurement() {
        return delegate.requiresTimeMeasurement();
    }

    @Override
    public void onDecoderSetup(FixMessageDecoder decoder, FixFieldsDecoderMapper fieldsDecoderMapper) {
        delegate.onDecoderSetup(decoder, fieldsDecoderMapper);
    }

    @Override
    public void onLogon() {
        if (forwarded.logon) {
            emitType(AsyncPluginEvent.Type.LOGON);
        }
    }

    @Override
    public void onLogout() {
        if (forwarded.logout) {
            emitType(AsyncPluginEvent.Type.LOGOUT);
        }
    }

    @Override
    public void onMessageDecodingStarted(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        if (forwarded.decodingStarted) {
            emit(WRITE_DECODING_STARTED, localReceiveTimeInNanos, messageType, localReceiveTime, AsyncPluginEvent.Type.DECODING_STARTED);
        }
    }

    @Override
    public void onMessageDecodingFinished(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        if (forwarded.decodingFinished) {
            emit(WRITE_DECODING_FINISHED, localReceiveTimeInNanos, messageType, localReceiveTime, AsyncPluginEvent.Type.DECODING_FINISHED);
        }
    }

    @Override
    public void onMessageReceived(MessageType messageType, int payloadSize, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        if (forwarded.received) {
            scratch.set(AsyncPluginEvent.Type.RECEIVED, messageType, payloadSize, localReceiveTimeInNanos, localReceiveTime);
            emit(COPY_MESSAGE_SIZE_WITH_TIME, scratch, AsyncPluginEvent.Type.RECEIVED);
        }
    }

    @Override
    public Object getMessageEncodingToken(MessageType messageType, long encodingStartTimeInNanos) {
        // Produced synchronously on the producing thread: the delegate's token is captured here so the engine
        // can hand it back to this wrapper's I/O-thread onMessageEncodedBody / onMessageEncodingFinished, from
        // where it rides the ring-buffer slot to the delegate's replayed callbacks on the consumer thread.
        return forwarded.encodingToken ? delegate.getMessageEncodingToken(messageType, encodingStartTimeInNanos) : null;
    }

    @Override
    public void onMessageEncodingStarted(MessageType messageType, long encodingStartTimeInNanos, Object encodingToken) {
        if (forwarded.encodingStarted) {
            // The token is captured synchronously on the producing thread (getMessageEncodingToken) and carried
            // by reference through the slot, so the delegate's replayed onMessageEncodingStarted sees the same
            // token as the synchronous engine would — consistent with onMessageEncodedBody / onMessageEncodingFinished.
            emit(WRITE_ENCODING_STARTED, encodingStartTimeInNanos, messageType, encodingToken,
                    AsyncPluginEvent.Type.ENCODING_STARTED);
        }
    }

    @Override
    public void onMessageEncodingFinished(MessageType messageType, long encodingEndTimeInNanos, Object encodingToken) {
        if (forwarded.encodingFinished) {
            emit(WRITE_ENCODING_FINISHED, encodingEndTimeInNanos, messageType, encodingToken,
                    AsyncPluginEvent.Type.ENCODING_FINISHED);
        }
    }

    @Override
    public void onMessageEncodedBody(MessageType messageType, ByteBuffer encodedBody, FixFieldsEncoder<?> fixFieldsEncoder, long encodingStartTimeInNanos, Object encodingToken) {
        if (forwarded.encodedBody) {
            // The live body buffer and the token are staged, then the translator snapshots the bytes into the
            // slot during offer and copies the token by reference; the (pooled) encoder cannot cross threads
            // and is not forwarded.
            encodedBodyScratch.set(messageType, encodingStartTimeInNanos, encodedBody, encodingToken);
            emit(COPY_ENCODED_BODY, encodedBodyScratch, AsyncPluginEvent.Type.ENCODED_BODY);
        }
    }

    @Override
    public void onMessageSent(MessageType messageType, int payloadSize, long localSendingTimeInNanos, UTCTime localSendingTime) {
        if (forwarded.sent) {
            scratch.set(AsyncPluginEvent.Type.SENT, messageType, payloadSize, localSendingTimeInNanos, localSendingTime);
            emit(COPY_MESSAGE_SIZE_WITH_TIME, scratch, AsyncPluginEvent.Type.SENT);
        }
    }

    @Override
    public void onRttMeasurement(RttMeasurement measurement) {
        if (forwarded.rtt) {
            emit(WRITE_RTT, measurement, AsyncPluginEvent.Type.RTT);
        }
    }

    @Override
    public void onSessionDestroyed(String fixInstanceId, FixSessionId fixSessionId) {
        // Terminal event: it must not be dropped (it also triggers the queue's unregistration), so it is
        // always published with a blocking offer regardless of the configured backpressure policy.
        queue.offerBlocking(WRITE_SESSION_DESTROYED, fixInstanceId, fixSessionId, producerIdleStrategy);
    }

    private void emitType(AsyncPluginEvent.Type type) {
        if (backpressurePolicy == BackpressurePolicy.BLOCK) {
            queue.offerBlocking(WRITE_TYPE, type, producerIdleStrategy);
            return;
        }
        recordOffer(queue.offer(WRITE_TYPE, type), type);
    }

    private <A> void emit(EventTranslatorOneArg<AsyncPluginEvent, A> translator, A arg, AsyncPluginEvent.Type type) {
        if (backpressurePolicy == BackpressurePolicy.BLOCK) {
            queue.offerBlocking(translator, arg, producerIdleStrategy);
            return;
        }
        recordOffer(queue.offer(translator, arg), type);
    }

    private <A, B> void emit(EventTranslatorThreeLongArg<AsyncPluginEvent, A, B> translator, long value, A arg1, B arg2, AsyncPluginEvent.Type type) {
        if (backpressurePolicy == BackpressurePolicy.BLOCK) {
            queue.offerBlocking(translator, value, arg1, arg2, producerIdleStrategy);
            return;
        }
        recordOffer(queue.offer(translator, value, arg1, arg2), type);
    }

    private void recordOffer(boolean offered, AsyncPluginEvent.Type type) {
        if (!offered) {
            droppedEvents++;
            warnDropThrottled(type);
        }
    }

    private void warnDropThrottled(AsyncPluginEvent.Type type) {
        long now = System.nanoTime();
        if (now - lastDropWarnNanos > DROP_WARN_THROTTLE_NANOS) {
            lastDropWarnNanos = now;
            log.warn("Async plugin queue full for delegate {}: dropping {} events (last dropped: {}); "
                            + "consider increasing queueSize/consumerThreadPoolSize or using BLOCK backpressure",
                    delegate.getClass().getSimpleName(), droppedEvents, type);
        }
    }

    /**
     * Reusable staging slot for the only two callbacks — {@code onMessageReceived} and
     * {@code onMessageSent} — that carry both a payload size and a wall-clock timestamp. No ring-buffer
     * event translator takes all four arguments at once, so the values are first staged here and then
     * copied by value into the ring-buffer slot by {@link AsyncPluginEvent#copyMessageSizeWithTime}. Both
     * callbacks run on the session's single I/O thread and fully consume the staged values within one
     * call, so a single re-populated instance suffices and this staging never allocates.
     */
    static final class MessageSizeScratch {
        AsyncPluginEvent.Type type;
        MessageType messageType;
        int payloadSize;
        long timeNanos;
        long epochSeconds;
        int nanosOfSecond;

        void set(AsyncPluginEvent.Type type, MessageType messageType, int payloadSize, long timeNanos, UTCTime time) {
            this.type = type;
            this.messageType = messageType;
            this.payloadSize = payloadSize;
            this.timeNanos = timeNanos;
            this.epochSeconds = time.getEpochSeconds();
            this.nanosOfSecond = time.getNanosOfSecond();
        }
    }

    /**
     * Reusable staging slot for the {@code onMessageEncodedBody} path, which carries a live body
     * {@link ByteBuffer} (snapshotted by value into the ring slot during offer) plus the per-message encoding
     * token (carried by reference). Runs on the session's single I/O thread and is fully consumed within one
     * {@code offer}, so a single re-populated instance suffices and this staging never allocates.
     */
    static final class EncodedBodyScratch {
        MessageType messageType;
        long timeNanos;
        ByteBuffer body;
        Object token;

        void set(MessageType messageType, long timeNanos, ByteBuffer body, Object token) {
            this.messageType = messageType;
            this.timeNanos = timeNanos;
            this.body = body;
            this.token = token;
        }
    }

    /**
     * Which of the delegate's default (empty) callbacks it actually overrides. A callback the delegate
     * leaves as the {@link FixSessionPlugin} default is a no-op, so this wrapper never enqueues an event
     * for it. Resolved once per delegate class via reflection and cached in {@link #FORWARDED_CV}.
     *
     * <p>{@code onDecoderSetup} and the value-returning context callbacks are excluded: they run synchronously.
     * {@code getMessageEncodingToken} is the exception among value-returning callbacks — its {@code encodingToken}
     * flag gates whether the delegate's token is captured synchronously (not an enqueue), so it is included.
     * {@code onSessionDestroyed} is always forwarded because it also drives queue unregistration.
     */
    private static final class Forwarded {
        final boolean logon;
        final boolean logout;
        final boolean decodingStarted;
        final boolean decodingFinished;
        final boolean received;
        final boolean encodingToken;
        final boolean encodingStarted;
        final boolean encodingFinished;
        final boolean encodedBody;
        final boolean sent;
        final boolean rtt;

        Forwarded(Class<?> delegateClass) {
            logon = overrides(delegateClass, "onLogon");
            logout = overrides(delegateClass, "onLogout");
            decodingStarted = overrides(delegateClass, "onMessageDecodingStarted", MessageType.class, long.class, UTCTime.class);
            decodingFinished = overrides(delegateClass, "onMessageDecodingFinished", MessageType.class, long.class, UTCTime.class);
            received = overrides(delegateClass, "onMessageReceived", MessageType.class, int.class, long.class, UTCTime.class);
            encodingToken = overrides(delegateClass, "getMessageEncodingToken", MessageType.class, long.class);
            encodingStarted = overrides(delegateClass, "onMessageEncodingStarted", MessageType.class, long.class, Object.class);
            encodingFinished = overrides(delegateClass, "onMessageEncodingFinished", MessageType.class, long.class, Object.class);
            encodedBody = overrides(delegateClass, "onMessageEncodedBody", MessageType.class, ByteBuffer.class, FixFieldsEncoder.class, long.class, Object.class);
            sent = overrides(delegateClass, "onMessageSent", MessageType.class, int.class, long.class, UTCTime.class);
            rtt = overrides(delegateClass, "onRttMeasurement", RttMeasurement.class);
        }

        private static boolean overrides(Class<?> delegateClass, String method, Class<?>... paramTypes) {
            try {
                // getMethod resolves to the interface's default method when the class does not override it.
                return delegateClass.getMethod(method, paramTypes).getDeclaringClass() != FixSessionPlugin.class;
            } catch (NoSuchMethodException e) {
                // Interface methods are always resolvable; forward on the impossible case to stay safe.
                return true;
            }
        }
    }
}
