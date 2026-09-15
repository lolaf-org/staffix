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

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.TestingClock;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.mockito.Mockito;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

class TestFixMessageResendTransformer {
    FixDictionaryId fixDictionaryId;
    FixMessageResendTransformer transformer;
    FieldsRegistry fieldsRegistry;

    @BeforeEach
    void before() {
        fixDictionaryId = FixDictionaryId.of("tests", FixRegularVersion.VERSION_44);
        MessageTypeRegistry messageTypeRegistry = MessageTypeRegistry.Registry.getInstance(fixDictionaryId);
        fieldsRegistry = FieldsRegistry.Registry.getInstance(fixDictionaryId);
        transformer = new FixMessageResendTransformer(TimeUnit.MICROSECONDS, TestingClock.get(), Mockito.mock(FixSessionId.class), messageTypeRegistry, fieldsRegistry);
    }

    @Test
    void testTransformWithGroup() {

        String message = "8=FIX.4.4\u00019=192\u000135=C\u000134=6\u000149=SENDER44_TEST\u000156=TARGET44_TEST\u000152=20241027-21:48:15.516767\u000194=0\u0001" +
                "164=test thread id\u0001147=test subject\u000133=2\u000158=test text\u0001354=12\u0001355=encoded text\u000158=test text\u0001354=12\u0001355=encoded text\u000110=020\u0001";

        DecodedFixMessage transformed = transformer.transformForResend(ByteBuffer.wrap(message.getBytes()));

        Assertions.assertThat(transformed.containsField(fieldsRegistry.find(33))).isTrue();
        Assertions.assertThat(transformed.getGroup(fieldsRegistry.find(33)).getEntries()).hasSize(2);

        String expected = "35=C\u000134=6\u0001122=20241027-21:48:15.516767\u000143=Y\u000194=0\u0001164=test thread id\u0001147=test subject\u000133=2\u0001" +
                "58=test text\u0001354=12\u0001355=encoded text\u000158=test text\u0001354=12\u0001355=encoded text\u0001";

        Assertions.assertThat(transformed).hasToString(expected);
    }

    @Test
    void testTransformForResend() {

        String message = "8=FIX.4.49=8635=A34=123449=TARGET_TEST52=20241013-19:07:17.86156=SENDER_TEST98=0108=30141=Y10=149";

        DecodedFixMessage transformed = transformer.transformForResend(ByteBuffer.wrap(message.getBytes()));

        Assertions.assertThat(transformed.containsField(fieldsRegistry.find(CoreFields.MESSAGE_TYPE))).isTrue();
        Assertions.assertThat(transformed.containsField(fieldsRegistry.find(CoreFields.ORIG_SENDING_TIME))).isTrue();
        Assertions.assertThat(transformed.containsField(fieldsRegistry.find(CoreFields.POSS_DUP_FLAG))).isTrue();
        Assertions.assertThat(transformed.containsField(fieldsRegistry.find(CoreFields.MESSAGE_SEQ_NUM))).isTrue();
        Assertions.assertThat(transformed.containsField(fieldsRegistry.find(98))).isTrue();
        Assertions.assertThat(transformed.containsField(fieldsRegistry.find(108))).isTrue();
        Assertions.assertThat(transformed.containsField(fieldsRegistry.find(141))).isTrue();

        Assertions.assertThat(transformed.containsField(fieldsRegistry.find(CoreFields.SENDING_TIME))).isFalse();
        Assertions.assertThat(transformed.containsField(fieldsRegistry.find(CoreFields.BODY_LENGTH))).isFalse();
        Assertions.assertThat(transformed.containsField(fieldsRegistry.find(CoreFields.CHECKSUM))).isFalse();
        Assertions.assertThat(transformed.containsField(fieldsRegistry.find(CoreFields.BEGIN_STRING))).isFalse();
        Assertions.assertThat(transformed.containsField(fieldsRegistry.find(49))).isFalse();
        Assertions.assertThat(transformed.containsField(fieldsRegistry.find(56))).isFalse();
    }
}