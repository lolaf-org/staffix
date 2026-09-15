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
package org.lolaf.staffix.codec.encoders;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.TestingClock;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixFieldsEncoder;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.mockito.Mockito;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.Supplier;

class TestFixMessageEncoderImpl {

    TestingEncoder encoder = new TestingEncoder(TestFixMessageEncoderImpl.getMessageType());

    private static MessageType getMessageType() {
        return MessageType.of("A", false);
    }

    private static String getEncodedMessageToString(ByteBuffer encoded) {
        byte[] encodedBytes = new byte[encoded.position()];
        encoded.flip().get(encodedBytes);
        return new String(encodedBytes);
    }

    private static FixSessionId getFixSessionId(String sender, String target) {
        return FixSessionId.of("test", FixRegularVersion.VERSION_42, sender, target);
    }

    @Test
    void testCopy() {

        TestingEncoder encoderToCopy = new TestingEncoder(TestFixMessageEncoderImpl.getMessageType());
        encoderToCopy.begin();
        for (int i = 0; i < 4; i++) {
            encoderToCopy.addString(FixField.of(91, FieldType.STRING, FieldLocation.BODY), "ABCDEFG");
        }

        // crate a very small body buffer
        encoder = new TestingEncoder(TestFixMessageEncoderImpl.getMessageType(), ByteBuffer::allocate, 128, 8, 128);

        encoder.begin().copy(encoderToCopy);

        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 1, getFixSessionId("sender1", "target1"), Mockito.mock(FixApplication.class),
                TimeUnit.MICROSECONDS, TestingClock.get().now(), null);


        Assertions.assertThat(getEncodedMessageToString(encoded))
                .isEqualTo("8=FIX.4.2\u00019=104\u000135=A\u000134=1\u000149=sender1\u000156=target1\u000152=19700101-00:00:00.000000\u0001" +
                        "91=ABCDEFG\u000191=ABCDEFG\u000191=ABCDEFG\u000191=ABCDEFG\u000110=253\u0001");

    }

    @Test
    void testMessageEncoding() {
        encoder.begin().addString(FixField.of(91, FieldType.STRING, FieldLocation.BODY), "ABCD")
                .addInt(FixField.of(98, FieldType.INT, FieldLocation.BODY), 0)
                .addInt(FixField.of(384, FieldType.INT, FieldLocation.BODY), 2)
                .addChar(FixField.of(372, FieldType.CHAR, FieldLocation.BODY), 'D')
                .addChar(FixField.of(385, FieldType.CHAR, FieldLocation.BODY), 'R')
                .addInt(FixField.of(372, FieldType.INT, FieldLocation.BODY), 8);

        FixApplication app = new FixApplication() {

            @Override
            public FixApiVersion getFixApiVersion() {
                return FixApiVersion.of(FixRegularVersion.VERSION_44);
            }

            @Override
            public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
                return List.of();
            }

            @Override
            public void onMessageEncoding(FixSession fixSession, MessageType messageType, Supplier<FixFieldsEncoder> headersAppender, Supplier<FixFieldsEncoder> trailerAppender) {
                headersAppender.get().addInt(FixField.of(90, FieldType.STRING, FieldLocation.BODY), 4);
                trailerAppender.get().addString(FixField.of(385, FieldType.STRING, FieldLocation.BODY), "S");
            }
        };

        FixSession fixSession = Mockito.mock(FixSession.class);

        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 1, getFixSessionId("sender", "target"), app, TimeUnit.MICROSECONDS, TestingClock.get().now(), fixSession);

        Assertions.assertThat(getEncodedMessageToString(encoded))
                .isEqualTo("8=FIX.4.2\u00019=106\u000135=A\u000134=1\u000149=sender\u000156=target\u000152=19700101-00:00:00.000000\u000190=4\u000191=ABCD\u000198=0\u0001384=2\u0001372=D\u0001385=R\u0001372=8\u0001385=S\u000110=153\u0001");
    }

    @Test
    void testMessageEncodingWithTooSmallBuffers() {
        encoder = new TestingEncoder(TestFixMessageEncoderImpl.getMessageType(), ByteBuffer::allocate, 1, 1, 1);
        testMessageEncoding();
    }

    private static class TestingEncoder extends FixMessageEncoderImpl<TestingEncoder> {

        public TestingEncoder(MessageType messageType, IntFunction<ByteBuffer> allocator, int headerBufferCapacity, int bodyBufferCapacity, int trailerBufferCapacity) {
            super(null, messageType, allocator, headerBufferCapacity, bodyBufferCapacity, trailerBufferCapacity, null, null);
        }

        public TestingEncoder(MessageType messageType) {
            super(null, messageType, null, null, null);
        }

    }
}
