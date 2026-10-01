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
package org.lolaf.staffix.spring.boot.mapper;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.spring.boot.props.InitiatorProps;
import org.lolaf.staffix.spring.boot.props.InitiatorTargetProps;
import org.lolaf.staffix.spring.boot.spi.FixSessionIdProps;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InitiatorPropsMapperTest {

    private static FixSessionIdProps sessionId(String senderCompId) {
        FixSessionIdProps sessionId = new FixSessionIdProps();
        sessionId.setName("test");
        sessionId.setFixVersion("VERSION_44");
        sessionId.setSenderCompId(senderCompId);
        sessionId.setTargetCompId("ACCEPTOR");
        return sessionId;
    }

    private static InitiatorTargetProps target(FixSessionIdProps sessionId, String... connectAddresses) {
        InitiatorTargetProps target = new InitiatorTargetProps();
        target.setFixSessionId(sessionId);
        target.setConnectAddresses(List.of(connectAddresses));
        return target;
    }

    private static InitiatorProps initiator(InitiatorTargetProps mainTarget, InitiatorTargetProps... backupTargets) {
        InitiatorProps props = new InitiatorProps();
        props.setMainTarget(mainTarget);
        props.setBackupTargets(List.of(backupTargets));
        return props;
    }

    @Test
    void theMainTargetIsRequired() {
        assertThatThrownBy(() -> InitiatorPropsMapper.build("primary", initiator(null), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("staffix.initiators.primary.main-target is required");
    }

    @Test
    void aMainTargetWithoutAddressIsRejected() {
        assertThatThrownBy(() -> InitiatorPropsMapper.build("primary", initiator(target(sessionId("INITIATOR"))), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("staffix.initiators.primary.main-target.connect-addresses is required");
    }

    @Test
    void aBackupTargetWithoutSessionIdIsRejected() {
        InitiatorProps props = initiator(target(sessionId("INITIATOR"), "localhost:17001"), target(null, "localhost:17002"));

        assertThatThrownBy(() -> InitiatorPropsMapper.build("primary", props, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("staffix.initiators.primary.backup-targets[0].fix-session-id is required");
    }

    @Test
    void aBackupTargetWithoutAddressIsRejected() {
        InitiatorProps props = initiator(target(sessionId("INITIATOR"), "localhost:17001"), target(sessionId("INITIATOR_DR")));

        assertThatThrownBy(() -> InitiatorPropsMapper.build("primary", props, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("staffix.initiators.primary.backup-targets[0].connect-addresses is required");
    }
}
