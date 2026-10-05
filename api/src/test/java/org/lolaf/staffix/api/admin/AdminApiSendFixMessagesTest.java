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
package org.lolaf.staffix.api.admin;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionId.FixSessionIdBuilder;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AdminApiSendFixMessagesTest {

    private static final FixSessionId TRADING = FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionIdBuilder.builder()
            .group("alpha").name("trading").senderCompID("ALPHA").targetCompID("BETA").build());

    private final AdminApi adminApi = mock(AdminApi.class);

    AdminApiSendFixMessagesTest() {
        doCallRealMethod().when(adminApi).sendFixMessages(eq(TRADING), anyList(), anyChar(), anyBoolean());
    }

    @Test
    void sendsEachMessageInTurn() {
        adminApi.sendFixMessages(TRADING, List.of("35=B|148=one|", "35=B|148=two|"), '|', true);

        InOrder order = inOrder(adminApi);
        order.verify(adminApi).sendFixMessage(TRADING, "35=B|148=one|", '|', true);
        order.verify(adminApi).sendFixMessage(TRADING, "35=B|148=two|", '|', true);
    }

    @Test
    void stopsAtTheFirstRefusedMessageAndSaysWhichItIs() {
        doThrow(new IllegalArgumentException("unknown tag 9999"))
                .when(adminApi).sendFixMessage(TRADING, "35=B|9999=x|", '|', false);

        assertThatThrownBy(() -> adminApi.sendFixMessages(TRADING, List.of("35=B|148=one|", "35=B|9999=x|", "35=B|148=three|"), '|', false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message 2 of 3: unknown tag 9999");
        verify(adminApi).sendFixMessage(TRADING, "35=B|148=one|", '|', false);
        verify(adminApi, never()).sendFixMessage(TRADING, "35=B|148=three|", '|', false);
    }

    @Test
    void keepsTheRefusalOfASingleMessageAsItIs() {
        IllegalStateException notLoggedIn = new IllegalStateException("trading is not logged in");
        doThrow(notLoggedIn).when(adminApi).sendFixMessage(eq(TRADING), anyString(), anyChar(), anyBoolean());

        assertThatThrownBy(() -> adminApi.sendFixMessages(TRADING, List.of("35=B|"), '|', false)).isSameAs(notLoggedIn);
    }

    @Test
    void keepsTheTypeOfARefusal() {
        doThrow(new IllegalStateException("trading is not logged in"))
                .when(adminApi).sendFixMessage(eq(TRADING), anyString(), anyChar(), anyBoolean());

        assertThatThrownBy(() -> adminApi.sendFixMessages(TRADING, List.of("35=B|", "35=B|"), '|', false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Message 1 of 2: trading is not logged in");
    }
}
