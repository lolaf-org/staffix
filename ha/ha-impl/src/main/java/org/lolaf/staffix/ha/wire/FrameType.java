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
package org.lolaf.staffix.ha.wire;

import lombok.Getter;

/**
 * The frames of the replication stream, with the size of their fixed payload; a variable part (a string, a message)
 * follows it.
 *
 * <pre>
 * frame             direction           fixed payload
 * HELLO             follower to leader  magic 4, version 2, node id length 2, last applied epoch 8,
 *                                       last applied offset 8; then the node id
 * WELCOME           leader to follower  magic 4, version 2, leader id length 2, resume offset 8; then the leader id
 * SESSION           leader to follower  session key 4, session type 1, replication mode 1, session id length 2,
 *                                       storage identity length 2; then the session id, then the identity
 * SESSION_STATUS    follower to leader  session key 4, status 1
 * STORE_EVENT       leader to follower  log offset 8, session key 4, event type 1, reserved 3, sequence number 8,
 *                                       message length 4; then the message
 * SNAPSHOT_BEGIN    leader to follower  session key 4, reserved 4, cut offset 8, next incoming 8, next outgoing 8
 * SNAPSHOT_MESSAGE  leader to follower  session key 4, message length 4, sequence number 8; then the message
 * SNAPSHOT_END      leader to follower  session key 4, message count 4
 * ACK               follower to leader  applied offset 8
 * HEARTBEAT         leader to follower  log tail offset 8
 * CLOSE             either way          reason 2, text length 2; then the text
 * </pre>
 */
@Getter
public enum FrameType {

    HELLO(1, 24),
    WELCOME(2, 16),
    SESSION(3, 10),
    SESSION_STATUS(4, 5),
    STORE_EVENT(5, 28),
    SNAPSHOT_BEGIN(6, 32),
    SNAPSHOT_MESSAGE(7, 16),
    SNAPSHOT_END(8, 8),
    ACK(9, 8),
    HEARTBEAT(10, 8),
    CLOSE(11, 4);

    private static final FrameType[] BY_CODE = new FrameType[256];

    static {
        for (FrameType type : values()) {
            BY_CODE[type.code] = type;
        }
    }

    private final int code;
    private final int fixedPayloadBytes;

    FrameType(int code, int fixedPayloadBytes) {
        this.code = code;
        this.fixedPayloadBytes = fixedPayloadBytes;
    }

    /**
     * @return the type, or null for a code no version of the format defines
     */
    public static FrameType forCode(int code) {
        return BY_CODE[code & 0xFF];
    }
}
