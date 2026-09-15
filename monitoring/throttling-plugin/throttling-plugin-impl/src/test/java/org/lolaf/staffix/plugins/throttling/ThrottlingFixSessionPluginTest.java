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
package org.lolaf.staffix.plugins.throttling;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.codec.FixFieldsEncoder;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.api.time.UTCTime;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ThrottlingFixSessionPluginTest {

    private static final MessageType NEW_ORDER = MessageType.of("D", false);
    private static final long WINDOW_NANOS = 1_000_000_000L; // 1s
    private static final UTCTime T = UTCTime.of(0);

    private static ThrottlingFixSessionPlugin<PluginContext> wrap(FixSessionPlugin<PluginContext, Object> delegate,
                                                                  int maxReceived, int maxSent) {
        return new ThrottlingFixSessionPlugin<>(delegate, maxReceived, maxSent, WINDOW_NANOS);
    }

    private static void inboundGroup(ThrottlingFixSessionPlugin<PluginContext> p, long ts) {
        p.onMessageDecodingStarted(NEW_ORDER, ts, T);
        p.onMessageDecodingFinished(NEW_ORDER, ts, T);
        p.onMessageReceived(NEW_ORDER, 5, ts, T);
    }

    private static void encodingGroup(ThrottlingFixSessionPlugin<PluginContext> p, long ts) {
        ByteBuffer body = ByteBuffer.allocate(2);
        body.put(new byte[]{1, 2}).flip();
        // The engine produces the token first, hands it to onMessageEncodingStarted, then threads it into the
        // later callbacks.
        Object token = p.getMessageEncodingToken(NEW_ORDER, ts);
        p.onMessageEncodingStarted(NEW_ORDER, ts, token);
        p.onMessageEncodedBody(NEW_ORDER, body, mock(FixFieldsEncoder.class), ts, token);
        p.onMessageEncodingFinished(NEW_ORDER, ts, token);
    }

    @Test
    void inboundGroupIsAdmittedOrDroppedAtomically() {
        RecordingPlugin delegate = new RecordingPlugin();
        ThrottlingFixSessionPlugin<PluginContext> p = wrap(delegate, 1, 0);

        inboundGroup(p, 0);   // admitted (1st in window)
        inboundGroup(p, 100); // dropped (same window, budget spent)

        // Exactly one full group reached the delegate — never a started without its finished/received.
        assertThat(delegate.decodingStarted.get()).isEqualTo(1);
        assertThat(delegate.decodingFinished.get()).isEqualTo(1);
        assertThat(delegate.received.get()).isEqualTo(1);
        assertThat(p.getDroppedReceivedCount()).isEqualTo(1);
    }

    @Test
    void inboundBudgetResetsOnWindowRollover() {
        RecordingPlugin delegate = new RecordingPlugin();
        ThrottlingFixSessionPlugin<PluginContext> p = wrap(delegate, 1, 0);

        inboundGroup(p, 0);                  // window 0
        inboundGroup(p, 2 * WINDOW_NANOS);   // window 2 -> admitted again

        assertThat(delegate.received.get()).isEqualTo(2);
        assertThat(p.getDroppedReceivedCount()).isZero();
    }

    @Test
    void encodingGroupIsAdmittedOrDroppedAtomically() {
        RecordingPlugin delegate = new RecordingPlugin();
        ThrottlingFixSessionPlugin<PluginContext> p = wrap(delegate, 0, 1);

        encodingGroup(p, 0);   // admitted
        encodingGroup(p, 100); // dropped (same window)

        assertThat(delegate.encodingStarted.get()).isEqualTo(1);
        assertThat(delegate.encodedBody.get()).isEqualTo(1);
        assertThat(delegate.encodingFinished.get()).isEqualTo(1);
        assertThat(p.getDroppedEncodeCount()).isEqualTo(1);
    }

    @Test
    void encodingGroupStaysAtomicWhenStartedRanOnAnotherThread() throws InterruptedException {
        RecordingPlugin delegate = new RecordingPlugin();
        // Budget is ample; the point is the app→I/O thread hop, not exhaustion.
        ThrottlingFixSessionPlugin<PluginContext> p = wrap(delegate, 0, 10);

        // getMessageEncodingToken / onMessageEncodingStarted fire on the producing application thread (from
        // FixMessageEncoder.begin()); the token the getter returns is what the engine stashes on the encoder.
        AtomicReference<Object> token = new AtomicReference<>();
        Thread appThread = new Thread(() -> {
            Object t = p.getMessageEncodingToken(NEW_ORDER, 0);
            p.onMessageEncodingStarted(NEW_ORDER, 0, t);
            token.set(t);
        });
        appThread.start();
        appThread.join();

        // ...while onMessageEncodedBody / onMessageEncodingFinished fire later on the session I/O thread
        // (from FixMessageEncoder.encode()), driven here from the main (≠ app) thread, with the token the
        // engine carried over. The admission decision reaches them via the token, not a thread-local.
        ByteBuffer body = ByteBuffer.allocate(2);
        body.put(new byte[]{1, 2}).flip();
        p.onMessageEncodedBody(NEW_ORDER, body, mock(FixFieldsEncoder.class), 0, token.get());
        p.onMessageEncodingFinished(NEW_ORDER, 0, token.get());

        // The whole group is admitted together across the thread hop — started with its body and finished.
        assertThat(delegate.encodingStarted.get()).isEqualTo(1);
        assertThat(delegate.encodedBody.get()).isEqualTo(1);
        assertThat(delegate.encodingFinished.get()).isEqualTo(1);
        assertThat(p.getDroppedEncodeCount()).isZero();
    }

    @Test
    void sentIsThrottledIndependentlyOfTheEncodingGroup() {
        RecordingPlugin delegate = new RecordingPlugin();
        ThrottlingFixSessionPlugin<PluginContext> p = wrap(delegate, 0, 1);

        // Same window, maxSent=1: the encoding group and onMessageSent each get their own budget,
        // so both are admitted even though they'd share a single combined budget.
        encodingGroup(p, 0);
        p.onMessageSent(NEW_ORDER, 5, 0, T);
        assertThat(delegate.encodingStarted.get()).isEqualTo(1);
        assertThat(delegate.sent.get()).isEqualTo(1);

        // A second send in the same window is dropped by the sent limiter.
        p.onMessageSent(NEW_ORDER, 5, 100, T);
        assertThat(delegate.sent.get()).isEqualTo(1);
        assertThat(p.getDroppedSentCount()).isEqualTo(1);
    }

    @Test
    void zeroMaxDisablesThrottling() {
        RecordingPlugin delegate = new RecordingPlugin();
        ThrottlingFixSessionPlugin<PluginContext> p = wrap(delegate, 0, 0);

        for (int i = 0; i < 100; i++) {
            inboundGroup(p, 0);
            encodingGroup(p, 0);
            p.onMessageSent(NEW_ORDER, 5, 0, T);
        }

        assertThat(delegate.received.get()).isEqualTo(100);
        assertThat(delegate.encodingFinished.get()).isEqualTo(100);
        assertThat(delegate.sent.get()).isEqualTo(100);
        assertThat(p.getDroppedReceivedCount()).isZero();
        assertThat(p.getDroppedEncodeCount()).isZero();
        assertThat(p.getDroppedSentCount()).isZero();
    }

    @Test
    void valueReturningAndLifecycleCallbacksAreAlwaysDelegated() {
        RecordingPlugin delegate = new RecordingPlugin();
        ThrottlingFixSessionPlugin<PluginContext> p = wrap(delegate, 1, 1);

        assertThat(p.requiresTimeMeasurement()).isTrue();
        assertThat(p.getPluginContext()).isEmpty();

        p.onLogon();
        p.onLogout();
        p.onSessionDestroyed("engine-1", mock(FixSessionId.class));

        assertThat(delegate.logon.get()).isEqualTo(1);
        assertThat(delegate.logout.get()).isEqualTo(1);
        assertThat(delegate.sessionDestroyed.get()).isEqualTo(1);
    }

    // --- Test fixtures -------------------------------------------------------------------------------

    private static final class RecordingPlugin implements FixSessionPlugin<PluginContext, Object> {
        final AtomicInteger decodingStarted = new AtomicInteger();
        final AtomicInteger decodingFinished = new AtomicInteger();
        final AtomicInteger received = new AtomicInteger();
        final AtomicInteger encodingStarted = new AtomicInteger();
        final AtomicInteger encodedBody = new AtomicInteger();
        final AtomicInteger encodingFinished = new AtomicInteger();
        final AtomicInteger sent = new AtomicInteger();
        final AtomicInteger logon = new AtomicInteger();
        final AtomicInteger logout = new AtomicInteger();
        final AtomicInteger sessionDestroyed = new AtomicInteger();

        @Override
        public boolean isForPluginContext(Class<? extends PluginContext> pluginClass) {
            return false;
        }

        @Override
        public Optional<PluginContext> getPluginContext() {
            return Optional.empty();
        }

        @Override
        public void onLogon() {
            logon.incrementAndGet();
        }

        @Override
        public void onLogout() {
            logout.incrementAndGet();
        }

        @Override
        public void onMessageDecodingStarted(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
            decodingStarted.incrementAndGet();
        }

        @Override
        public void onMessageDecodingFinished(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
            decodingFinished.incrementAndGet();
        }

        @Override
        public void onMessageReceived(MessageType messageType, int payloadSize, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
            received.incrementAndGet();
        }

        @Override
        public void onMessageEncodingStarted(MessageType messageType, long encodingStartTimeInNanos, Object encodingToken) {
            encodingStarted.incrementAndGet();
        }

        @Override
        public void onMessageEncodedBody(MessageType messageType, ByteBuffer encodedBody, FixFieldsEncoder<?> fixFieldsEncoder, long encodingStartTimeInNanos, Object encodingToken) {
            this.encodedBody.incrementAndGet();
        }

        @Override
        public void onMessageEncodingFinished(MessageType messageType, long encodingEndTimeInNanos, Object encodingToken) {
            encodingFinished.incrementAndGet();
        }

        @Override
        public void onMessageSent(MessageType messageType, int payloadSize, long localSendingTimeInNanos, UTCTime localSendingTime) {
            sent.incrementAndGet();
        }

        @Override
        public void onSessionDestroyed(String fixInstanceId, FixSessionId fixSessionId) {
            sessionDestroyed.incrementAndGet();
        }
    }
}
