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
package org.lolaf.staffix.api.logging;

import org.lolaf.staffix.api.msg.MessageType;

import java.nio.ByteBuffer;

/**
 * Masks sensitive field values, such as a Logon's Password(554), before a message reaches the log.
 */
public interface LogObfuscator {

    byte EQUALS_CHAR = '=';
    byte FIELD_SEPARATOR_CHAR = '\001';
    /**
     * What each masked character is replaced with.
     */
    byte OBFUSCATED_CHAR = '*';

    /**
     * Whether messages of this type are passed to {@link #obfuscate(ByteBuffer)}, so other types cost nothing.
     */
    boolean isForMessageType(MessageType messageType);

    /**
     * Masks the message and returns the buffer to log. It may overwrite values in place, keeping the message length.
     *
     * @param fixMessageToObfuscate the raw message, between its position and limit
     */
    ByteBuffer obfuscate(ByteBuffer fixMessageToObfuscate);

    /**
     * Masks the value of the first field a subclass recognises, character for character, so the message keeps its
     * length.
     */
    abstract class AbstractLogObfuscator implements LogObfuscator {

        @Override
        public ByteBuffer obfuscate(ByteBuffer fixMessageToObfuscate) {
            for (int i = fixMessageToObfuscate.position(); i < fixMessageToObfuscate.limit(); i++) {
                if (positionMatchesFieldStart(fixMessageToObfuscate, i) && obfuscate(fixMessageToObfuscate, i + getFieldNumberCharsLen() + 1)) {
                    return fixMessageToObfuscate;
                }
            }
            return fixMessageToObfuscate;
        }

        private boolean obfuscate(ByteBuffer fixMessageToObfuscate, int startPosition) {
            for (int j = startPosition; j < fixMessageToObfuscate.limit(); j++) {
                if (fixMessageToObfuscate.get(j) == FIELD_SEPARATOR_CHAR) {
                    int currentPosition = fixMessageToObfuscate.position();
                    fixMessageToObfuscate.position(startPosition);
                    for (int k = startPosition; k < j; k++) {
                        fixMessageToObfuscate.put(OBFUSCATED_CHAR);
                    }
                    fixMessageToObfuscate.position(currentPosition);
                    return true;
                }
            }
            return false;
        }

        /**
         * How many characters the field's tag number has, to skip it and the {@code =} to reach the value.
         */
        protected abstract int getFieldNumberCharsLen();

        /**
         * Check if current position matches target FIX field start
         *
         * @param fixMessageToObfuscate the message to obfuscate
         * @param position              the current position in the buffer
         * @return true if the current position is the start position of the target FIX field
         */
        protected abstract boolean positionMatchesFieldStart(ByteBuffer fixMessageToObfuscate, int position);
    }
}