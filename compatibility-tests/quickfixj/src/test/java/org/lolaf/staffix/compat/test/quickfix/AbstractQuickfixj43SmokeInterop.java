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
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.codec.decoders.DecodedFixMessageDecoder;
import org.lolaf.staffix.fix43.encoders.EmailEncoder;
import org.lolaf.staffix.fix43.encoders.group.LinesOfTextEncoder;
import org.lolaf.staffix.fix43.fields.EmailThreadID;
import org.lolaf.staffix.fix43.fields.EmailType;
import org.lolaf.staffix.fix43.fields.Subject;
import org.lolaf.staffix.fix43.msg.MessageTypes;
import quickfix.Message;
import quickfix.field.EncodedText;
import quickfix.field.EncodedTextLen;
import quickfix.field.Text;
import quickfix.fix43.Email;

import java.util.function.Consumer;

/**
 * {@link AbstractQuickfixjSmokeInterop} over FIX.4.3.
 * <p>
 * The other dictionary that cannot be generated from the FIX Orchestra repository, so what staffix ships for it
 * descends from QuickFIX/J's own XML with edits on top - see {@link AbstractQuickfixj42SmokeInterop}, whose reason
 * for existing is the same.
 * <p>
 * The repeating group is {@code LinesOfText} here where FIX.4.4 and later name it {@code NoLinesOfText}, on both
 * sides, which is why the two Email builders are overridden whole.
 */
abstract class AbstractQuickfixj43SmokeInterop extends AbstractQuickfixjSmokeInterop {

    @Override
    String beginString() {
        return "FIX.4.3";
    }

    @Override
    String compIdTag() {
        return "43";
    }

    @Override
    FixApiVersion staffixApiVersion() {
        return FixApiVersion.of(FixRegularVersion.VERSION_43);
    }

    @Override
    FixSessionId staffixFixSessionId() {
        return FixSessionId.of("test", FixRegularVersion.VERSION_43,
                staffixIsInitiator() ? initiatorCompId : acceptorCompId,
                staffixIsInitiator() ? acceptorCompId : initiatorCompId);
    }

    @Override
    String quickfixApplicationDictionaryResource() {
        return "FIX43.xml";
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
        LinesOfTextEncoder linesOfText = email.addLinesOfText(2);
        for (int i = 0; i < 2; i++) {
            linesOfText.setText("test text");
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
            Email.LinesOfText linesOfText = new Email.LinesOfText();
            linesOfText.set(new Text("test text"));
            linesOfText.set(new EncodedTextLen("encoded text".length()));
            linesOfText.set(new EncodedText("encoded text"));
            email.addGroup(linesOfText);
        }
        return email;
    }

    @Override
    Message newQuickfixSequenceReset() {
        return new quickfix.fix43.SequenceReset();
    }
}
