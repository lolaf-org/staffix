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
package org.lolaf.staffix.plugins.throttling;

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

/**
 * A {@link FixSessionPlugin} wrapper that rate-limits (samples) the wrapped plugin's per-message callbacks,
 * dropping the overflow so a delegate is not overwhelmed at peak throughput. Value-returning and lifecycle
 * callbacks are always delegated inline.
 *
 * <p>Throttling is message-atomic: a whole callback group is admitted or dropped together, so the delegate
 * never sees a {@code started} without its {@code finished}. The admission decision is taken once, at the
 * group's first callback:
 * <ul>
 *   <li>inbound — decided at {@link #onMessageDecodingStarted}, applied to {@code onMessageDecodingFinished}
 *       and {@code onMessageReceived}; these all run on the session's single I/O thread, so a plain field
 *       carries the decision;</li>
 *   <li>encoding — decided at {@link #getMessageEncodingToken} (on the producing application thread, so the
 *       limiter is lock-free) and applied to {@code onMessageEncodingStarted}, {@code onMessageEncodedBody} and
 *       {@code onMessageEncodingFinished}. The decision crosses the thread hop to the I/O-thread callbacks
 *       through the engine's per-message <em>encoding token</em>: {@code getMessageEncodingToken} returns either
 *       {@link #DROPPED} or the delegate's own token, and the later callbacks forward to the delegate only when
 *       the token is not {@code DROPPED}, replaying the delegate's token so the delegate's own
 *       started/body/finished stay correlated;</li>
 *   <li>{@link #onMessageSent} is a single I/O-thread callback throttled on its own, independently of the
 *       encoding group of the same message (the two are uncorrelated across threads).</li>
 * </ul>
 *
 * <p>A {@code null} limiter means that direction is unthrottled (max ≤ 0) and every call is forwarded.
 * Each callback reuses the {@code System.nanoTime()}-based timestamp the engine already passes, so
 * admission needs no clock read of its own; {@link #requiresTimeMeasurement()} therefore returns
 * {@code true} to guarantee those timestamps are captured.
 *
 * @param <C> the wrapped plugin's context type
 */
final class ThrottlingFixSessionPlugin<C> implements FixSessionPlugin<C, Object> {

    // Sentinel encoding token meaning "this message's encoding group was dropped". Returned from
    // getMessageEncodingToken and recognised by onMessageEncodingStarted and the I/O-thread encoding
    // callbacks; distinct from the delegate's own tokens (including its null) so an admitted message with a
    // null delegate token still forwards correctly.
    static final Object DROPPED = new Object();

    // The delegate's own token type is irrelevant here: this wrapper multiplexes the DROPPED sentinel with
    // whatever the delegate returns, so it treats the token opaquely as Object.
    private final FixSessionPlugin<C, Object> delegate;
    private final FixedWindowLimiter receiveLimiter;
    private final AtomicFixedWindowLimiter encodeLimiter;
    private final FixedWindowLimiter sentLimiter;

    // Best-effort drop counters. droppedReceived/droppedSent are only touched on the single I/O thread and
    // are therefore exact; droppedEncode is incremented from the possibly-concurrent producing application
    // threads with a plain (racy) ++, which may lose updates — accurate encode-drop counting is not required.
    private long droppedReceived;
    private long droppedEncode;
    private long droppedSent;

    // Inbound group decision: written and read only on the single I/O thread.
    private boolean inboundAdmitted;

    @SuppressWarnings("unchecked")
    ThrottlingFixSessionPlugin(FixSessionPlugin<C, ?> delegate, int maxReceivedMessages, int maxSentMessages, long windowNanos) {
        this.delegate = (FixSessionPlugin<C, Object>) delegate;
        this.receiveLimiter = maxReceivedMessages > 0 ? new FixedWindowLimiter(maxReceivedMessages, windowNanos) : null;
        this.encodeLimiter = maxSentMessages > 0 ? new AtomicFixedWindowLimiter(maxSentMessages, windowNanos) : null;
        this.sentLimiter = maxSentMessages > 0 ? new FixedWindowLimiter(maxSentMessages, windowNanos) : null;
    }

    long getDroppedReceivedCount() {
        return droppedReceived;
    }

    long getDroppedEncodeCount() {
        return droppedEncode;
    }

    long getDroppedSentCount() {
        return droppedSent;
    }

    @Override
    public boolean requiresTimeMeasurement() {
        // Admission reuses the engine-provided timestamps, so they must always be captured.
        return true;
    }

    // --- Value-returning and lifecycle callbacks: always delegated inline. ---

    @Override
    public boolean isForPluginContext(Class<? extends PluginContext> pluginClass) {
        return delegate.isForPluginContext(pluginClass);
    }

    @Override
    public Optional<C> getPluginContext() {
        return delegate.getPluginContext();
    }

    @Override
    public void onDecoderSetup(FixMessageDecoder decoder, FixFieldsDecoderMapper fieldsDecoderMapper) {
        delegate.onDecoderSetup(decoder, fieldsDecoderMapper);
    }

    @Override
    public void onLogon() {
        delegate.onLogon();
    }

    @Override
    public void onLogout() {
        delegate.onLogout();
    }

    @Override
    public void onRttMeasurement(RttMeasurement measurement) {
        delegate.onRttMeasurement(measurement);
    }

    @Override
    public void onSessionDestroyed(String fixInstanceId, FixSessionId fixSessionId) {
        delegate.onSessionDestroyed(fixInstanceId, fixSessionId);
    }

    // --- Inbound group: decided at decodingStarted, applied to the group (I/O thread only). ---

    @Override
    public void onMessageDecodingStarted(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        inboundAdmitted = receiveLimiter == null || receiveLimiter.tryAcquire(localReceiveTimeInNanos);
        if (inboundAdmitted) {
            delegate.onMessageDecodingStarted(messageType, localReceiveTimeInNanos, localReceiveTime);
        } else {
            droppedReceived++;
        }
    }

    @Override
    public void onMessageDecodingFinished(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        if (inboundAdmitted) {
            delegate.onMessageDecodingFinished(messageType, localReceiveTimeInNanos, localReceiveTime);
        }
    }

    @Override
    public void onMessageReceived(MessageType messageType, int payloadSize, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
        if (inboundAdmitted) {
            delegate.onMessageReceived(messageType, payloadSize, localReceiveTimeInNanos, localReceiveTime);
        }
    }

    // --- Encoding group: decided at encodingStarted, carried to body/finished by the encoding token. ---

    @Override
    public Object getMessageEncodingToken(MessageType messageType, long encodingStartTimeInNanos) {
        // Decision taken here on the producing thread (lock-free limiter); the returned token relays it — and
        // the delegate's own token — across the thread hop to onMessageEncodedBody / onMessageEncodingFinished.
        if (encodeLimiter == null || encodeLimiter.tryAcquire(encodingStartTimeInNanos)) {
            return delegate.getMessageEncodingToken(messageType, encodingStartTimeInNanos);
        }
        droppedEncode++;
        return DROPPED;
    }

    @Override
    public void onMessageEncodingStarted(MessageType messageType, long encodingStartTimeInNanos, Object encodingToken) {
        // Suppress the delegate's started notification for a dropped encoding group, matching body/finished.
        if (encodingToken != DROPPED) {
            delegate.onMessageEncodingStarted(messageType, encodingStartTimeInNanos, encodingToken);
        }
    }

    @Override
    public void onMessageEncodedBody(MessageType messageType, ByteBuffer encodedBody, FixFieldsEncoder<?> fixFieldsEncoder, long encodingStartTimeInNanos, Object encodingToken) {
        if (encodingToken != DROPPED) {
            delegate.onMessageEncodedBody(messageType, encodedBody, fixFieldsEncoder, encodingStartTimeInNanos, encodingToken);
        }
    }

    @Override
    public void onMessageEncodingFinished(MessageType messageType, long encodingEndTimeInNanos, Object encodingToken) {
        if (encodingToken != DROPPED) {
            delegate.onMessageEncodingFinished(messageType, encodingEndTimeInNanos, encodingToken);
        }
    }

    // --- Sent: single I/O-thread callback, throttled independently of the encoding group. ---

    @Override
    public void onMessageSent(MessageType messageType, int payloadSize, long localSendingTimeInNanos, UTCTime localSendingTime) {
        if (sentLimiter == null || sentLimiter.tryAcquire(localSendingTimeInNanos)) {
            delegate.onMessageSent(messageType, payloadSize, localSendingTimeInNanos, localSendingTime);
        } else {
            droppedSent++;
        }
    }
}
