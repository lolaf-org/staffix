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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.lolaf.staffix.tests.FixMessageAssert.assertThatFixMessage;

/**
 * What every FIX version has to do against QuickFIX/J: log on, and carry an application message each way.
 * <p>
 * These two are separated from the rest of {@link AbstractQuickfixjInterop} because they are the ones worth running
 * against <b>every</b> dictionary staffix ships, where the deeper scenarios - retransmission, gap fill, sequence
 * reset, schedules - exercise session-layer code that is the same whatever the application dictionary is, and are
 * therefore run in depth on two versions only. What differs per version is the encoders, the field registries and
 * the dictionary each side validates against, and that is exactly what a logon and one Email each way put on the
 * wire.
 * <p>
 * Subclasses supply the version through the hooks on {@link AbstractQuickfixjHarness}, and a concrete class per role
 * runs the pair with staffix accepting and with staffix initiating.
 */
abstract class AbstractQuickfixjSmokeInterop extends AbstractQuickfixjHarness {

    @Test
    void testLogon() {
        logon();

        assertThat(staffixSession.isLoggedIn()).isTrue();
        await().untilAsserted(() -> assertThat(quickfixConnector.isLoggedOn()).isTrue());
    }

    @Test
    void testSendApplicationMessageEachWay() {
        logon();

        sendEmailFromStaffix(1);
        assertQuickfixReceivedEmail(1);

        sendEmailFromQuickfix(2);
        assertStaffixReceivedEmail(2);

        // neither side took the other's message for a retransmission of one it had missed
        assertThat(quickfixReceivedEmails).noneMatch(ReceivedEmail::isPossDup);
        assertThat(staffixReceivedEmails).noneMatch(ReceivedEmail::isPossDup);
    }

    /**
     * The session really is the version this class stands for, on both Logons.
     * <p>
     * A subclass that forgets an override inherits the harness's FIX.4.4 defaults and passes everything above while
     * testing nothing new - a second set of greens that pins no dictionary. Asserting BeginString(8) on the Logon
     * that went out and on the one that came back is what makes that impossible; the FIXT versions add
     * DefaultApplVerID(1137) to it in {@link AbstractQuickfixjFixtSmokeInterop}, since under FIXT the BeginString
     * alone names no application version at all.
     */
    @Test
    void testLogonNamesTheExpectedFixVersion() {
        logon();

        await().untilAsserted(() -> assertThatFixMessage(
                messagesOfType(staffixLogger.getOutgoingMessages(), CoreMessageType.LOGON))
                .as("the staffix Logon must carry BeginString(8)=%s", beginString())
                .containsFieldWithValue(CoreFields.BEGIN_STRING, beginString()));
        await().untilAsserted(() -> assertThatFixMessage(
                messagesOfType(staffixLogger.getIncomingMessages(), CoreMessageType.LOGON))
                .as("the QuickFIX/J Logon must carry BeginString(8)=%s", beginString())
                .containsFieldWithValue(CoreFields.BEGIN_STRING, beginString()));
    }
}
