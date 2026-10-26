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
package org.lolaf.staffix.api.ha;

/**
 * How a {@link HaAdmin#switchover() switchover} ended.
 */
public enum SwitchoverOutcome {
    /**
     * Leadership left this node, which follows from now on; its sessions run on the new leader, from where this node
     * left them.
     */
    TRANSFERRED,
    /**
     * No follower held every replicated session in sync before the timeout: nothing was changed.
     */
    NO_FOLLOWER_IN_SYNC,
    /**
     * The follower was not sent every write before the timeout: this node leads its sessions again.
     */
    NOT_HANDED_OVER,
    /**
     * The leader election did not move leadership before the timeout: this node leads its sessions again.
     */
    NOT_TRANSFERRED
}
