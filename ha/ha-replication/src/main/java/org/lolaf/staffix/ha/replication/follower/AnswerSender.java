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
package org.lolaf.staffix.ha.replication.follower;

import org.lolaf.staffix.ha.replication.wire.FrameCipher;

import java.nio.ByteBuffer;

/**
 * How a {@link LeaderConnection}'s answers reach the leader: a frame is encoded from index 0 of the buffer claimed, then
 * sent. Whichever thread answers, the network thread seals and sends every answer, so they are sealed in the order the
 * leader opens them.
 */
interface AnswerSender {

    ByteBuffer claim(int bytes);

    /**
     * Sends the frame, sealed once the KEY_CONFIRMATION went.
     */
    void send(ByteBuffer frame, int length);

    /**
     * Sends the KEY_CONFIRMATION as it is, and seals every answer after it with {@code sealer}: the leader opens the
     * follower's frames only once it verified the confirmation.
     */
    void sendKeyConfirmation(ByteBuffer frame, int length, FrameCipher sealer);
}
