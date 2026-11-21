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
import org.jspecify.annotations.Nullable;

/**
 * The frames of the replication stream, with the size of their fixed payload; a variable part (a string, a message)
 * follows it.
 *
 * <pre>
 * frame             direction           fixed payload
 * HELLO             follower to leader  magic 4, version 2, node id length 2, last applied epoch 8,
 *                                       last applied offset 8, lane 2, encryption mode 1, reserved 5, nonce 16;
 *                                       then the node id
 * WELCOME           leader to follower  magic 4, version 2, leader id length 2, resume offset 8, lane count 2,
 *                                       encryption mode 1, reserved 5, nonce 16; then the leader id
 * KEY_CONFIRMATION         follower to leader  HMAC-SHA256 32 of the HELLO and WELCOME, when both peers encrypt
 * SESSION           leader to follower  session key 4, session type 1, replication mode 1, session id length 2,
 *                                       storage identity length 2; then the session id, then the identity
 * SESSION_ANSWER    follower to leader  session key 4, answer 1
 * STORE_EVENT       leader to follower  log offset 8, session key 4, event type 1, fragment flags 1, reserved 2,
 *                                       sequence number 8, MsgType hash 4, message length 4; then the message
 * SNAPSHOT_BEGIN    leader to follower  session key 4, reserved 4, snapshot offset 8, next incoming 8, next outgoing 8
 * SNAPSHOT_MESSAGE  leader to follower  session key 4, message length 4, sequence number 8, MsgType hash 4,
 *                                       reserved 4; then the message, its fragment flags in the header's flags
 * SNAPSHOT_END      leader to follower  session key 4, message count 4, next incoming 8, next outgoing 8
 * ACK               follower to leader  applied offset 8, pending writes 8
 * RECEIVED          follower to leader  received offset 8
 * HEARTBEAT         leader to follower  log tail offset 8
 * CLOSE             either way          reason 2, text length 2; then the text
 * SESSION_SETTINGS_DIGEST leader to follower  generation 4, session type 1, reserved 1, store length 2,
 *                                       session id length 2, digest length 2, store identity length 2,
 *                                       document length 2; then the store, session id, digest, identity and document
 * SESSION_SETTINGS_DIGESTS_COMPLETE    leader to follower  generation 4, digest count 4
 * SESSIONS_ANNOUNCED leader to follower  announced count 4, reserved 4
 * </pre>
 *
 * <p>The {@link #isSealed() sealed} types are the ones carrying data. The others go without a tag; since a frame's
 * length counts its tag, sealing more types changes no layout.
 */
@Getter
public enum FrameType {

    HELLO(1, 48, false),
    WELCOME(2, 40, false),
    SESSION(3, 10, true),
    SESSION_ANSWER(4, 5, false),
    STORE_EVENT(5, 32, true),
    SNAPSHOT_BEGIN(6, 32, false),
    SNAPSHOT_MESSAGE(7, 24, true),
    SNAPSHOT_END(8, 24, false),
    ACK(9, 16, false),
    HEARTBEAT(10, 8, false),
    CLOSE(11, 4, false),
    RECEIVED(12, 8, false),
    SESSION_SETTINGS_DIGEST(13, 16, true),
    SESSION_SETTINGS_DIGESTS_COMPLETE(14, 8, false),
    SESSIONS_ANNOUNCED(15, 8, false),
    KEY_CONFIRMATION(16, 32, false);

    private static final FrameType[] BY_CODE = new FrameType[256];

    static {
        for (FrameType type : values()) {
            BY_CODE[type.code] = type;
        }
    }

    private final int code;
    private final int fixedPayloadBytes;
    /**
     * On an encrypted connection, the frame ends with a tag and its variable part is encrypted.
     */
    private final boolean sealed;

    FrameType(int code, int fixedPayloadBytes, boolean sealed) {
        this.code = code;
        this.fixedPayloadBytes = fixedPayloadBytes;
        this.sealed = sealed;
    }

    /**
     * @return the type, or null for a code no version of the format defines
     */
    public static @Nullable FrameType forCode(int code) {
        return BY_CODE[code & 0xFF];
    }
}
