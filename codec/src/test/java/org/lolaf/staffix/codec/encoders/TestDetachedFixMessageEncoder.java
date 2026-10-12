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

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.TestingClock;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixFieldsEncoder;
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.codec.FixMessageEncodingListener;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class TestDetachedFixMessageEncoder {

    private static final MessageType MESSAGE_TYPE = MessageType.of("B", false);
    private static final FixField HEADLINE = FixField.of(148, FieldType.STRING, FieldLocation.BODY);
    private static final FixSessionId SESSION_ID = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
    private static final UTCTime SENDING_TIME = TestingClock.get().now();

    private final FixApplication application = mock(FixApplication.class);
    private final FixSession fixSession = mock(FixSession.class);
    private final List<FixMessageEncoder<?>> released = new CopyOnWriteArrayList<>();

    private ListeningEncoder source(FixMessageEncodingListener listener) {
        return new ListeningEncoder(listener);
    }

    private String encode(FixMessageEncoder<?> encoder) {
        ByteBuffer encoded = encoder.encode(ByteBuffer::allocate, 7, SESSION_ID, application, TimeUnit.MILLISECONDS, SENDING_TIME, fixSession);
        byte[] bytes = new byte[encoded.position()];
        encoded.flip().get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    @Test
    void aTransferredMessageEncodesExactlyLikeTheOriginal() {
        doAnswer(invocation -> {
            invocation.<Supplier<FixFieldsEncoder>>getArgument(2).get().addString(FixField.of(50, FieldType.STRING, FieldLocation.HEADER), "DESK");
            invocation.<Supplier<FixFieldsEncoder>>getArgument(3).get().addString(FixField.of(93, FieldType.STRING, FieldLocation.TRAILER), "X");
            return null;
        }).when(application).onMessageEncoding(eq(fixSession), eq(MESSAGE_TYPE), any(), any());
        ListeningEncoder source = source(null);
        String original = encode(source.begin().addString(HEADLINE, "EUR/USD moves"));
        source.release();
        DetachedFixMessageEncoder carrier = new DetachedFixMessageEncoder(released::add);

        source.begin().addString(HEADLINE, "EUR/USD moves");
        source.transferPendingMessageTo(carrier);
        source.release();
        source.begin().addString(HEADLINE, "the next message, begun on the freed encoder");

        assertThat(carrier.getMessageType()).isEqualTo(MESSAGE_TYPE);
        assertThat(encode(carrier)).isEqualTo(original);
        carrier.release();
        assertThat(released).containsExactly(carrier);
        assertThat(carrier.isAvailable()).isTrue();
    }

    @Test
    void theCarrierGrowsToTakeALargerBody() {
        ListeningEncoder source = source(null);
        String headline = "x".repeat(10_000);
        String original = encode(source.begin().addString(HEADLINE, headline));
        source.release();
        DetachedFixMessageEncoder carrier = new DetachedFixMessageEncoder(released::add);

        source.begin().addString(HEADLINE, headline);
        source.transferPendingMessageTo(carrier);

        assertThat(encode(carrier)).isEqualTo(original);
    }

    /**
     * Plugins see each message through the encoding listener, keeping their state on the encoder between the start of
     * the encoding and its end: the carrier must end the encoding the source started, with that state.
     */
    @Test
    void theCarrierEndsTheEncodingWithTheSourcesListenerAndPluginState() {
        FixMessageEncodingListener listener = mock(FixMessageEncodingListener.class);
        List<Object> stateAtEnd = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            stateAtEnd.add(invocation.<FixMessageEncoder<?>>getArgument(2).pluginEncodingState()[0]);
            stateAtEnd.add(invocation.getArgument(3));
            return null;
        }).when(listener).onEncodingEnd(any(), any(), any(), any(Long.class));
        ListeningEncoder source = source(listener);
        DetachedFixMessageEncoder carrier = new DetachedFixMessageEncoder(released::add);

        source.begin().addString(HEADLINE, "EUR/USD moves");
        source.pluginEncodingState(1)[0] = "span of the first message";
        source.transferPendingMessageTo(carrier);
        source.release();
        source.begin();
        source.pluginEncodingState(1)[0] = "span of the next message";
        encode(carrier);

        assertThat(stateAtEnd).containsExactly("span of the first message", ListeningEncoder.STARTED_AT);
    }

    @Test
    void nothingIsTransferredFromAnEncoderWithNoMessageBegun() {
        ListeningEncoder source = source(null);

        assertThatThrownBy(() -> source.transferPendingMessageTo(new DetachedFixMessageEncoder(released::add)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No message begun");
    }

    private static final class ListeningEncoder extends FixMessageEncoderImpl<ListeningEncoder> {

        private static final long STARTED_AT = 42;

        private ListeningEncoder(FixMessageEncodingListener listener) {
            super(encoder -> {
            }, MESSAGE_TYPE, ByteBuffer::allocate, 16, 16, 16, listener, startingAt(STARTED_AT));
        }

        private static Clock startingAt(long nanoTime) {
            TestingClock clock = new TestingClock();
            clock.setNanoTime(nanoTime);
            return clock;
        }
    }
}
