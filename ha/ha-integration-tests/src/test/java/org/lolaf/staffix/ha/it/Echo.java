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
package org.lolaf.staffix.ha.it;

import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.fix44.msg.MessageTypes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Answers each Email with an Email, counting what it receives again as possible duplicates, and telling a
 * {@link LoggedOnNodes} its pair shares when its session logs on and off.
 */
final class Echo extends Fix44Application {

    private final LoggedOnNodes loggedOnNodes;

    Echo() {
        this(new LoggedOnNodes());
    }

    Echo(LoggedOnNodes loggedOnNodes) {
        this.loggedOnNodes = loggedOnNodes;
    }

    @Override
    public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
        loggedOnNodes.loggedOn();
    }

    @Override
    public void onLogout(FixSession fixSession, String message, DecodedFixMessage logoutMessage) {
        loggedOnNodes.loggedOff();
    }

    /**
     * Messages received again as possible duplicates, by session name.
     */
    final Map<String, AtomicInteger> possibleDuplicates = new ConcurrentHashMap<>();

    @Override
    public List<FixMessageDecoder> setup(FixSessionSettings settings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
        EmailEncoder answer = fixSession.newEncoder(EmailEncoder.class).asReusable();
        AtomicInteger duplicates = possibleDuplicates.computeIfAbsent(settings.getFixSessionId().getName(), name -> new AtomicInteger());
        return List.of(new FixMessageDecoder() {
            @Override
            public MessageType getMessageType() {
                return MessageTypes.Email;
            }

            @Override
            public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
                if (possDupFlag) {
                    duplicates.incrementAndGet();
                }
                session.send(answer.begin().setSubject("pong"), null);
            }
        });
    }
}
