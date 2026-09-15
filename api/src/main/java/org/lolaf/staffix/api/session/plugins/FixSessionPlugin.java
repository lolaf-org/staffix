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
package org.lolaf.staffix.api.session.plugins;

import org.lolaf.staffix.api.codec.*;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.RttMeasurement;
import org.lolaf.staffix.api.time.UTCTime;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * A per-session plugin that receives lifecycle and message processing callbacks for a single FIX session.
 *
 * <p>Instances are created by {@link FixSessionsPlugin#onSessionCreated} when a session is established,
 * and are automatically wrapped in a {@code FailSafeFixSessionPlugin} to prevent plugin exceptions from
 * disrupting session processing.
 *
 * <h2>Callback Order</h2>
 *
 * <p><b>Inbound message processing:</b>
 * <ol>
 *   <li>{@link #onMessageDecodingStarted} — message bytes received, decoding begins</li>
 *   <li>{@link #onMessageDecodingFinished} — application has decoded the message or failed to</li>
 *   <li>{@link #onMessageReceived} — message fully processed by the engine (logged, stored)</li>
 * </ol>
 *
 * <p><b>Outbound message processing:</b>
 * <ol>
 *   <li>{@link #onMessageEncodingStarted} — encoding begins</li>
 *   <li>{@link #onMessageEncodedBody} — message body has been encoded (provides access to the raw bytes and encoder)</li>
 *   <li>{@link #onMessageEncodingFinished} — encoding complete, message ready to be sent</li>
 *   <li>{@link #onMessageSent} — message fully sent to the network adapter</li>
 * </ol>
 *
 * <p><b>Session lifecycle:</b> {@link #onLogon} → (message callbacks) → {@link #onLogout}
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #getMessageEncodingToken} and {@link #onMessageEncodingStarted} are the <b>only</b> callbacks that
 * may be invoked concurrently from multiple threads. They fire from {@link FixMessageEncoder#begin()}, which
 * runs on whichever application thread produces the outbound message, and several application threads may
 * encode on the same session at once. Their implementations must be thread-safe.
 *
 * <p>All other callbacks are invoked on the session's single I/O thread and never run concurrently with
 * one another. In particular {@link #onMessageEncodedBody} and {@link #onMessageEncodingFinished} do
 * <b>not</b> run on the thread that produced the message: they fire later, from {@link FixMessageEncoder#encode}
 * on the I/O thread, immediately before the message is written to the network. A single message's
 * {@link #getMessageEncodingToken} therefore generally runs on a different thread from its own
 * {@link #onMessageEncodedBody}, {@link #onMessageEncodingFinished} and {@link #onMessageSent} — so a value
 * stashed in a thread-local at encoding start is <b>not</b> visible to the later callbacks of the same message.
 * To carry per-message state across that hop, return it as the <b>encoding token</b> from
 * {@link #getMessageEncodingToken}: the engine stashes it on the encoder and hands the same token back to this
 * plugin's {@link #onMessageEncodingStarted}, {@link #onMessageEncodedBody} and
 * {@link #onMessageEncodingFinished} for that message.
 *
 * <p>The started callback still happens-before the I/O-thread callbacks of the same message (the encoder is
 * published to the I/O thread through the outbound ring buffer), so the token is safely visible there; but no
 * cross-thread ordering is guaranteed between different messages.
 *
 * <h2>Time Measurement</h2>
 *
 * <p>Nanosecond timestamps are passed to message callbacks for latency measurement. If no registered plugin
 * requires time measurement (i.e. all plugins return {@code false} from {@link #requiresTimeMeasurement()}),
 * the engine may skip capturing timestamps to avoid the overhead.
 *
 * @param <C> the type of {@link PluginContext} this plugin exposes, allowing other components to access
 *            plugin-specific state for the session
 * @param <T> the type of the per-message <b>encoding token</b> this plugin produces from
 *            {@link #getMessageEncodingToken} and receives back in {@link #onMessageEncodingStarted},
 *            {@link #onMessageEncodedBody} and {@link #onMessageEncodingFinished}; use {@link Void} when the
 *            plugin carries no per-message state across the encoding thread hop
 * @see FixSessionsPlugin
 * @see PluginContext
 */
public interface FixSessionPlugin<C, T> {

    /**
     * Checks whether this plugin provides a context compatible with the given {@link PluginContext} type.
     * see {@link FixSession#getPluginContext(Class)}
     *
     * @param pluginClass the plugin context class to check against
     * @return {@code true} if this plugin's context is compatible with the specified class
     */
    boolean isForPluginContext(Class<? extends PluginContext> pluginClass);

    /**
     * Called when the FIX session is destroyed (when the fix acceptor/initiator is stopped) and will no longer receive any callbacks.
     * Implementations should release any resources associated with the session.
     *
     * @param fixInstanceId the FIX instance identifier
     * @param fixSessionId  the FIX session identifier
     */
    void onSessionDestroyed(String fixInstanceId, FixSessionId fixSessionId);

    /**
     * Returns the plugin-specific context for this session, if available.
     *
     * @return an {@link Optional} containing the plugin context, or empty if not available
     */
    Optional<C> getPluginContext();

    /**
     * Indicates whether this plugin requires nanosecond timestamp capture for message callbacks.
     *
     * <p>If no registered plugin returns {@code true}, the engine may skip time measurement to reduce overhead.
     *
     * @return {@code true} if this plugin needs timestamps in message callbacks, {@code false} by default
     */
    default boolean requiresTimeMeasurement() {
        return false;
    }

    /**
     * Called when the FIX session has successfully completed logon.
     */
    default void onLogon() {

    }

    /**
     * Called when a decoder setup has been finished
     *
     * @param decoder             the decoder
     * @param fieldsDecoderMapper the field decoding mapper for decoding fields from the message,
     *                            allows the plugin to listen to fields (only if the decoder did not already register)
     */
    default void onDecoderSetup(FixMessageDecoder decoder, FixFieldsDecoderMapper fieldsDecoderMapper) {

    }

    /**
     * Called when decoding of an inbound message has started.
     *
     * @param messageType             the type of the FIX message being decoded
     * @param localReceiveTimeInNanos the local timestamp in nanoseconds when the message was received
     * @param localReceiveTime        the local timestamp when the message was received
     */
    default void onMessageDecodingStarted(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {

    }

    /**
     * Called when decoding of an inbound message has finished and been fully processed by the FIX application.
     * This is invoked immediately after
     * {@link org.lolaf.staffix.api.codec.FixMessageDecoder#onDecoded(FixSession, boolean, boolean)} or
     * {@link org.lolaf.staffix.api.codec.FixMessageDecoder#onDecodingFailed(FixSession, DecodingException)}.
     * This excludes logging and persistence to the message store.
     *
     * @param messageType             the type of the FIX message that was decoded
     * @param localReceiveTimeInNanos the local timestamp in nanoseconds when the message was received
     * @param localReceiveTime        the local timestamp when the message was received
     */
    default void onMessageDecodingFinished(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {

    }

    /**
     * Called when an inbound message has been fully processed by the FIX engine, including logging
     * and persistence to the message store.
     *
     * @param messageType             the type of the FIX message
     * @param payloadSize             the size of the message payload in bytes
     * @param localReceiveTimeInNanos the local timestamp in nanoseconds when the message was received
     * @param localReceiveTime        the local timestamp when the message was received
     */
    default void onMessageReceived(MessageType messageType, int payloadSize, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
    }

    /**
     * Produces this message's <b>encoding token</b>, called on the producing application thread immediately
     * before {@link #onMessageEncodingStarted}, from {@link FixMessageEncoder#begin()}.
     *
     * <p>May be invoked concurrently (see the {@code Threading} contract on this interface), so it must be
     * thread-safe, and — being on the latency-critical producing path — cheap. The value returned here is
     * stashed on the encoder by the engine and handed back to this same plugin's
     * {@link #onMessageEncodingStarted}, {@link #onMessageEncodedBody} and {@link #onMessageEncodingFinished}
     * for the same message; the latter two run later on the I/O thread. This is the only supported way to carry
     * per-message state across that thread hop (a thread-local would not survive it). Return {@code null} if no
     * state needs to be carried. The token should be a self-contained value (e.g. a decision flag or a captured
     * context), not an open resource, since a message abandoned after {@code begin()} without being encoded
     * will never see its token handed back.
     *
     * @param messageType              the type of the FIX message being encoded
     * @param encodingStartTimeInNanos the local timestamp in nanoseconds when encoding started
     * @return an opaque per-message token handed back to {@link #onMessageEncodingStarted},
     * {@link #onMessageEncodedBody} and {@link #onMessageEncodingFinished}, or {@code null}
     */
    default T getMessageEncodingToken(MessageType messageType, long encodingStartTimeInNanos) {
        return null;
    }

    /**
     * Called when encoding of an outbound message has started {@link FixMessageEncoder#begin()}, immediately
     * after {@link #getMessageEncodingToken}, on the same producing application thread.
     *
     * <p>Runs on the producing application thread and may be invoked concurrently (see the {@code Threading}
     * contract on this interface); its implementation must be thread-safe.
     *
     * @param messageType              the type of the FIX message being encoded
     * @param encodingStartTimeInNanos the local timestamp in nanoseconds when encoding started
     * @param encodingToken            the token this plugin returned from {@link #getMessageEncodingToken} for
     *                                 this message, or {@code null}
     */
    default void onMessageEncodingStarted(MessageType messageType, long encodingStartTimeInNanos, T encodingToken) {

    }

    /**
     * Called when encoding of an outbound message has finished, immediately after {@link #onMessageEncodedBody},
     * on the session's I/O thread.
     *
     * @param messageType            the type of the FIX message that was encoded
     * @param encodingEndTimeInNanos the local timestamp in nanoseconds when encoding finished
     * @param encodingToken          the token this plugin returned from {@link #getMessageEncodingToken} for
     *                               this message, or {@code null}
     */
    default void onMessageEncodingFinished(MessageType messageType, long encodingEndTimeInNanos, T encodingToken) {

    }

    /**
     * Called after the message body has been encoded, providing access to the raw encoded bytes
     * and the encoder used. This can be used for inspection or post-processing of the encoded message.
     *
     * <p>Runs on the session's I/O thread (see the {@code Threading} contract on this interface): it is the
     * first encoding callback of a message to run there, and carries the same {@code encodingStartTimeInNanos}
     * as {@link #onMessageEncodingStarted} and {@link #onMessageEncodingFinished} so the whole encoding group
     * shares one timestamp.
     *
     * @param messageType              the type of the FIX message
     * @param encodedBody              the buffer containing the encoded message body
     * @param fixFieldsEncoder         the encoder that produced the message, or {@code null} when this plugin is
     *                                 run asynchronously: the (pooled) encoder cannot cross threads, so it is not
     *                                 forwarded to the replayed callback — only the encoded body bytes are
     * @param encodingStartTimeInNanos the local timestamp in nanoseconds when encoding started
     * @param encodingToken            the token this plugin returned from {@link #getMessageEncodingToken} for
     *                                 this message, or {@code null}
     */
    default void onMessageEncodedBody(MessageType messageType, ByteBuffer encodedBody, FixFieldsEncoder<?> fixFieldsEncoder, long encodingStartTimeInNanos, T encodingToken) {

    }

    /**
     * Called when an outbound message has been fully sent to the network adapter.
     *
     * @param messageType             the type of the FIX message
     * @param payloadSize             the size of the message payload in bytes
     * @param localSendingTimeInNanos the local timestamp in nanoseconds when the message was sent
     * @param localSendingTime        the local timestamp when the message was sent
     */
    default void onMessageSent(MessageType messageType, int payloadSize, long localSendingTimeInNanos, UTCTime localSendingTime) {

    }

    /**
     * Called when the FIX session has completed logout.
     */
    default void onLogout() {

    }

    /**
     * Called after each accepted RTT/clock-offset sample produced by the continuous RTT
     * measurement machinery. Not invoked for samples that were dropped (unknown TestReqID,
     * duplicate, or outside {@link org.lolaf.staffix.api.session.FixSessionSettings.RttMeasurementSettings#getMaxAcceptedRtt()}).
     *
     * @param measurement the latest EMA-smoothed snapshot of round-trip-time, clock offset
     *                    and local sample time
     */
    default void onRttMeasurement(RttMeasurement measurement) {

    }
}
