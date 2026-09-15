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
package org.lolaf.staffix.tests;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.MessageType;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestRawFixSocketClient {

    private static final char SOH = (char) 1;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final MessageType REJECT = MessageType.of("3", true);

    private static String fix(String... fields) {
        return String.join(String.valueOf(SOH), fields) + SOH;
    }

    @Test
    void testReadMessageOfTypeSkipsTheMessagesBeforeIt() throws Exception {
        String heartbeat = fix("8=FIX.4.4", "9=30", "35=0", "34=1", "10=001");
        String reject = fix("8=FIX.4.4", "9=40", "35=3", "34=2", "373=10", "10=002");

        try (FakePeer peer = FakePeer.serving(heartbeat + reject)) {
            assertThat(peer.session().readMessageOfType(REJECT, TIMEOUT)).isEqualTo(reject);
        }
    }

    @Test
    void testReadMessageWithFieldMatchesTheFieldAndNotASubstring() throws Exception {
        // SessionRejectReason(373) 1 must not be satisfied by the message carrying 373=10, which a substring scan
        // would have stopped on
        String wrongReason = fix("8=FIX.4.4", "9=40", "35=3", "34=1", "373=10", "10=001");
        String rightReason = fix("8=FIX.4.4", "9=40", "35=3", "34=2", "373=1", "10=002");

        try (FakePeer peer = FakePeer.serving(wrongReason + rightReason)) {
            assertThat(peer.session().readMessageWithField(new Tag(373), "1", TIMEOUT)).isEqualTo(rightReason);
        }
    }

    @Test
    void testReadMessageWithFieldGivesUpOnTheTimeoutWhenNothingMatches() throws Exception {
        String heartbeat = fix("8=FIX.4.4", "9=30", "35=0", "34=1", "10=001");

        try (FakePeer peer = FakePeer.serving(heartbeat)) {
            // nothing carries the awaited TestReqID: the read gives up on the timeout rather than hanging, and
            // returns what the last read gave, i.e. nothing once the received bytes have been consumed
            assertThat(peer.session().readMessageWithField(new Tag(112), "never-sent", Duration.ofMillis(300)))
                    .isEmpty();
        }
    }

    @Test
    void testSplitMessagesReturnsEachMessageOfTheBuffer() {
        String reject = fix("8=FIX.4.4", "9=40", "35=3", "34=2", "58=some text", "10=123");
        String logout = fix("8=FIX.4.4", "9=30", "35=5", "34=3", "10=124");

        List<String> messages = RawFixSocketClient.splitMessages(reject + logout);

        assertThat(messages).containsExactly(reject, logout);
    }

    @Test
    void testSplitMessagesIgnoresATrailingIncompleteMessage() {
        String complete = fix("8=FIX.4.4", "9=40", "35=3", "34=2", "10=123");
        // the second message stops before its CheckSum(10) has been fully received
        String truncated = "8=FIX.4.4" + SOH + "9=40" + SOH + "35=5" + SOH + "34=3" + SOH;

        assertThat(RawFixSocketClient.splitMessages(complete + truncated)).containsExactly(complete);
    }

    @Test
    void testSplitMessagesIgnoresAMessageWhoseChecksumIsNotTerminated() {
        // the CheckSum(10) field itself is started but its closing SOH is still missing
        String partialChecksum = "8=FIX.4.4" + SOH + "9=40" + SOH + "35=3" + SOH + "34=2" + SOH + "10=12";

        assertThat(RawFixSocketClient.splitMessages(partialChecksum)).isEmpty();
    }

    @Test
    void testSplitMessagesOnABufferWithoutAnyChecksum() {
        assertThat(RawFixSocketClient.splitMessages("")).isEmpty();
        assertThat(RawFixSocketClient.splitMessages("not a fix payload at all")).isEmpty();
    }

    @Test
    void testSplitMessagesDoesNotCutOnAChecksumLookalikeInsideAValue() {
        // a Text(58) quoting "10=" must not be mistaken for the trailer of the message
        String message = fix("8=FIX.4.4", "9=60", "35=3", "34=2", "58=expected 10=123 here", "10=200");

        assertThat(RawFixSocketClient.splitMessages(message)).containsExactly(message);
    }

    /**
     * A socket peer that accepts one connection and immediately writes a canned payload to it, so that the reading
     * of {@link RawFixSocketClient.Session} can be driven without a running FIX engine.
     */
    private static final class FakePeer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final Socket served;
        private final RawFixSocketClient.Session session;

        private FakePeer(String payload) throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.session = RawFixSocketClient.connect(serverSocket.getLocalPort(), TIMEOUT);
            this.served = serverSocket.accept();
            served.getOutputStream().write(payload.getBytes(StandardCharsets.US_ASCII));
            served.getOutputStream().flush();
        }

        static FakePeer serving(String payload) throws IOException {
            return new FakePeer(payload);
        }

        RawFixSocketClient.Session session() {
            return session;
        }

        @Override
        public void close() throws IOException {
            session.close();
            served.close();
            serverSocket.close();
        }
    }

    /**
     * Minimal {@link FixField} standing in for a generated field constant, this module depending on the api only.
     */
    private static final class Tag implements FixField {

        private final int code;

        private Tag(int code) {
            this.code = code;
        }

        @Override
        public int getCode() {
            return code;
        }

        @Override
        public byte[] serialized() {
            return (code + "=").getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public int checksum() {
            return 0;
        }

        @Override
        public FieldType getType() {
            return FieldType.STRING;
        }

        @Override
        public FieldLocation getLocation() {
            return FieldLocation.BODY;
        }

        @Override
        public int getAsInt() {
            return code;
        }
    }
}
