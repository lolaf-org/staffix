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
package org.lolaf.staffix.ha.replication.wire;

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.replication.spi.RecordFlag;

/**
 * The frames of the replication stream, with the size of their fixed payload; a variable part (a string, a record's
 * payload, a stream's state) follows it.
 *
 * <pre>
 * frame              direction           fixed payload
 * HELLO              follower to leader  magic 4, version 2, node id length 2, last applied epoch 8,
 *                                        last applied offset 8, lane 2, encryption mode 1, reserved 1,
 *                                        application 4, nonce 16; then the node id
 * WELCOME            leader to follower  magic 4, version 2, leader id length 2, resume offset 8, lane count 2,
 *                                        encryption mode 1, reserved 1, application 4, nonce 16; then the leader id
 * KEY_CONFIRMATION   follower to leader  HMAC-SHA256 32 of the HELLO and WELCOME, when both peers encrypt
 * STREAM             leader to follower  stream key 4, replication mode 1, reserved 1, stream name length 2,
 *                                        storage identity length 2; then the name, then the identity
 * STREAM_ANSWER      follower to leader  stream key 4, answer 1
 * RECORD             leader to follower  log offset 8, then a record body: stream key 4, content type 1, flags 1,
 *                                        reserved 2, position 8, payload length 4, reserved 4; then the payload
 * SNAPSHOT_BEGIN     leader to follower  stream key 4, reserved 2, state length 2, snapshot offset 8; then the state
 * SNAPSHOT_RECORD    leader to follower  a record body, as RECORD's; then the payload
 * SNAPSHOT_END       leader to follower  stream key 4, record count 4, reserved 2, state length 2; then the state
 * ACK                follower to leader  applied offset 8, pending writes 8
 * RECEIVED           follower to leader  received offset 8
 * HEARTBEAT          leader to follower  log tail offset 8
 * CLOSE              either way          reason 2, text length 2; then the text
 * STREAMS_ANNOUNCED  leader to follower  announced count 4, reserved 4
 * </pre>
 *
 * <p>A record's content type is the application's, never read by the replication; its flags are
 * {@link WireFormat#MORE_FRAGMENTS}, {@link WireFormat#CONTINUED_FRAGMENT}, {@link RecordFlag#PLAINTEXT} and
 * {@link RecordFlag#RESET}. A stream's state is opaque bytes too, which a snapshot carries for the application to
 * resume the stream from.
 *
 * <p>On an encrypted connection the frames after the handshake end with a tag, whatever their type; see
 * {@link WireFormat}.
 */
@Getter
public enum FrameType {

    HELLO(1, 48),
    WELCOME(2, 40),
    STREAM(3, 10),
    STREAM_ANSWER(4, 5),
    RECORD(5, 32),
    SNAPSHOT_BEGIN(6, 16),
    SNAPSHOT_RECORD(7, 24),
    SNAPSHOT_END(8, 12),
    ACK(9, 16),
    HEARTBEAT(10, 8),
    CLOSE(11, 4),
    RECEIVED(12, 8),
    STREAMS_ANNOUNCED(13, 8),
    KEY_CONFIRMATION(14, 32);

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
    public static @Nullable FrameType forCode(int code) {
        return BY_CODE[code & 0xFF];
    }
}
