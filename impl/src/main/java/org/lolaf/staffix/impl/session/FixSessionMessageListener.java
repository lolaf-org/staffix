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
package org.lolaf.staffix.impl.session;

import org.lolaf.staffix.api.time.UTCTime;

/**
 * The two session layer events every message raises, which a {@link FixSessionLayerComponent} implements in addition
 * to the interface when it cares about the traffic itself rather than about what the session does.
 *
 * <p>They are here rather than on the component because they are the only ones on the hot path: a session doing
 * 100k messages a second would otherwise pay a dispatch per component per message for the one that reacts.
 * {@link FixSessionLayerComponents} holds a single listener rather than a list, so a message costs one call to the
 * one class that implements this, with nothing to iterate.
 */
interface FixSessionMessageListener {

    /**
     * A message has been read from the peer, whether or not its decoding succeeded. A garbled one is not announced:
     * section 4.8 disregards it as if it had never arrived, so it is not the sign of life a heartbeat interval
     * counts.
     */
    default void onMessageReceived(UTCTime receiveTime) {
    }

    /**
     * A message has been written and stored, its MsgSeqNum(34) spent.
     */
    default void onMessageSent(UTCTime sendingTime) {
    }
}
