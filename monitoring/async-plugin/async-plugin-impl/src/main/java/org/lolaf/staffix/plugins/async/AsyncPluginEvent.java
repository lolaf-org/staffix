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
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.RttMeasurement;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.time.UTCTime;

import java.nio.ByteBuffer;

/**
 * A mutable, pre-allocated ring-buffer slot carrying a single deferred {@link FixSessionPlugin} callback.
 *
 * <p>Instances are created once per ring-buffer slot (and once per producer thread as a reusable scratch)
 * and are then repeatedly re-populated in place, so that publishing a callback never allocates.
 *
 * <p><b>Value copies, not references.</b> Callback arguments that reference objects the FIX engine
 * reuses across calls — {@link UTCTime} (see {@link UTCTime#isImmutable()}) and the encoded body
 * {@link ByteBuffer} — are copied <em>by value</em> into primitive fields / a slot-owned buffer while
 * still on the producing (latency-critical) thread. {@link MessageType} and {@link FixSessionId} are
 * engine singletons / immutable and are stored by reference. The per-message encoding token is likewise
 * stored by reference: it is plugin-created (not reused by the engine) and already crosses one thread hop in
 * the synchronous design, so publishing it to the consumer thread carries the same visibility guarantee.
 */
@Slf4j
final class AsyncPluginEvent {

    private final UTCTime.TimeImpl utcTime = new UTCTime.TimeImpl();
    private Type type;
    private MessageType messageType;
    private int payloadSize;
    private long timeNanos;
    private RttMeasurement rttMeasurement;
    private String fixInstanceId;
    private FixSessionId fixSessionId;
    // ENCODED_BODY: slot-owned copy target, grown on demand.
    private ByteBuffer bodyCopy;
    // ENCODING_STARTED / ENCODED_BODY / ENCODING_FINISHED: the per-message encoding token, carried by
    // reference. Safe to store by reference (unlike UTCTime / the body buffer) because the token is
    // plugin-created and not reused by the engine; it already crosses at least one thread hop in the
    // synchronous design, so publishing it one more hop to the consumer thread is the same visibility
    // property. Nulled after dispatch so a pooled slot does not pin the token object.
    private Object encodingToken;
    // Injected once per ring slot at registration: unregisters this slot's queue from its consumer thread
    // after the terminal SESSION_DESTROYED event is replayed, so the consumer loop needs no per-event test.
    private Runnable onSessionDestroyed;

    void writeType(Type type) {
        this.type = type;
    }

    void writeDecodingStarted(long timeNanos, MessageType messageType, UTCTime time) {
        writeDecoding(Type.DECODING_STARTED, timeNanos, messageType, time);
    }

    void writeDecodingFinished(long timeNanos, MessageType messageType, UTCTime time) {
        writeDecoding(Type.DECODING_FINISHED, timeNanos, messageType, time);
    }

    private void writeDecoding(Type type, long timeNanos, MessageType messageType, UTCTime time) {
        this.type = type;
        this.messageType = messageType;
        this.timeNanos = timeNanos;
        this.utcTime.from(time.getEpochSeconds(), time.getNanosOfSecond());
    }

    void writeEncodingStarted(long timeNanos, MessageType messageType, Object token) {
        this.type = Type.ENCODING_STARTED;
        this.messageType = messageType;
        this.timeNanos = timeNanos;
        this.encodingToken = token;
    }

    void writeEncodingFinished(long timeNanos, MessageType messageType, Object token) {
        this.type = Type.ENCODING_FINISHED;
        this.messageType = messageType;
        this.timeNanos = timeNanos;
        this.encodingToken = token;
    }

    void copyEncodedBody(AsyncFixSessionPlugin.EncodedBodyScratch src) {
        this.type = Type.ENCODED_BODY;
        this.messageType = src.messageType;
        this.timeNanos = src.timeNanos;
        this.encodingToken = src.token;
        copyBody(src.body);
    }

    void writeRtt(RttMeasurement measurement) {
        this.type = Type.RTT;
        this.rttMeasurement = measurement;
    }

    void writeSessionDestroyed(String fixInstanceId, FixSessionId fixSessionId) {
        this.type = Type.SESSION_DESTROYED;
        this.fixInstanceId = fixInstanceId;
        this.fixSessionId = fixSessionId;
    }

    void copyMessageSizeWithTime(AsyncFixSessionPlugin.MessageSizeScratch src) {
        this.type = src.type;
        this.messageType = src.messageType;
        this.payloadSize = src.payloadSize;
        this.timeNanos = src.timeNanos;
        this.utcTime.from(src.epochSeconds, src.nanosOfSecond);
    }

    private void copyBody(ByteBuffer source) {
        int len = source.remaining();
        if (bodyCopy == null || bodyCopy.capacity() < len) {
            bodyCopy = ByteBuffer.allocate(Math.max(64, Integer.highestOneBit(Math.max(1, len - 1)) << 1));
        }
        // Absolute get/put so the live source buffer's position/limit are left untouched and no
        // temporary duplicate buffer is allocated.
        int pos = source.position();
        for (int i = 0; i < len; i++) {
            bodyCopy.put(i, source.get(pos + i));
        }
        bodyCopy.position(0);
        bodyCopy.limit(len);
        payloadSize = len;
    }

    void safelyDispatch(FixSessionPlugin<?, Object> delegate) {
        try {
            dispatch(delegate);
        } catch (RuntimeException ex) {
            log.error("Failed to replay async plugin callback {} on delegate {}",
                    type, delegate.getClass().getSimpleName(), ex);
        }
    }

    private void dispatch(FixSessionPlugin<?, Object> delegate) {
        // Highest-traffic message callbacks first.
        switch (type) {
            case RECEIVED:
                delegate.onMessageReceived(messageType, payloadSize, timeNanos, utcTime);
                break;
            case SENT:
                delegate.onMessageSent(messageType, payloadSize, timeNanos, utcTime);
                break;
            case DECODING_STARTED:
                delegate.onMessageDecodingStarted(messageType, timeNanos, utcTime);
                break;
            case DECODING_FINISHED:
                delegate.onMessageDecodingFinished(messageType, timeNanos, utcTime);
                break;
            case ENCODING_STARTED:
                delegate.onMessageEncodingStarted(messageType, timeNanos, encodingToken);
                encodingToken = null;
                break;
            case ENCODING_FINISHED:
                delegate.onMessageEncodingFinished(messageType, timeNanos, encodingToken);
                encodingToken = null;
                break;
            case ENCODED_BODY:
                // The encoder cannot be passed as at this stage the message has been already sent and modifying it makes no sense
                delegate.onMessageEncodedBody(messageType, bodyCopy, null, timeNanos, encodingToken);
                encodingToken = null;
                break;
            case RTT:
                delegate.onRttMeasurement(rttMeasurement);
                break;
            case LOGON:
                delegate.onLogon();
                break;
            case LOGOUT:
                delegate.onLogout();
                break;
            case SESSION_DESTROYED:
                try {
                    delegate.onSessionDestroyed(fixInstanceId, fixSessionId);
                } finally {
                    // Always unregister, even if the delegate threw, so the queue is not polled forever.
                    onSessionDestroyed.run();
                }
                break;
            default:
                throw new IllegalStateException("Unexpected async plugin event type: " + type);
        }
    }

    /**
     * Inject the action run after this slot's {@link Type#SESSION_DESTROYED} event is replayed. Set once
     * per slot at registration; shared by all slots of the same queue.
     */
    void setOnSessionDestroyed(Runnable onSessionDestroyed) {
        this.onSessionDestroyed = onSessionDestroyed;
    }

    enum Type {
        LOGON,
        LOGOUT,
        DECODING_STARTED,
        DECODING_FINISHED,
        RECEIVED,
        ENCODING_STARTED,
        ENCODING_FINISHED,
        ENCODED_BODY,
        SENT,
        RTT,
        SESSION_DESTROYED
    }
}
