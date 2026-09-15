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

import java.util.List;
import java.util.Set;

/**
 * Acceptor-side application: on every received {@code JVMWarmup} message, immediately encode and
 * send one back. Holds no per-message state and uses lambda-style decoder mappings so it warms the
 * lambda-Consumer dispatch in {@link org.lolaf.staffix.api.codec.FixFieldsDecoderMapper}.
 */
@Slf4j
class JvmWarmupAcceptorApplication implements FixApplication {

    private FixSession fixSession;
    private JvmWarmupMessageBuilder messageBuilder;

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
        return List.of(new JvmWarmupMessageDecoder(this::echoBack));
    }

    @Override
    public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
        log.info("JVM warmup acceptor logon {}", fixSession.getFixSessionId());
    }

    private void echoBack(MessageExecutor<Void, Void, Void, Void> messageExecutor) {
        messageBuilder.populate();
        fixSession.send(messageBuilder.getEncoder(), null);
    }
}