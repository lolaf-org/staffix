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
package org.lolaf.staffix.impl;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.net.InetSocketAddress;

/**
 * Session ids are interned for the whole JVM: an acceptor session created before the initiator's id exists must not
 * leave a mirror image of it behind. The harness creates both ids up front, which would hide this.
 */
class TestFixAcceptorSessionCreatedFirst extends AbstractFixTests {

    @Test
    void initiatorLogsOnWithItsOwnCompIds() {
        setupAcceptorSessionsSettings(getAcceptorFixSessionSettings()
                .fixSessionId(FixSessionId.of("acceptor-first", FixRegularVersion.VERSION_44, "BROKER_FIRST", "CLIENT_FIRST"))
                .build());
        startFixAcceptor();

        FixSessionId initiatorSessionId = FixSessionId.of("acceptor-first", FixRegularVersion.VERSION_44, "CLIENT_FIRST", "BROKER_FIRST");
        fixInitiatorBuilder = fixInitiatorBuilder.toBuilder()
                .instanceId("acceptor-first-initiator")
                .mainTarget(FixInitiatorTarget.builder()
                        .fixSessionId(initiatorSessionId)
                        .connectAddress(new InetSocketAddress("localhost", acceptorPort))
                        .build())
                .build();
        setupInitiatorSessionsSettings(getInitiatorFixSessionSettings().fixSessionId(initiatorSessionId).build());

        logonClient();
    }
}
