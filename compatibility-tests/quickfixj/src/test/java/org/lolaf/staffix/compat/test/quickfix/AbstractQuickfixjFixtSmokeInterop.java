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
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.api.version.FixtVersion;
import quickfix.Message;

import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;

/**
 * What the FIXT.1.1 versions share: the transport dictionary carries the session layer, the application version is
 * named by DefaultApplVerID(1137) rather than by BeginString(8), and the session id is a FIXT one.
 * <p>
 * Everything here is the same for FIX 5.0, 5.0 SP1 and 5.0 SP2; a subclass says which of them it is through
 * {@link #applVerId()} and supplies the encoders and the QuickFIX/J package that go with it.
 */
abstract class AbstractQuickfixjFixtSmokeInterop extends AbstractQuickfixjSmokeInterop {

    private static final String FIXT_11_BEGIN_STRING = new String(FixtVersion.FIXT_11.getBeginString());

    /**
     * The application version this FIXT session carries.
     */
    abstract FixApplVerID applVerId();

    @Override
    String beginString() {
        return FIXT_11_BEGIN_STRING;
    }

    @Override
    FixApiVersion staffixApiVersion() {
        return FixApiVersion.of(FixApplVerID.getFixVersionForCode(applVerId().getCode()));
    }

    @Override
    FixSessionId staffixFixSessionId() {
        return FixSessionId.ofFIXT11("test", applVerId(),
                staffixIsInitiator() ? initiatorCompId : acceptorCompId,
                staffixIsInitiator() ? acceptorCompId : initiatorCompId);
    }

    @Override
    String quickfixDefaultApplVerId() {
        return applVerId().getCode();
    }

    @Override
    String quickfixTransportDictionaryResource() {
        return "FIXT11.xml";
    }

    @Override
    Message newQuickfixSequenceReset() {
        return new quickfix.fixt11.SequenceReset();
    }

    /**
     * Under FIXT the BeginString names the transport and nothing else, so {@link #testLogonNamesTheExpectedFixVersion}
     * would be satisfied by a session that never agreed on an application version at all. DefaultApplVerID(1137), on
     * the Logon that went out and on the one that came back, is what says these two engines negotiated the dictionary
     * this class exists to test.
     */
    @Test
    void testLogonNamesTheExpectedApplicationVersion() {
        logon();

        await().untilAsserted(() -> assertThatFixMessage(
                messagesOfType(staffixLogger.getOutgoingMessages(), CoreMessageType.LOGON))
                .as("the staffix Logon must name %s as its DefaultApplVerID(1137)", applVerId())
                .containsFieldWithValue(CoreFields.DEFAULT_APPL_VER_ID, applVerId().getCode()));
        await().untilAsserted(() -> assertThatFixMessage(
                messagesOfType(staffixLogger.getIncomingMessages(), CoreMessageType.LOGON))
                .as("the QuickFIX/J Logon must name %s as its DefaultApplVerID(1137)", applVerId())
                .containsFieldWithValue(CoreFields.DEFAULT_APPL_VER_ID, applVerId().getCode()));
    }
}
