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
package org.lolaf.staffix.examples.plugins;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.codec.FixFieldsEncoder;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Adds one user defined field to every outbound message of a chosen type, without the application that produced the
 * message knowing anything about it.
 *
 * <p>This is the whole of a session plugin: an engine-level {@link FixSessionsPlugin} that is asked, per session,
 * whether it wants that session, and a per-session {@link FixSessionPlugin} that gets the callbacks. The API this
 * exercises is written up in {@code docs/session-plugins.md}.
 *
 * <p>The interesting callback is {@link StampingFixSessionPlugin#onMessageEncodedBody}, which fires from
 * {@code FixMessageEncoder.encode()} <b>before</b> BodyLength(9) and CheckSum(10) are computed — so a field appended
 * there is a first-class part of the message, counted and checksummed like any other. It is the same mechanism the
 * shipped OpenTelemetry plugin uses to put a W3C trace context on the wire.
 */
@Slf4j
public class StampingFixSessionsPlugin implements FixSessionsPlugin<PluginContext.VoidPluginContext> {

    private final StampingFixSessionsPluginSettings settings;

    public StampingFixSessionsPlugin(StampingFixSessionsPluginSettings settings) {
        this.settings = settings;
    }

    @Override
    public String getInstanceId() {
        return settings.getInstanceId();
    }

    /**
     * Asked once per session, as the session is created and after its {@code FixApplication} has declared what it
     * sends. Returning {@code Optional.empty()} means "not interested in this one", and costs the session nothing
     * thereafter — no callback of this plugin is ever invoked on it.
     *
     * <p>A plugin returned here <b>must be a fresh instance for this session</b>. Shared instances are not
     * supported, and that is the whole reason the per-session callbacks can keep mutable state without
     * synchronising.
     */
    @Override
    public Optional<? extends FixSessionPlugin<PluginContext.VoidPluginContext, ?>> onSessionCreated(
            String fixInstanceId, FixSession fixSession,
            Collection<MessageType> incomingMessageTypes, Collection<MessageType> outgoingMessageTypes) {
        if (!outgoingMessageTypes.contains(settings.getStampedMessageType())) {
            log.info("stamping plugin: session {} does not send {}, declining it",
                    fixSession.getFixSessionId().getId(), settings.getStampedMessageType().code());
            return Optional.empty();
        }
        // The field has to exist in the session's registry before it can be encoded. Registering it here, rather
        // than in the application, is what keeps the application unaware of the stamp.
        FixField stampField = fixSession.getFieldsRegistry()
                .addUserDefinedField(settings.getStampFieldCode(), FieldType.STRING, FieldLocation.BODY);
        return Optional.of(new StampingFixSessionPlugin(settings.getStampedMessageType(), stampField,
                settings.getStampSupplier()));
    }

    /**
     * The per-session half. Its callbacks belong to one session, so the counter below needs no synchronisation —
     * with the one exception noted on {@link #getMessageEncodingToken}.
     */
    @Slf4j
    static final class StampingFixSessionPlugin
            implements FixSessionPlugin<PluginContext.VoidPluginContext, String> {

        private final MessageType stampedMessageType;
        private final FixField stampField;
        private final Supplier<String> stampSupplier;
        private long stamped;

        StampingFixSessionPlugin(MessageType stampedMessageType, FixField stampField, Supplier<String> stampSupplier) {
            this.stampedMessageType = stampedMessageType;
            this.stampField = stampField;
            this.stampSupplier = stampSupplier;
        }

        /**
         * Runs on the thread that produced the message, from {@code FixMessageEncoder.begin()}, and is the only
         * callback here that may be called concurrently — so it must be thread-safe, and cheap, being on the
         * producing path.
         *
         * <p>The value returned is the <b>encoding token</b>: the engine stashes it on the encoder and hands it back
         * to this same plugin's {@code onMessageEncodingStarted}, {@code onMessageEncodedBody} and
         * {@code onMessageEncodingFinished} for this message. That matters because those three run later, on the
         * session's I/O thread — a thread local set here would not survive the hop, and the token is the supported
         * way across it.
         *
         * <p>Deciding here whether the message is one to stamp also means the I/O thread has nothing to decide: a
         * {@code null} token is the whole of "leave this message alone".
         */
        @Override
        public String getMessageEncodingToken(MessageType messageType, long encodingStartTimeInNanos) {
            return stampedMessageType.equals(messageType) ? stampSupplier.get() : null;
        }

        /**
         * Runs on the session's I/O thread, from {@code FixMessageEncoder.encode()}, once the application has
         * finished filling the message in and before the header, BodyLength(9) and CheckSum(10) are laid down.
         * Appending to the encoder here is therefore safe: the field lands in the body, and the length and checksum
         * that follow account for it.
         *
         * @param encodedBody      the body as encoded so far — readable, and left alone here; the field is added
         *                         through the encoder rather than by writing bytes into this buffer
         * @param fixFieldsEncoder the encoder that produced the message, or {@code null} when the plugin has been
         *                         wrapped in the asynchronous wrapper: a pooled encoder cannot cross threads, so
         *                         only the bytes are replayed and there is nothing left to append to
         * @param encodingToken    what {@link #getMessageEncodingToken} returned for this message
         */
        @Override
        public void onMessageEncodedBody(MessageType messageType, ByteBuffer encodedBody,
                                         FixFieldsEncoder<?> fixFieldsEncoder, long encodingStartTimeInNanos,
                                         String encodingToken) {
            if (encodingToken == null) {
                return;
            }
            if (fixFieldsEncoder == null) {
                throw new IllegalStateException("no encoder to stamp " + messageType.code()
                        + " with: a plugin that writes fields cannot be run behind the asynchronous wrapper");
            }
            fixFieldsEncoder.addString(stampField, encodingToken);
            stamped++;
        }

        @Override
        public void onSessionDestroyed(String fixInstanceId, FixSessionId fixSessionId) {
            log.info("stamping plugin: stamped {} {} message(s) on session {}", stamped,
                    stampedMessageType.code(), fixSessionId.getId());
        }

        /**
         * This plugin publishes nothing for the application to read back, so it claims no context type and
         * {@code FixSession.getPluginContext(…)} never resolves to it. A plugin that does expose state returns its
         * own {@link PluginContext} here and answers {@code true} for that class above.
         */
        @Override
        public boolean isForPluginContext(Class<? extends PluginContext> pluginClass) {
            return false;
        }

        @Override
        public Optional<PluginContext.VoidPluginContext> getPluginContext() {
            return Optional.empty();
        }
    }
}
