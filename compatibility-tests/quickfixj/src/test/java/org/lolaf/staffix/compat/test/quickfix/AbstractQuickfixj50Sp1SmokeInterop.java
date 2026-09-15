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

import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.codec.decoders.DecodedFixMessageDecoder;
import org.lolaf.staffix.fix50sp1.encoders.EmailEncoder;
import org.lolaf.staffix.fix50sp1.encoders.group.NoLinesOfTextEncoder;
import org.lolaf.staffix.fix50sp1.fields.EmailThreadID;
import org.lolaf.staffix.fix50sp1.fields.EmailType;
import org.lolaf.staffix.fix50sp1.fields.Subject;
import org.lolaf.staffix.fix50sp1.msg.MessageTypes;
import quickfix.Message;
import quickfix.field.EncodedText;
import quickfix.field.EncodedTextLen;
import quickfix.field.Text;
import quickfix.fix50sp1.Email;

import java.util.function.Consumer;

/**
 * {@link AbstractQuickfixjSmokeInterop} over FIXT.1.1 carrying FIX 5.0 SP1.
 */
abstract class AbstractQuickfixj50Sp1SmokeInterop extends AbstractQuickfixjFixtSmokeInterop {

    @Override
    FixApplVerID applVerId() {
        return FixApplVerID.FIX50SP1;
    }

    @Override
    String compIdTag() {
        return "50SP1";
    }

    @Override
    String quickfixApplicationDictionaryResource() {
        return "FIX50SP1.xml";
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
}
