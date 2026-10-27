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
package org.lolaf.staffix.examples.ha;

import lombok.experimental.UtilityClass;
import org.lolaf.primos.Peer;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.net.InetSocketAddress;

/**
 * Where every process of the example listens, all on localhost, and the session they share. The cluster name and the
 * secret must match the witness's configuration in {@code witness/}.
 */
@UtilityClass
class Topology {

    static final String HOST = "127.0.0.1";
    static final String CLUSTER = "staffix-ha-example";
    static final String CLUSTER_SECRET = "an-example-secret-change-it";

    static final int WITNESS_ID = Peer.MAX_ID;
    static final int WITNESS_PORT = 7420;

    static final FixSessionId GATEWAY_SESSION = FixSessionId.of("ha", FixRegularVersion.VERSION_44, "GATEWAY", "CLIENT");
    static final FixSessionId CLIENT_SESSION = FixSessionId.of("ha", FixRegularVersion.VERSION_44, "CLIENT", "GATEWAY");

    static int electionPort(int nodeId) {
        return 7420 + nodeId;
    }

    static InetSocketAddress replicationAddress(int nodeId) {
        return new InetSocketAddress(HOST, 17100 + nodeId);
    }

    static InetSocketAddress fixAddress(int nodeId) {
        return new InetSocketAddress(HOST, 7110 + nodeId);
    }
}
