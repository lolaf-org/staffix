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
 * The caller-owned reply. Its whole point is being reusable, so that is what these check.
 */
class GrpcReplyTest {

    @Test
    void startsEmptyAndSuccessful() {
        GrpcReply reply = new GrpcReply();

        assertThat(reply.isOk()).isTrue();
        assertThat(reply.getStatusMessage()).isEmpty();
        assertThat(reply.getMessageLength()).isZero();
        assertThat(reply.getMessage()).isEmpty();
    }

    @Test
    void carriesAFailureStatusAndItsMessage() {
        GrpcReply reply = new GrpcReply();

        reply.status(GrpcStatus.UNAVAILABLE, "collector is restarting");

        assertThat(reply.isOk()).isFalse();
        assertThat(reply.getStatusCode()).isEqualTo(GrpcStatus.UNAVAILABLE);
        assertThat(reply.getStatusMessage()).isEqualTo("collector is restarting");
    }

    @Test
    void neverReportsANullStatusMessage() {
        GrpcReply reply = new GrpcReply();

        reply.status(GrpcStatus.INTERNAL, null);

        assertThat(reply.getStatusMessage()).isEmpty();
    }

    @Test
    void keepsTheBufferItAlreadyHasWhenItIsBigEnough() {
        GrpcReply reply = new GrpcReply();
        byte[] first = reply.messageBuffer(64);

        byte[] second = reply.messageBuffer(32);

        assertThat(second)
                .as("a reply that fits is answered from the buffer already held")
                .isSameAs(first);
    }

    @Test
    void growsTheBufferWhenAReplyDoesNotFit() {
        GrpcReply reply = new GrpcReply();
        byte[] first = reply.messageBuffer(16);

        byte[] grown = reply.messageBuffer(1024);

        assertThat(grown).isNotSameAs(first).hasSizeGreaterThanOrEqualTo(1024);
    }

    @Test
    void resetClearsTheOutcomeButKeepsTheBuffer() {
        GrpcReply reply = new GrpcReply();
        byte[] buffer = reply.messageBuffer(128);
        reply.messageLength(10);
        reply.status(GrpcStatus.ABORTED, "gone");

        reply.reset();

        assertThat(reply.isOk()).isTrue();
        assertThat(reply.getStatusMessage()).isEmpty();
        assertThat(reply.getMessageLength()).isZero();
        assertThat(reply.messageBuffer(128))
                .as("reuse is the reason this object is passed in rather than returned")
                .isSameAs(buffer);
    }

    @Test
    void refusesALengthTheBufferCannotHold() {
        GrpcReply reply = new GrpcReply();
        reply.messageBuffer(8);

        assertThatThrownBy(() -> reply.messageLength(9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reply.messageLength(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reply.messageBuffer(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
