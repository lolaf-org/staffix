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


import org.lolaf.staffix.api.msg.MessageType;

import java.nio.ByteBuffer;

/**
 * Observes a message being encoded, from the first field to the finished bytes.
 *
 * <p>Every callback carries the encoding start time in nanos so a listener can measure the encode without
 * reading a clock of its own - it is on the message path, and the session layer has already paid for that read.
 *
 * <p>{@link VoidFixMessageEncodingListener} is the singleton used when nothing is observing, so the encoder
 * calls through an empty method rather than testing for null on every field.
 */
public interface FixMessageEncodingListener {

    void onEncodingStart(MessageType messageType, FixMessageEncoder<?> encoder, long encodingStartTimeInNanos);

    void onEncodedBody(MessageType messageType, ByteBuffer encodedBody, FixMessageEncoder<?> encoder, long encodingStartTimeInNanos);

    void onEncodingEnd(MessageType messageType, ByteBuffer encodedMessage, FixMessageEncoder<?> encoder, long encodingStartTimeInNanos);

    class VoidFixMessageEncodingListener implements FixMessageEncodingListener {

        private static final VoidFixMessageEncodingListener INSTANCE = new VoidFixMessageEncodingListener();

        private VoidFixMessageEncodingListener() {
            // singleton
        }

        public static FixMessageEncodingListener getInstance() {
            return INSTANCE;
        }

        @Override
        public void onEncodingStart(MessageType messageType, FixMessageEncoder<?> encoder, long encodingStartTimeInNanos) {
            // nothing to do
        }

        @Override
        public void onEncodedBody(MessageType messageType, ByteBuffer encodedBody, FixMessageEncoder<?> encoder, long encodingStartTimeInNanos) {
            // nothing to do
        }

        @Override
        public void onEncodingEnd(MessageType messageType, ByteBuffer encodedMessage, FixMessageEncoder<?> encoder, long encodingStartTimeInNanos) {
            // nothing to do
        }
    }

}