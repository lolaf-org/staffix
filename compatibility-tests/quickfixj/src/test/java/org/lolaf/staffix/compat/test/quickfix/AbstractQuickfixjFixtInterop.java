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
package org.lolaf.staffix.compat.test.quickfix;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.api.version.FixtVersion;
import org.lolaf.staffix.codec.decoders.DecodedFixMessageDecoder;
import org.lolaf.staffix.fix50sp2.encoders.EmailEncoder;
import org.lolaf.staffix.fix50sp2.encoders.group.NoLinesOfTextEncoder;
import org.lolaf.staffix.fix50sp2.fields.EmailThreadID;
import org.lolaf.staffix.fix50sp2.fields.EmailType;
import org.lolaf.staffix.fix50sp2.fields.Subject;
import org.lolaf.staffix.fix50sp2.msg.MessageTypes;
import quickfix.Message;
import quickfix.field.EncodedText;
import quickfix.field.EncodedTextLen;
import quickfix.field.Text;
import quickfix.fix50sp2.Email;

import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;

/**
 * Runs every scenario of {@link AbstractQuickfixjInterop} over FIXT.1.1 carrying FIX 5.0 SP2, rather than over the
 * FIX.4.4 the base class defaults to.
 * <p>
 * This is not the same code path on either side. Under FIXT the session-level messages - Logon, ResendRequest,
 * SequenceReset - come from the transport dictionary rather than from the application one, so every resend, gap fill
 * and sequence reset in the suite is built and decoded through a different dictionary than the FIX.4.4 classes
 * exercise. The Logon additionally carries DefaultApplVerID(1137), which is what tells the peer which dictionary the
 * application messages are to be read with.
 * <p>
 * The version-specific overrides below are the whole of the difference; the scenarios themselves are inherited
 * unchanged, which is the point of the harness. {@link #testLogonIsFixtCarryingFix50Sp2} is the guard that this is
 * really so: without it a mistake in one of the overrides could leave these classes quietly speaking FIX.4.4 and
 * reporting a second set of greens that pin nothing new.
 */
abstract class AbstractQuickfixjFixtInterop extends AbstractQuickfixjInterop {

    private static final String FIXT_11_BEGIN_STRING = new String(FixtVersion.FIXT_11.getBeginString());

    @Override
    String beginString() {
        return FIXT_11_BEGIN_STRING;
    }

    @Override
    String compIdTag() {
        return "50SP2";
    }

    @Override
    FixApiVersion staffixApiVersion() {
        return FixApiVersion.of(FixRegularVersion.VERSION_50_SP2);
    }

    @Override
    FixSessionId staffixFixSessionId() {
        return FixSessionId.ofFIXT11("test", FixApplVerID.FIX50SP2,
                staffixIsInitiator() ? initiatorCompId : acceptorCompId,
                staffixIsInitiator() ? acceptorCompId : initiatorCompId);
    }

    @Override
    String quickfixDefaultApplVerId() {
        return FixApplVerID.FIX50SP2.getCode();
    }

    @Override
    String quickfixApplicationDictionaryResource() {
        return "FIX50SP2.xml";
    }

    @Override
    String quickfixTransportDictionaryResource() {
        return "FIXT11.xml";
    }

    @Override
    FixMessageDecoder recordingEmailDecoder(Consumer<ReceivedEmail> consumer) {
        return new DecodedFixMessageDecoder(MessageTypes.Email) {
            @Override
            public void onDecoded(FixSession fixSession, boolean possDupFlag, boolean possResend) {
                DecodedFixMessage decoded = getDecodedFixMessage();
                consumer.accept(new ReceivedEmail(
                        decoded.getString(EmailThreadID.get(), ""),
                        decoded.getString(Subject.get(), ""),
                        possDupFlag,
                        possResend));
                super.onDecoded(fixSession, possDupFlag, possResend);
            }
        };
    }

    @Override
    void sendEmailFromStaffix(int index) {
        EmailEncoder email = staffixSession.newEncoder(EmailEncoder.class);
        email.begin()
                .setEmailType(EmailType.EmailTypeValues.NEW)
                .setEmailThreadID(emailThreadId(index))
                .setSubject(emailSubject(index));
        NoLinesOfTextEncoder linesOfText = email.addNoLinesOfText(2);
        for (int i = 0; i < 2; i++) {
            linesOfText.setText("test text");
            // EncodedTextLen belongs to both the FIXT transport dictionary and the application one; the application
            // dictionary's singleton wins in the FIXT field registry, so encoding and decoding agree on one instance
            linesOfText.setEncodedTextLen("encoded text".length());
            linesOfText.setEncodedText("encoded text");
        }
        staffixSession.send(email, null);
    }

    @Override
    Message newQuickfixEmail(int index) {
        Email email = new Email();
        email.set(new quickfix.field.EmailType(quickfix.field.EmailType.NEW));
        email.set(new quickfix.field.EmailThreadID(emailThreadId(index)));
        email.set(new quickfix.field.Subject(emailSubject(index)));
        for (int i = 0; i < 2; i++) {
            Email.NoLinesOfText linesOfText = new Email.NoLinesOfText();
            linesOfText.set(new Text("test text"));
            linesOfText.set(new EncodedTextLen("encoded text".length()));
            linesOfText.set(new EncodedText("encoded text"));
            email.addGroup(linesOfText);
        }
        return email;
    }

    @Override
    Message newQuickfixSequenceReset() {
        return new quickfix.fixt11.SequenceReset();
    }

    /**
     * The session really is FIXT.1.1 and really did agree on FIX 5.0 SP2, rather than a misconfiguration having left
     * these classes running the FIX.4.4 the base class defaults to.
     * <p>
     * Both halves matter. BeginString(8) alone would be satisfied by a FIXT session that never said which application
     * version it carries, and DefaultApplVerID(1137) alone cannot appear outside a FIXT Logon anyway - it is the pair,
     * seen on the Logon that went out and on the one that came back, that says the two engines negotiated what this
     * class exists to test.
     */
    @Test
    void testLogonIsFixtCarryingFix50Sp2() {
        logon();

        await().untilAsserted(() -> assertThatFixMessage(
                messagesOfType(staffixLogger.getOutgoingMessages(), CoreMessageType.LOGON))
                .as("the staffix Logon must be a FIXT.1.1 one naming FIX 5.0 SP2 as its DefaultApplVerID(1137)")
                .containsFieldWithValue(CoreFields.BEGIN_STRING, FIXT_11_BEGIN_STRING)
                .containsFieldWithValue(CoreFields.DEFAULT_APPL_VER_ID, FixApplVerID.FIX50SP2.getCode()));
        await().untilAsserted(() -> assertThatFixMessage(
                messagesOfType(staffixLogger.getIncomingMessages(), CoreMessageType.LOGON))
                .as("the QuickFIX/J Logon must be a FIXT.1.1 one naming FIX 5.0 SP2 as its DefaultApplVerID(1137)")
                .containsFieldWithValue(CoreFields.BEGIN_STRING, FIXT_11_BEGIN_STRING)
                .containsFieldWithValue(CoreFields.DEFAULT_APPL_VER_ID, FixApplVerID.FIX50SP2.getCode()));
    }
}
