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
package org.lolaf.staffix.jvmwarmup;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.timer.MutableTimeout;
import org.lolaf.ringos.timer.Timeout;
import org.lolaf.ringos.timer.WheelTimer;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.executor.MessageExecutor;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.SemVer;
import org.lolaf.staffix.jvmwarmup.fix.msg.MessageTypes;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Initiator-side application. On logon and on every received echo, schedules the next send through
 * the provided {@link WheelTimer}; with throttling==0 the send runs inline. Uses VarHandle-style
 * decoder mappings (inside {@link JvmWarmupMessageDecoder}) to warm the alternate dispatch path.
 */
@Slf4j
class JvmWarmupInitiatorApplication implements FixApplication {

    private final Duration throttling;
    private final WheelTimer throttlingTimer;
    private final MutableTimeout reusableTimeout;
    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong messagesReceived = new AtomicLong();

    private FixSession fixSession;
    private JvmWarmupMessageBuilder messageBuilder;

    JvmWarmupInitiatorApplication(Duration throttling, WheelTimer throttlingTimer) {
        this.throttling = throttling;
        this.throttlingTimer = throttlingTimer;
        this.reusableTimeout = throttlingTimer != null ? throttlingTimer.newReusableTimeout() : null;
    }

    @Override
    public FixApiVersion getFixApiVersion() {
        return FixApiVersion.of("jvm-warmup-api", SemVer.SEM_VER_V1);
    }

    @Override
    public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings,
                                         FixSession fixSession,
                                         Set<MessageType> encodedMessagesTypes) {
        this.fixSession = fixSession;
        this.messageBuilder = new JvmWarmupMessageBuilder(fixSession);
        encodedMessagesTypes.add(MessageTypes.JVMWarmup);
        return List.of(new JvmWarmupMessageDecoder(this::onEcho));
    }

    @Override
    public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
        log.info("Initiator logon {}", fixSession.getFixSessionId());
        scheduleNextSend(fixSession.getMessageExecutor(JvmWarmupMessageDecoder.class, () -> 0));
    }

    private void onEcho(MessageExecutor<Void, Void, Void, Void> messageExecutor) {
        messagesReceived.incrementAndGet();
        scheduleNextSend(messageExecutor);
    }

    private void scheduleNextSend(MessageExecutor<Void, Void, Void, Void> messageExecutor) {
        if (throttlingTimer == null) {
            sendNow(null, null, null, null);
            return;
        }

        Timeout t = throttlingTimer.schedule(reusableTimeout, this::submitSend, messageExecutor, throttling.toNanos(), TimeUnit.NANOSECONDS);
        if (t.isRejected()) {
            log.warn("Throttling submission queue full for session {} — dropping send", fixSession.getFixSessionId());
        }
    }

    private void submitSend(MessageExecutor<Void, Void, Void, Void> messageExecutor) {
        messageExecutor.execute(this::sendNow, null, null, null, null);
    }

    private void sendNow(Void message, Void param1, Void param2, Void param3) {
        messageBuilder.populate();
        fixSession.send(messageBuilder.getEncoder(), null);
        messagesSent.incrementAndGet();
    }

    long getMessagesSent() {
        return messagesSent.get();
    }

    long getMessagesReceived() {
        return messagesReceived.get();
    }
}
