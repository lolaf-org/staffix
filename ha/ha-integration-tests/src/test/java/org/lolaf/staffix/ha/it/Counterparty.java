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

import org.lolaf.staffix.api.codec.FixFieldsDecoderMapper;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.fix44.encoders.EmailEncoder;
import org.lolaf.staffix.fix44.fields.NoLinesOfText;
import org.lolaf.staffix.fix44.fields.Text;
import org.lolaf.staffix.fix44.msg.MessageTypes;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;

/**
 * The other side of the session, recording what the pair looks like from outside.
 */
final class Counterparty extends Fix44Application {

    /**
     * The Subject asking {@link Echo} for an answer longer than two frames of the default replication log, in Text
     * lines rather than one field, as a large message is.
     */
    static final String BIG_REQUEST = "big";
    static final int BIG_ANSWER_LINES = 5 << 10;
    static final String BIG_ANSWER_LINE = "x".repeat(1024);
    static final int BIG_ANSWER_LENGTH = BIG_ANSWER_LINES * 1024;

    final AtomicInteger logons = new AtomicInteger();
    final AtomicInteger logoutsReceived = new AtomicInteger();
    final AtomicInteger resendRequests = new AtomicInteger();
    final AtomicLong answers = new AtomicLong();
    /**
     * The Text length of the last answer, and of the longest received again as a possible duplicate.
     */
    final AtomicInteger lastTextLength = new AtomicInteger();
    final AtomicInteger longestPossibleDuplicate = new AtomicInteger();
    private volatile FixSession session;

    @Override
    public void onLogon(FixSession fixSession, DecodedFixMessage logonMessage) {
        session = fixSession;
        logons.incrementAndGet();
    }

    @Override
    public void onPreLogout(FixSession fixSession, String message, boolean logoutInitiated) {
        if (!logoutInitiated) {
            logoutsReceived.incrementAndGet();
        }
    }

    @Override
    public void onResendRequestInitiated(FixSession fixSession, long fromSeqNum, long toSeqNum) {
        resendRequests.incrementAndGet();
    }

    @Override
    public List<FixMessageDecoder> setup(FixSessionSettings settings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
        return List.of(new FixMessageDecoder() {
            private int textLength;

            @Override
            public MessageType getMessageType() {
                return MessageTypes.Email;
            }

            @Override
            public void mapFieldsForDecoding(FixFieldsDecoderMapper mapper, FieldsRegistry fieldsRegistry) {
                mapper.forGroup(NoLinesOfText.get()).mapStringField(Text.get(), value -> textLength += value == null ? 0 : value.length(), null);
            }

            @Override
            public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
                lastTextLength.set(textLength);
                if (possDupFlag) {
                    longestPossibleDuplicate.accumulateAndGet(textLength, Math::max);
                }
                textLength = 0;
                answers.incrementAndGet();
            }
        });
    }

    /**
     * Asks for one {@link #BIG_ANSWER_LENGTH} answer and waits for it.
     */
    void bigRoundTrip() {
        long expected = answers.get() + 1;
        session.send(session.newEncoder(EmailEncoder.class).begin().setSubject(BIG_REQUEST), null);
        await().atMost(java.time.Duration.ofSeconds(30)).until(() -> answers.get() >= expected);
    }

    void roundTrips(int count) {
        EmailEncoder ping = session.newEncoder(EmailEncoder.class).asReusable();
        for (int i = 0; i < count; i++) {
            long expected = answers.get() + 1;
            session.send(ping.begin().setSubject("ping"), null);
            await().until(() -> answers.get() >= expected);
        }
    }
}
