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
package org.lolaf.staffix.api;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.lolaf.staffix.api.session.FixSessionId;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * A session a {@link FixInitiator} can run, the {@link FixInitiatorBuilder#getMainTarget() main one} or one it can be
 * {@link FixInitiator#switchTo switched} to, and the addresses to dial for it.
 *
 * <p>The id and the addresses come as a pair because a counterparty's other site may use another session id, another
 * IP, or both.
 */
@Value
@Builder(toBuilder = true)
public class FixInitiatorTarget {

    /**
     * A main target's must have initiator settings in one of the engine's
     * {@link org.lolaf.staffix.api.session.FixSessionsSettingsStore}s; a backup's must have none, it runs on its
     * main target's.
     */
    FixSessionId fixSessionId;

    /**
     * At least one. Several are the counterparty's endpoints for this same session, tried in turn,
     * {@link FixInitiatorBuilder#getConnectionRetry()} apart, until one accepts.
     */
    @Singular
    List<InetSocketAddress> connectAddresses;
}
