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
package org.lolaf.staffix.api.grpc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * gRPC's five-byte frame header. Small enough to test exhaustively, and worth doing so: a reader that is
 * one byte out turns every subsequent frame into nonsense.
 */
class GrpcFramingTest {

    @Test
    void roundTripsAFrameHeader() {
        byte[] buffer = new byte[GrpcFraming.HEADER_LENGTH + 3];

        GrpcFraming.writeHeader(buffer, 0, false, 3);

        assertThat(GrpcFraming.isCompressed(buffer, 0)).isFalse();
        assertThat(GrpcFraming.messageLength(buffer, 0)).isEqualTo(3);
    }

    @Test
    void roundTripsACompressedFrameHeader() {
        byte[] buffer = new byte[GrpcFraming.HEADER_LENGTH];

        GrpcFraming.writeHeader(buffer, 0, true, 0);

        assertThat(GrpcFraming.isCompressed(buffer, 0)).isTrue();
        assertThat(GrpcFraming.messageLength(buffer, 0)).isZero();
    }

    @Test
    void writesTheLengthBigEndian() {
        byte[] buffer = new byte[GrpcFraming.HEADER_LENGTH];

        GrpcFraming.writeHeader(buffer, 0, false, 0x01020304);

        assertThat(buffer).containsExactly(0x00, 0x01, 0x02, 0x03, 0x04);
    }

    @Test
    void writesAndReadsAtAnOffset() {
        byte[] buffer = new byte[16];

        GrpcFraming.writeHeader(buffer, 7, true, 1234);

        assertThat(GrpcFraming.isCompressed(buffer, 7)).isTrue();
        assertThat(GrpcFraming.messageLength(buffer, 7)).isEqualTo(1234);
    }

    @Test
    void carriesTheLargestLengthAnArrayCanHold() {
        byte[] buffer = new byte[GrpcFraming.HEADER_LENGTH];

        GrpcFraming.writeHeader(buffer, 0, false, Integer.MAX_VALUE);

        assertThat(GrpcFraming.messageLength(buffer, 0)).isEqualTo(Integer.MAX_VALUE);
    }

    /**
     * The length field is unsigned, so it can describe a message no array can hold. Taking the low bits
     * would turn a corrupt frame into a plausible one, which is the failure worth refusing loudly.
     */
    @Test
    void refusesALengthNoArrayCouldHold() {
        byte[] buffer = new byte[GrpcFraming.HEADER_LENGTH];
        // 0xFFFFFFFF: every length bit set, which as a signed int would read as -1.
        buffer[1] = (byte) 0xFF;
        buffer[2] = (byte) 0xFF;
        buffer[3] = (byte) 0xFF;
        buffer[4] = (byte) 0xFF;

        assertThatThrownBy(() -> GrpcFraming.messageLength(buffer, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4294967295")
                .hasMessageContaining("corrupt");
    }

    @Test
    void refusesATruncatedHeader() {
        byte[] tooShort = new byte[GrpcFraming.HEADER_LENGTH - 1];

        assertThatThrownBy(() -> GrpcFraming.messageLength(tooShort, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrpcFraming.isCompressed(tooShort, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrpcFraming.writeHeader(tooShort, 0, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAHeaderThatWouldRunPastTheBuffer() {
        byte[] buffer = new byte[8];

        assertThatThrownBy(() -> GrpcFraming.writeHeader(buffer, 4, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrpcFraming.messageLength(buffer, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesANegativeLengthAndANegativeOffset() {
        byte[] buffer = new byte[GrpcFraming.HEADER_LENGTH];

        assertThatThrownBy(() -> GrpcFraming.writeHeader(buffer, 0, false, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrpcFraming.writeHeader(buffer, -1, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrpcFraming.messageLength(buffer, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
