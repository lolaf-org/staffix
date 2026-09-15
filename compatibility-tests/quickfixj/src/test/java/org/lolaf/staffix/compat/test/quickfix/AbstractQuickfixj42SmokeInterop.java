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
import org.lolaf.staffix.fix42.encoders.EmailEncoder;
import org.lolaf.staffix.fix42.encoders.group.LinesOfTextEncoder;
import org.lolaf.staffix.fix42.fields.EmailThreadID;
import org.lolaf.staffix.fix42.fields.EmailType;
import org.lolaf.staffix.fix42.fields.Subject;
import org.lolaf.staffix.fix42.msg.MessageTypes;
import quickfix.Message;
import quickfix.field.EncodedText;
import quickfix.field.EncodedTextLen;
import quickfix.field.Text;
import quickfix.fix42.Email;

import java.util.function.Consumer;

/**
 * {@link AbstractQuickfixjSmokeInterop} over FIX.4.2, the oldest dictionary staffix ships.
 * <p>
 * Worth more than a version number in a list: FIX.4.2 and FIX.4.3 are the two dictionaries that cannot be generated
 * from the FIX Orchestra repository, so what staffix ships for them descends from QuickFIX/J's own XML with edits on
 * top. Putting the two engines on a socket is what says those edits did not change what goes on the wire.
 * <p>
 * The repeating group is {@code LinesOfText} here where FIX.4.4 and later name it {@code NoLinesOfText}, on both
 * sides, which is why the two Email builders are overridden whole.
 */
abstract class AbstractQuickfixj42SmokeInterop extends AbstractQuickfixjSmokeInterop {

    @Override
    String beginString() {
        return "FIX.4.2";
    }

    @Override
    String compIdTag() {
        return "42";
    }

    @Override
    FixApiVersion staffixApiVersion() {
        return FixApiVersion.of(FixRegularVersion.VERSION_42);
    }

    @Override
    FixSessionId staffixFixSessionId() {
        return FixSessionId.of("test", FixRegularVersion.VERSION_42,
                staffixIsInitiator() ? initiatorCompId : acceptorCompId,
                staffixIsInitiator() ? acceptorCompId : initiatorCompId);
    }

    @Override
    String quickfixApplicationDictionaryResource() {
        return "FIX42.xml";
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
        return new quickfix.fix42.SequenceReset();
    }
}
