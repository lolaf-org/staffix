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
package org.lolaf.staffix.api.codec;

import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * A FIX message encoder, instance can be used only once to send a message unless they are return by a {@link FixMessageEncodersPool}
 * or flagged as reusable {@link #asReusable()}
 *
 * @param <T>
 */
public interface FixMessageEncoder<T extends FixMessageEncoder<?>> extends FixFieldsEncoder<T> {

    MessageType getMessageType();

    /**
     * Initializes the encoder to write the message fields, must always be called first
     */
    T begin();

    /**
     * Return an encoder reusable after it has been sent, its destroy method will never be called after being sent
     */
    T asReusable();

    /**
     * Indicates if the encoder is available to encode a new message or if it is currently waiting to be sent
     */
    boolean isAvailable();

    /**
     * Copies an FixMessageEncoder
     */
    T copy(FixMessageEncoder<?> other);

    ByteBuffer getEncodedBody();

    int getEncodedBodyChecksum();

    /**
     * encodes a message on a target session
     *
     * @param allocator           the ByteBuffer allocator to encode the message
     * @param sequenceNumber      the sequence number
     * @param fixSessionId        the fix session id to serialize
     * @param fixApplication      the fix application
     * @param sendingTimeAccuracy the sending time accuracy
     * @param sendingTime         the message sending time
     * @param fixSession          fix session to be used to call {@link FixApplication#onAdminMessageEncoding(FixSession, MessageType, Supplier, Supplier, Supplier)}
     *                            and  {@link FixApplication#onMessageEncoding(FixSession, MessageType, Supplier, Supplier)}, null for bypassing the callbacks
     * @return the fully encoded fix message ready to be sent
     */
    ByteBuffer encode(IntFunction<ByteBuffer> allocator, long sequenceNumber, FixSessionId fixSessionId,
                      FixApplication fixApplication, TimeUnit sendingTimeAccuracy, UTCTime sendingTime, FixSession fixSession);

    int getApproximateEncodedMessageLength(FixSessionId fixSessionId);

    /**
     * Release if needed the encoder when the message has been sent, returns it to a pool if it was a pooled instance, see {@link FixMessageEncodersPool}
     */
    void release();

    /**
     * Destroy the encoder when not needed anymore, clean underlying resources, especially direct byte buffers
     * This method is automatically called if the encoder instance is not reusable (see {@link #isReusable()}) after it has been sent
     */
    void destroy();

    /**
     * Indicates if the encoder is an instance that can be reused
     * If the instance is not reusable, the destroy method will be called after the message has been sent
     */
    boolean isReusable();

    /**
     * Returns this encoder's per-message, per-plugin token array,
     * ensuring it is sized to at least {@code pluginCount} and clearing its first {@code pluginCount} slots.
     * <p>The array lets a {@code FixSessionPlugin}'s token returned from {@code onMessageEncodingStarted} (on the
     * producing thread) reach {@code onMessageEncodedBody} / {@code onMessageEncodingFinished} (on the I/O thread),
     * since the encoder is the only object that spans both. Called by the engine at the start of encoding.
     *
     * @param pluginCount number of registered plugins (one token slot each)
     * @return the cleared token array, indexed by plugin position
     */
    Object[] pluginEncodingState(int pluginCount);

    /**
     * Engine-internal, not for application use. Returns the token array populated by
     * {@link #pluginEncodingState(int)} at the start of encoding, or {@code null} if it was never sized.
     */
    Object[] pluginEncodingState();

}