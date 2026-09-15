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
package org.lolaf.staffix.codec.decoders;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.lolaf.staffix.api.codec.DecodingException;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.time.UTCTime;

import java.util.List;

/**
 * Observes the parser: a message starting, its decoder succeeding or failing, and the rejects it collected.
 *
 * <p>How the session layer learns what the parser did without the parser knowing about sessions.
 */
public interface FixMessageParserEventsListener {

    default void onMessageDecoded(MessageType messageType, FixMessageDecoder fixMessageDecoder, long incomingSeqNum, boolean possibleDuplicate, boolean possResend, long localReceiveTimeInNanos, UTCTime localReceiveTime) {

    }

    default void onMessageDecodingFailed(MessageType messageType, FixMessageDecoder fixMessageDecoder, long incomingSeqNum, DecodingException decodingFailureCause, long localReceiveTimeInNanos, UTCTime localReceiveTime) {

    }

    default void onMessageRejects(MessageType messageType, FixMessageDecoder fixMessageDecoder, long incomingSeqNum, List<MessageReject> rejects) {

    }

    default void onMessageDecodingEnd(MessageType messageType, int payloadSize, long localReceiveTimeInNanos, UTCTime localReceiveTime) {

    }

    default void onMessageDecodingStart(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {

    }

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    final class VoidFixMessageParserEventsListener implements FixMessageParserEventsListener {
        private static final FixMessageParserEventsListener INSTANCE = new VoidFixMessageParserEventsListener();

        public static FixMessageParserEventsListener getInstance() {
            return INSTANCE;
        }

    }
}
